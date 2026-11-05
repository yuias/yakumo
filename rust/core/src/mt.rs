//! Machine translation via llama.cpp (GGUF models). Android-only; host stubs.
//!
//! llama.cpp is statically linked into `libtranslatecore.so`. Its CPU kernels are
//! built with `+dotprod` (GGML_CPU_ARM_ARCH in the per-ABI build calls), so on a core without ASIMDDP
//! the first quantized dot product raises SIGILL and kills the process. Every
//! entry point therefore checks [`cpu_supported`] before touching llama.cpp.
//!
//! The prompt, sampling and UTF-8 helpers are plain Rust so they can be tested on
//! the host; only the `imp` module talks to llama.cpp.
// The host build only exercises the pure helpers from tests.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

/// `AT_HWCAP` from `<elf.h>`.
#[cfg(all(target_os = "android", target_arch = "aarch64"))]
const AT_HWCAP: std::os::raw::c_ulong = 16;
/// `HWCAP_ASIMDDP` from the arm64 uapi `asm/hwcap.h`.
#[cfg_attr(not(all(target_os = "android", target_arch = "aarch64")), allow(dead_code))]
const HWCAP_ASIMDDP: u64 = 1 << 20;

/// Pure decode, so the bit test is unit-testable on the host.
#[cfg_attr(not(all(target_os = "android", target_arch = "aarch64")), allow(dead_code))]
pub(crate) fn hwcap_has_dotprod(hwcap: u64) -> bool {
    hwcap & HWCAP_ASIMDDP != 0
}

/// True when this process may run the llama.cpp CPU backend.
#[cfg(all(target_os = "android", target_arch = "aarch64"))]
pub(crate) fn cpu_supported() -> bool {
    extern "C" {
        // bionic, API 18+
        fn getauxval(t: std::os::raw::c_ulong) -> std::os::raw::c_ulong;
    }
    hwcap_has_dotprod(unsafe { getauxval(AT_HWCAP) } as u64)
}

/// The x86_64 build uses baseline ggml flags, so no capability gate is needed.
#[cfg(all(target_os = "android", not(target_arch = "aarch64")))]
pub(crate) fn cpu_supported() -> bool {
    true
}

#[cfg(not(target_os = "android"))]
pub(crate) fn cpu_supported() -> bool {
    false
}

/// Context window per request. Translation turns are short, so a small window
/// keeps the KV cache allocation cheap.
const N_CTX: u32 = 1024;
/// Hard cap on generated tokens, whatever the source length.
const MAX_NEW_CAP: usize = 512;
/// A prompt must leave at least this many tokens of room for the answer.
const MIN_GEN_ROOM: usize = 16;

/// The only GGUF architecture whose prompt format this module builds.
const PLAMO3_ARCH: &str = "plamo3";

/// PFML3 control tokens. Generation ends when the model opens the next element or
/// a new sequence, which llama.cpp does not mark as end-of-generation.
const TAG: &str = "<|plamo:tag|>";
const BOS: &str = "<|plamo:bos|>";

/// FLORES code -> language code used in PFML3 `lang` attributes. Add a row when
/// the app's offline language list grows.
pub(crate) fn pfml3_lang(flores: &str) -> Option<&'static str> {
    match flores {
        "eng_Latn" => Some("en"),
        "jpn_Jpan" => Some("ja"),
        _ => None,
    }
}

/// PFML3 translation prompt without the leading BOS. The source language is left
/// out, as in the model card's basic example, so a misdetected source still
/// translates.
pub(crate) fn pfml3_prompt(tgt: &str, text: &str) -> Result<String, String> {
    let lang = pfml3_lang(tgt).ok_or_else(|| format!("unsupported target language: {tgt}"))?;
    Ok(format!(
        "{TAG}dataset<|plamo:msg|>translation{TAG}input<|plamo:msg|>{text}{TAG}output<|plamo:key|>lang<|plamo:val|>{lang}<|plamo:msg|>"
    ))
}

/// Generation budget: a translation rarely exceeds ~3x the source token count.
pub(crate) fn max_new_tokens(src_tokens: usize) -> usize {
    (3 * src_tokens + 32).min(MAX_NEW_CAP)
}

/// Collects token pieces, which can split a multi-byte UTF-8 sequence, and exposes
/// only complete characters. Genuinely invalid bytes become U+FFFD so they cannot
/// stall the stream.
#[derive(Default)]
pub(crate) struct Utf8Accumulator {
    text: String,
    pending: Vec<u8>,
}

impl Utf8Accumulator {
    /// Appends `bytes` and returns all text decoded so far (the longest valid prefix).
    pub(crate) fn push(&mut self, bytes: &[u8]) -> &str {
        self.pending.extend_from_slice(bytes);
        loop {
            match std::str::from_utf8(&self.pending) {
                Ok(s) => {
                    self.text.push_str(s);
                    self.pending.clear();
                    break;
                }
                Err(e) => {
                    let valid = e.valid_up_to();
                    self.text
                        .push_str(std::str::from_utf8(&self.pending[..valid]).unwrap_or(""));
                    match e.error_len() {
                        // Truncated sequence at the end: wait for the next piece.
                        None => {
                            self.pending.drain(..valid);
                            break;
                        }
                        Some(n) => {
                            self.text.push('\u{fffd}');
                            self.pending.drain(..valid + n);
                        }
                    }
                }
            }
        }
        &self.text
    }

    /// A sequence still incomplete when generation stops is dropped.
    pub(crate) fn into_string(self) -> String {
        self.text
    }
}

/// Shown to the user whenever the gate refuses to start llama.cpp.
#[cfg(target_os = "android")]
const UNSUPPORTED_MSG: &str =
    "translation needs a CPU with ARMv8.2 dot-product instructions; not available on this device";

/// One-line summary of the ggml CPU features in use, or the reason MT is off.
#[cfg(target_os = "android")]
pub(crate) fn system_info() -> String {
    imp::system_info()
}

#[cfg(not(target_os = "android"))]
pub(crate) fn system_info() -> String {
    "MT is only available on Android".to_owned()
}

/// Loads the GGUF at `model_path` into the resident engine (idempotent per path).
#[cfg(target_os = "android")]
pub(crate) fn load(model_path: &str) -> Result<(), String> {
    imp::load(model_path)
}

/// Translates `text`, calling `on_partial` with the cumulative text as tokens decode.
#[cfg(target_os = "android")]
pub(crate) fn translate_streaming(
    model_path: &str,
    text: &str,
    src: &str,
    tgt: &str,
    on_partial: impl FnMut(&str),
) -> Result<String, String> {
    imp::translate_streaming(model_path, text, src, tgt, on_partial)
}

/// Frees the resident model.
#[cfg(target_os = "android")]
pub(crate) fn unload() {
    imp::unload()
}

#[cfg(not(target_os = "android"))]
const HOST_MSG: &str = "MT is only available on Android";

#[cfg(not(target_os = "android"))]
pub(crate) fn load(_model_path: &str) -> Result<(), String> {
    Err(HOST_MSG.to_owned())
}

#[cfg(not(target_os = "android"))]
pub(crate) fn translate_streaming(
    _model_path: &str,
    _text: &str,
    _src: &str,
    _tgt: &str,
    _on_partial: impl FnMut(&str),
) -> Result<String, String> {
    Err(HOST_MSG.to_owned())
}

#[cfg(not(target_os = "android"))]
pub(crate) fn unload() {}

#[cfg(target_os = "android")]
mod imp {
    use super::{
        cpu_supported, max_new_tokens, pfml3_prompt, Utf8Accumulator, BOS, MIN_GEN_ROOM, N_CTX,
        PLAMO3_ARCH, TAG, UNSUPPORTED_MSG,
    };
    use llama_cpp_2::context::params::LlamaContextParams;
    use llama_cpp_2::llama_backend::LlamaBackend;
    use llama_cpp_2::llama_batch::LlamaBatch;
    use llama_cpp_2::model::params::kv_overrides::ParamOverrideValue;
    use llama_cpp_2::model::params::LlamaModelParams;
    use llama_cpp_2::model::LlamaModel;
    use llama_cpp_2::sampling::LlamaSampler;
    use llama_cpp_2::token::LlamaToken;
    use std::ffi::{c_char, CStr};
    use std::num::NonZeroU32;
    use std::path::Path;
    use std::pin::pin;
    use std::sync::{Mutex, MutexGuard, OnceLock};

    /// Initialized once, after the CPU gate, and never dropped: dropping would call
    /// `llama_backend_free` and make a later `init` fail.
    static BACKEND: OnceLock<LlamaBackend> = OnceLock::new();
    // `LlamaBackend::init` fails on a second call, so racing first callers must be
    // serialized instead of relying on `OnceLock::get_or_init`.
    static INIT_LOCK: Mutex<()> = Mutex::new(());

    /// The weights stay resident; contexts are per request because `LlamaContext`
    /// borrows the model and cannot live next to it in a global.
    struct MtEngine {
        model_path: String,
        model: LlamaModel,
        bos: LlamaToken,
        /// Tokens that end a translation besides the vocabulary's own EOG set.
        stop: [LlamaToken; 2],
    }

    static ENGINE: OnceLock<Mutex<Option<MtEngine>>> = OnceLock::new();

    fn lock_engine() -> MutexGuard<'static, Option<MtEngine>> {
        ENGINE
            .get_or_init(|| Mutex::new(None))
            .lock()
            .unwrap_or_else(|e| e.into_inner())
    }

    pub(super) fn backend() -> Result<&'static LlamaBackend, String> {
        if !cpu_supported() {
            return Err(UNSUPPORTED_MSG.to_owned());
        }
        if let Some(b) = BACKEND.get() {
            return Ok(b);
        }
        let _guard = INIT_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(b) = BACKEND.get() {
            return Ok(b);
        }
        let b = LlamaBackend::init().map_err(|e| format!("llama backend init failed: {e}"))?;
        Ok(BACKEND.get_or_init(|| b))
    }

    pub(super) fn system_info() -> String {
        if let Err(e) = backend() {
            return e;
        }
        let ptr = unsafe { llama_cpp_sys_2::llama_print_system_info() };
        if ptr.is_null() {
            return "llama.cpp: no system info".to_owned();
        }
        let raw = unsafe { CStr::from_ptr(ptr) }.to_string_lossy();
        // The C string is multi-line and space-padded; flatten it for a single UI line.
        let flat = raw.split_whitespace().collect::<Vec<_>>().join(" ");
        format!("llama.cpp: {flat}")
    }

    /// The single token `text` names. A plamo3 GGUF whose vocabulary lacks the
    /// PFML3 control tokens would otherwise get the markup as plain text.
    fn control_token(model: &LlamaModel, text: &str) -> Result<LlamaToken, String> {
        match model.vocab().tokenize(text.as_bytes(), false, true).as_slice() {
            [t] => Ok(*t),
            _ => Err(format!("model vocabulary has no {text} token")),
        }
    }

    /// Makes `guard` hold the model at `path`. A different resident model is freed
    /// first so two sets of weights never coexist in RAM.
    fn ensure_loaded(guard: &mut Option<MtEngine>, path: &str) -> Result<(), String> {
        if guard.as_ref().is_some_and(|e| e.model_path == path) {
            return Ok(());
        }
        *guard = None;
        let backend = backend()?;
        // load_from_file only debug_asserts on a missing file; fail cleanly instead.
        if !Path::new(path).is_file() {
            return Err(format!("model file not found: {path}"));
        }
        // The bundled llama.cpp predates the "plamo3" tokenizer (ggml-org/llama.cpp#30045)
        // and refuses to load it. "plamo2" is the same Unigram tokenizer without
        // plamo3's pre-segmentation, which only changes runs of 4+ identical
        // characters or 2+ spaces, rare in speech transcripts. Drop this override
        // once llama-cpp-2 ships a llama.cpp with the plamo3 tokenizer.
        let mut params = pin!(LlamaModelParams::default());
        let mut tokenizer = [0 as c_char; 128];
        for (dst, src) in tokenizer.iter_mut().zip(b"plamo2") {
            *dst = *src as c_char;
        }
        params
            .as_mut()
            .append_kv_override(c"tokenizer.ggml.model", ParamOverrideValue::Str(tokenizer));
        let model = LlamaModel::load_from_file(backend, path, &params)
            .map_err(|e| format!("failed to load model: {e}"))?;
        let arch = model
            .meta_val_str("general.architecture")
            .map_err(|e| format!("model has no architecture metadata: {e}"))?;
        if arch != PLAMO3_ARCH {
            return Err(format!("unsupported translation model architecture: {arch}"));
        }
        let bos = control_token(&model, BOS)?;
        let tag = control_token(&model, TAG)?;
        *guard = Some(MtEngine {
            model_path: path.to_owned(),
            model,
            bos,
            stop: [tag, bos],
        });
        Ok(())
    }

    pub(super) fn load(path: &str) -> Result<(), String> {
        backend()?;
        let mut guard = lock_engine();
        ensure_loaded(&mut guard, path)
    }

    pub(super) fn unload() {
        *lock_engine() = None;
    }

    pub(super) fn translate_streaming(
        path: &str,
        text: &str,
        _src: &str,
        tgt: &str,
        mut on_partial: impl FnMut(&str),
    ) -> Result<String, String> {
        let backend = backend()?;
        let mut guard = lock_engine();
        ensure_loaded(&mut guard, path)?;
        let MtEngine { model, bos, stop, .. } =
            guard.as_mut().ok_or("translation model is not loaded")?;

        let prompt = pfml3_prompt(tgt, text)?;
        let vocab = model.vocab();
        // llama.cpp's plamo2 tokenizer path never inserts BOS, even with
        // add_special, so it is prepended here.
        let mut prompt_tokens = vec![*bos];
        prompt_tokens.extend(vocab.tokenize(prompt.as_bytes(), false, true));
        let src_tokens = vocab.tokenize(text.as_bytes(), false, false).len();
        let n_ctx = N_CTX as usize;
        if prompt_tokens.len() > n_ctx - MIN_GEN_ROOM {
            return Err("input too long for translation".to_owned());
        }
        let budget = max_new_tokens(src_tokens).min(n_ctx - prompt_tokens.len());

        let threads = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4)
            .min(4) as i32;
        let ctx_params = LlamaContextParams::default()
            .with_n_ctx(NonZeroU32::new(N_CTX))
            .with_n_batch(N_CTX)
            .with_n_threads(threads)
            .with_n_threads_batch(threads);
        let mut ctx = model
            .new_context(backend, ctx_params)
            .map_err(|e| format!("failed to create context: {e}"))?;

        // The model card decodes with temperature 0.
        let mut sampler = LlamaSampler::greedy();
        let mut batch = LlamaBatch::new(prompt_tokens.len(), 1);
        batch
            .add_sequence(&prompt_tokens, 0, false)
            .map_err(|e| format!("batch error: {e}"))?;
        ctx.decode(&mut batch)
            .map_err(|e| format!("decode failed: {e}"))?;

        let mut acc = Utf8Accumulator::default();
        let mut emitted = 0usize;
        let mut pos = prompt_tokens.len() as i32;
        for step in 0..budget {
            let tok = sampler.sample(&ctx, batch.n_tokens() - 1);
            if vocab.is_eog(tok) || stop.contains(&tok) {
                break;
            }
            let cur = acc.push(&vocab.token_to_piece(tok, false, None));
            if cur.len() > emitted {
                emitted = cur.len();
                let partial = cur.trim_start();
                if !partial.is_empty() {
                    on_partial(partial);
                }
            }
            if step + 1 == budget {
                break;
            }
            batch.clear();
            batch
                .add(tok, pos, &[0], true)
                .map_err(|e| format!("batch error: {e}"))?;
            pos += 1;
            ctx.decode(&mut batch)
                .map_err(|e| format!("decode failed: {e}"))?;
        }
        Ok(acc.into_string().trim().to_owned())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hwcap_dotprod_bit() {
        assert!(hwcap_has_dotprod(1 << 20));
        assert!(hwcap_has_dotprod(u64::MAX));
        assert!(!hwcap_has_dotprod((1 << 20) - 1));
        assert!(!hwcap_has_dotprod(0));
    }

    #[test]
    fn pfml3_langs() {
        assert_eq!(pfml3_lang("eng_Latn"), Some("en"));
        assert_eq!(pfml3_lang("jpn_Jpan"), Some("ja"));
        assert_eq!(pfml3_lang("kor_Hang"), None);
    }

    #[test]
    fn pfml3_prompt_matches_model_card() {
        // The model card's basic Japanese-to-English example, BOS omitted.
        assert_eq!(
            pfml3_prompt("eng_Latn", "会議は来週の月曜日に変更されました。").unwrap(),
            "<|plamo:tag|>dataset<|plamo:msg|>translation\
             <|plamo:tag|>input\
             <|plamo:msg|>会議は来週の月曜日に変更されました。\
             <|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>en<|plamo:msg|>"
        );
        assert!(pfml3_prompt("kor_Hang", "x").is_err());
    }

    #[test]
    fn max_new_tokens_bounds() {
        assert_eq!(max_new_tokens(0), 32);
        assert_eq!(max_new_tokens(10), 62);
        assert_eq!(max_new_tokens(10_000), 512);
    }

    #[test]
    fn utf8_accumulator_holds_split_multibyte() {
        let mut acc = Utf8Accumulator::default();
        let mut lens = Vec::new();
        for b in "日本".as_bytes() {
            let s = acc.push(&[*b]);
            assert!(!s.contains('\u{fffd}'));
            lens.push(s.len());
        }
        // Each CJK char is 3 bytes: the prefix grows only on a char boundary.
        assert_eq!(lens, [0, 0, 3, 3, 3, 6]);
        assert_eq!(acc.into_string(), "日本");
    }

    #[test]
    fn utf8_accumulator_replaces_invalid_bytes_and_continues() {
        let mut acc = Utf8Accumulator::default();
        assert_eq!(acc.push(&[0xFF]), "\u{fffd}");
        assert_eq!(acc.push(b"a"), "\u{fffd}a");
    }
}
