//! NLLB-200 translation via `ort` (onnxruntime) + `tokenizers`.
//!
//! Greedy decoding with a **KV cache**: the encoder runs once, then the merged
//! decoder (`decoder_model_merged_quantized.onnx`) generates one token per step.
//! Each step's `present.*` key/value outputs are fed back as the next step's
//! `past_key_values.*` inputs, so attention is computed only over the new token
//! instead of re-reading the whole sequence (O(n) total instead of O(n^2)).
//!
//! The merged decoder carries a `use_cache_branch` switch: the first step runs
//! the "no cache" branch with empty (zero-length) past tensors and produces the
//! encoder KV; subsequent steps run the cache branch reusing them.
//!
//! ort uses `load-dynamic`, so this compiles everywhere; it only needs a real
//! libonnxruntime.so at runtime (provided on Android, see callers). Loading the
//! ~865 MB of ONNX dominates cost, so models are kept resident in a global
//! `NllbEngine` keyed by `model_dir`.

use ort::session::{Session, SessionInputValue};
use ort::value::{DynValue, Tensor};
use std::borrow::Cow;
use std::path::Path;
use std::sync::{Mutex, OnceLock};
use tokenizers::Tokenizer;

// NLLB-200-distilled-600M attention geometry (config.json: 16 heads, d_model
// 1024 => head_dim 64). Used only to shape the empty step-0 past tensors.
const NUM_HEADS: i64 = 16;
const HEAD_DIM: i64 = 64;

pub fn smoke(ort_dylib: &str, model_path: &str) -> Result<String, String> {
    init_ort(ort_dylib)?;
    let session = Session::builder()
        .map_err(|e| format!("builder: {e}"))?
        .commit_from_file(model_path)
        .map_err(|e| format!("load: {e}"))?;
    let ins: Vec<String> = session.inputs().iter().map(|i| i.name().to_string()).collect();
    let outs: Vec<String> = session.outputs().iter().map(|o| o.name().to_string()).collect();
    Ok(format!("inputs={ins:?} outputs={outs:?}"))
}

// Loading the dylib is idempotent, but the environment can only be committed
// once per process, so a re-init's `false` is ignored. A load error is kept:
// it is how an onnxruntime older than the `api-*` feature floor shows up.
fn init_ort(ort_dylib: &str) -> Result<(), String> {
    ort::init_from(ort_dylib).map_err(|e| format!("onnxruntime: {e}"))?.commit();
    Ok(())
}

fn i64_tensor(shape: Vec<i64>, data: Vec<i64>) -> Result<Tensor<i64>, String> {
    Tensor::from_array((shape, data)).map_err(|e| e.to_string())
}

fn f32_tensor(shape: Vec<i64>, data: Vec<f32>) -> Result<Tensor<f32>, String> {
    Tensor::from_array((shape, data)).map_err(|e| e.to_string())
}

fn bool_tensor(v: bool) -> Result<Tensor<bool>, String> {
    Tensor::from_array((vec![1i64], vec![v])).map_err(|e| e.to_string())
}

// Zero-length self/cross-attention cache for the merged decoder's first step.
// `from_array` rejects 0-sized dims (raw-data path), so allocate via the default
// CPU allocator, which permits empty tensors.
fn empty_kv() -> Result<Tensor<f32>, String> {
    let alloc = ort::memory::Allocator::default();
    Tensor::<f32>::new(&alloc, [1usize, NUM_HEADS as usize, 0, HEAD_DIM as usize])
        .map_err(|e| e.to_string())
}

fn argmax(row: &[f32]) -> usize {
    let mut best = 0usize;
    let mut best_v = f32::NEG_INFINITY;
    for (i, &v) in row.iter().enumerate() {
        if v > best_v {
            best_v = v;
            best = i;
        }
    }
    best
}

fn ids_max_len(src_len: i64) -> usize {
    (src_len as usize) * 2 + 16
}

fn past_name(layer: usize, side: &str, kv: &str) -> String {
    format!("past_key_values.{layer}.{side}.{kv}")
}

fn present_name(layer: usize, side: &str, kv: &str) -> String {
    format!("present.{layer}.{side}.{kv}")
}

/// Resident NLLB models. `Session::run` needs `&mut self`, so callers hold this
/// behind a `Mutex` (see `ENGINE`); that also makes the raw model state safe to
/// touch from whichever thread UniFFI dispatches on.
struct NllbEngine {
    model_dir: String,
    tokenizer: Tokenizer,
    encoder: Session,
    decoder: Session,
    n_layers: usize,
}

impl NllbEngine {
    fn load(model_dir: &str) -> Result<Self, String> {
        let dir = Path::new(model_dir);
        let tokenizer = Tokenizer::from_file(dir.join("tokenizer.json"))
            .map_err(|e| format!("tokenizer: {e}"))?;
        let encoder = Session::builder()
            .map_err(|e| e.to_string())?
            .commit_from_file(dir.join("encoder_model_quantized.onnx"))
            .map_err(|e| format!("load encoder: {e}"))?;
        let decoder = Session::builder()
            .map_err(|e| e.to_string())?
            .commit_from_file(dir.join("decoder_model_merged_quantized.onnx"))
            .map_err(|e| format!("load decoder: {e}"))?;

        // Detect layer count from the cache inputs and confirm this is actually
        // a merged decoder; surfacing the real input names if our naming
        // assumption is wrong (native errors don't reach logcat).
        let in_names: Vec<String> = decoder.inputs().iter().map(|i| i.name().to_string()).collect();
        let n_layers = (0usize..)
            .take_while(|&i| in_names.iter().any(|n| *n == past_name(i, "decoder", "key")))
            .count();
        if n_layers == 0 {
            return Err(format!("no past_key_values inputs; decoder inputs={in_names:?}"));
        }
        if !in_names.iter().any(|n| n == "use_cache_branch") {
            return Err(format!("not a merged decoder (no use_cache_branch); inputs={in_names:?}"));
        }

        Ok(Self {
            model_dir: model_dir.to_owned(),
            tokenizer,
            encoder,
            decoder,
            n_layers,
        })
    }

    /// Greedy decode. `on_partial` is called after each generated token with the
    /// translation decoded so far, so the UI can stream it left-to-right; pass a
    /// no-op closure for a one-shot translation.
    fn run(
        &mut self,
        text: &str,
        src_lang: &str,
        tgt_lang: &str,
        mut on_partial: impl FnMut(&str),
    ) -> Result<String, String> {
        // Disjoint field borrows: `tok` reads tokenizer while encoder/decoder
        // are mutated; the borrow checker allows this within one method body.
        let tok = &self.tokenizer;
        let eos = tok.token_to_id("</s>").ok_or("no </s> token")? as i64;
        let src_id = tok
            .token_to_id(src_lang)
            .ok_or_else(|| format!("bad src lang {src_lang}"))? as i64;
        let tgt_id = tok
            .token_to_id(tgt_lang)
            .ok_or_else(|| format!("bad tgt lang {tgt_lang}"))? as i64;

        // NLLB source format: [src_lang] tokens... </s>
        let enc = tok.encode(text, false).map_err(|e| format!("encode: {e}"))?;
        let mut ids: Vec<i64> = Vec::with_capacity(enc.get_ids().len() + 2);
        ids.push(src_id);
        ids.extend(enc.get_ids().iter().map(|&x| x as i64));
        ids.push(eos);
        let src_len = ids.len() as i64;
        let mask: Vec<i64> = vec![1; ids.len()];

        // --- Encoder (once) ---
        let enc_out = self
            .encoder
            .run(ort::inputs![
                "input_ids" => i64_tensor(vec![1, src_len], ids)?,
                "attention_mask" => i64_tensor(vec![1, src_len], mask.clone())?,
            ])
            .map_err(|e| format!("encoder run: {e}"))?;
        let (h_shape, h_data) = enc_out["last_hidden_state"]
            .try_extract_tensor::<f32>()
            .map_err(|e| format!("extract hidden: {e}"))?;
        let h_shape: Vec<i64> = h_shape.to_vec();
        let h_data: Vec<f32> = h_data.to_vec();
        drop(enc_out);

        let n = self.n_layers;

        // --- Decoder step 0: no-cache branch over the seed [</s>, tgt_lang] ---
        let seed: Vec<i64> = vec![eos, tgt_id];
        let (mut next_tok, mut dec_kv, enc_kv) = {
            let mut feeds: Vec<(Cow<str>, SessionInputValue)> = Vec::with_capacity(4 + 4 * n);
            feeds.push(("input_ids".into(), i64_tensor(vec![1, 2], seed.clone())?.into()));
            feeds.push((
                "encoder_attention_mask".into(),
                i64_tensor(vec![1, src_len], mask.clone())?.into(),
            ));
            feeds.push((
                "encoder_hidden_states".into(),
                f32_tensor(h_shape.clone(), h_data.clone())?.into(),
            ));
            for i in 0..n {
                feeds.push((past_name(i, "decoder", "key").into(), empty_kv()?.into()));
                feeds.push((past_name(i, "decoder", "value").into(), empty_kv()?.into()));
                feeds.push((past_name(i, "encoder", "key").into(), empty_kv()?.into()));
                feeds.push((past_name(i, "encoder", "value").into(), empty_kv()?.into()));
            }
            feeds.push(("use_cache_branch".into(), bool_tensor(false)?.into()));

            let mut out = self
                .decoder
                .run(feeds)
                .map_err(|e| format!("decoder run (step 0): {e}"))?;

            let next = last_token(&out, /* positions = */ 2)?;
            let dec_kv = take_kv(&mut out, n, "decoder")?;
            let enc_kv = take_kv(&mut out, n, "encoder")?;
            (next, dec_kv, enc_kv)
        };

        // --- Decoder steps 1..: cache branch, one new token per step ---
        let mut gen: Vec<i64> = Vec::new();
        let max_len = ids_max_len(src_len);
        for _ in 0..max_len {
            if next_tok == eos {
                break;
            }
            gen.push(next_tok);
            // Emit the translation decoded so far (one token longer each step).
            let so_far: Vec<u32> = gen.iter().map(|&x| x as u32).collect();
            if let Ok(s) = tok.decode(&so_far, true) {
                on_partial(&s);
            }

            let mut feeds: Vec<(Cow<str>, SessionInputValue)> = Vec::with_capacity(4 + 4 * n);
            feeds.push(("input_ids".into(), i64_tensor(vec![1, 1], vec![next_tok])?.into()));
            feeds.push((
                "encoder_attention_mask".into(),
                i64_tensor(vec![1, src_len], mask.clone())?.into(),
            ));
            // Required graph input even though the cache branch ignores it.
            feeds.push((
                "encoder_hidden_states".into(),
                f32_tensor(h_shape.clone(), h_data.clone())?.into(),
            ));
            // Self-attention cache is consumed (moved) and rebuilt from outputs;
            // cross-attention cache is constant, so feed it by reference.
            let mut dec_iter = dec_kv.drain(..);
            for i in 0..n {
                feeds.push((past_name(i, "decoder", "key").into(), dec_iter.next().unwrap().into()));
                feeds.push((past_name(i, "decoder", "value").into(), dec_iter.next().unwrap().into()));
                feeds.push((past_name(i, "encoder", "key").into(), (&enc_kv[2 * i]).into()));
                feeds.push((past_name(i, "encoder", "value").into(), (&enc_kv[2 * i + 1]).into()));
            }
            drop(dec_iter);
            feeds.push(("use_cache_branch".into(), bool_tensor(true)?.into()));

            let mut out = self
                .decoder
                .run(feeds)
                .map_err(|e| format!("decoder run (cache): {e}"))?;
            next_tok = last_token(&out, /* positions = */ 1)?;
            dec_kv = take_kv(&mut out, n, "decoder")?;
        }

        let gen_u32: Vec<u32> = gen.iter().map(|&x| x as u32).collect();
        self.tokenizer
            .decode(&gen_u32, true)
            .map_err(|e| format!("decode: {e}"))
    }
}

/// Argmax over the last position of a `[1, positions, vocab]` logits tensor.
fn last_token(out: &ort::session::SessionOutputs<'_>, positions: usize) -> Result<i64, String> {
    let (shape, data) = out["logits"]
        .try_extract_tensor::<f32>()
        .map_err(|e| format!("extract logits: {e}"))?;
    let vocab = shape[2] as usize;
    let start = (positions - 1) * vocab;
    Ok(argmax(&data[start..start + vocab]) as i64)
}

/// Moves the `present.{0..n}.{side}.{key,value}` outputs out of `out`, in
/// layer-major (key, value) order — ready to feed back as `past_key_values`.
fn take_kv(
    out: &mut ort::session::SessionOutputs<'_>,
    n: usize,
    side: &str,
) -> Result<Vec<DynValue>, String> {
    let mut kv = Vec::with_capacity(2 * n);
    for i in 0..n {
        for k in ["key", "value"] {
            let name = present_name(i, side, k);
            let v = out
                .remove(&name)
                .ok_or_else(|| format!("missing output {name}"))?;
            kv.push(v);
        }
    }
    Ok(kv)
}

static ENGINE: OnceLock<Mutex<Option<NllbEngine>>> = OnceLock::new();

fn engine_cell() -> &'static Mutex<Option<NllbEngine>> {
    ENGINE.get_or_init(|| Mutex::new(None))
}

/// Loads (or reuses) the resident engine for `model_dir` without translating.
/// Lets the UI warm the models up once and report a "loaded" state.
pub fn load(model_dir: &str, ort_dylib: &str) -> Result<(), String> {
    init_ort(ort_dylib)?;
    let mut guard = engine_cell().lock().map_err(|e| e.to_string())?;
    ensure_loaded(&mut guard, model_dir)?;
    Ok(())
}

pub fn translate(
    model_dir: &str,
    text: &str,
    src_lang: &str,
    tgt_lang: &str,
    ort_dylib: &str,
) -> Result<String, String> {
    translate_streaming(model_dir, text, src_lang, tgt_lang, ort_dylib, |_| {})
}

/// Like [`translate`], but reports the partial translation after every decoded
/// token via `on_partial` (called on this thread while the engine lock is held).
pub fn translate_streaming(
    model_dir: &str,
    text: &str,
    src_lang: &str,
    tgt_lang: &str,
    ort_dylib: &str,
    on_partial: impl FnMut(&str),
) -> Result<String, String> {
    init_ort(ort_dylib)?;
    let mut guard = engine_cell().lock().map_err(|e| e.to_string())?;
    let engine = ensure_loaded(&mut guard, model_dir)?;
    engine.run(text, src_lang, tgt_lang, on_partial)
}

/// Ensures the held engine matches `model_dir`, loading it on the first call or
/// when the directory changes. Returns a mutable handle to the resident engine.
fn ensure_loaded<'a>(
    guard: &'a mut Option<NllbEngine>,
    model_dir: &str,
) -> Result<&'a mut NllbEngine, String> {
    let stale = guard.as_ref().map(|e| e.model_dir.as_str()) != Some(model_dir);
    if stale {
        *guard = Some(NllbEngine::load(model_dir)?);
    }
    Ok(guard.as_mut().expect("engine just loaded"))
}
