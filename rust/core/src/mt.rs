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
/// Fixed seed so the same input gives the same output.
const SEED: u32 = 0x5EED;
/// A prompt must leave at least this many tokens of room for the answer.
const MIN_GEN_ROOM: usize = 16;

const ENG: &str = "eng_Latn";
const JPN: &str = "jpn_Jpan";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Recipe {
    Lfm2EnJa,
    HyMt2,
}

impl Recipe {
    /// llama.cpp built-in template name. The embedded template is not auto-detected:
    /// the detector classifies Hy-MT2's template as `hunyuan-vl` and silently
    /// renders a malformed prompt.
    fn template_name(self) -> &'static str {
        match self {
            Recipe::Lfm2EnJa => "chatml",
            Recipe::HyMt2 => "hunyuan-dense",
        }
    }

    /// Text the embedded template must contain. Guards against a GGUF whose
    /// architecture matches but whose chat format differs from the built-in one.
    fn template_marker(self) -> &'static str {
        match self {
            Recipe::Lfm2EnJa => "<|im_start|>",
            Recipe::HyMt2 => "<\u{ff5c}hy_User\u{ff5c}>",
        }
    }

    fn sampling(self) -> &'static Sampling {
        match self {
            Recipe::Lfm2EnJa => &LFM2_SAMPLING,
            Recipe::HyMt2 => &HYMT2_SAMPLING,
        }
    }
}

/// Picks the prompt recipe from the GGUF `general.architecture`, so swapping in
/// another quantization of the same model is a manifest-only change.
pub(crate) fn recipe_for_arch(arch: &str) -> Result<Recipe, String> {
    match arch {
        "lfm2" => Ok(Recipe::Lfm2EnJa),
        "hunyuan-dense" => Ok(Recipe::HyMt2),
        other => Err(format!("unsupported translation model architecture: {other}")),
    }
}

/// FLORES code -> English language name used in prompts. Add a row when the
/// app's language list grows.
pub(crate) fn language_name(flores: &str) -> Option<&'static str> {
    match flores {
        ENG => Some("English"),
        JPN => Some("Japanese"),
        _ => None,
    }
}

/// (role, content) chat messages for one request.
pub(crate) fn chat_messages(
    r: &Recipe,
    src: &str,
    tgt: &str,
    text: &str,
) -> Result<Vec<(&'static str, String)>, String> {
    match r {
        Recipe::Lfm2EnJa => {
            let system = match (src, tgt) {
                (ENG, JPN) => "Translate to Japanese.",
                (JPN, ENG) => "Translate to English.",
                _ => return Err("LFM2-ENJP translates English\u{2194}Japanese only".to_owned()),
            };
            Ok(vec![("system", system.to_owned()), ("user", text.to_owned())])
        }
        Recipe::HyMt2 => {
            let name =
                language_name(tgt).ok_or_else(|| format!("unsupported target language: {tgt}"))?;
            Ok(vec![(
                "user",
                format!(
                    "Translate the following text into {name}. Note that you should only output the translated result without any additional explanation:\n\n{text}"
                ),
            )])
        }
    }
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

/// Model-card sampler settings. `None` skips that stage of the chain.
pub(crate) struct Sampling {
    pub(crate) temp: f32,
    pub(crate) top_k: Option<i32>,
    pub(crate) top_p: Option<f32>,
    pub(crate) min_p: Option<f32>,
    pub(crate) repeat_penalty: f32,
    pub(crate) penalty_last_n: i32,
    pub(crate) seed: u32,
}

pub(crate) const LFM2_SAMPLING: Sampling = Sampling {
    temp: 0.5,
    top_k: None,
    top_p: None,
    min_p: Some(0.1),
    repeat_penalty: 1.05,
    penalty_last_n: 64,
    seed: SEED,
};

// top_p 0.6 is the README value for the 1.8B model (the GGUF metadata says 0.8).
pub(crate) const HYMT2_SAMPLING: Sampling = Sampling {
    temp: 0.7,
    top_k: Some(20),
    top_p: Some(0.6),
    min_p: None,
    repeat_penalty: 1.05,
    penalty_last_n: 64,
    seed: SEED,
};

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
        chat_messages, cpu_supported, max_new_tokens, recipe_for_arch, Recipe, Sampling,
        Utf8Accumulator, MIN_GEN_ROOM, N_CTX, UNSUPPORTED_MSG,
    };
    use llama_cpp_2::context::params::LlamaContextParams;
    use llama_cpp_2::llama_backend::LlamaBackend;
    use llama_cpp_2::llama_batch::LlamaBatch;
    use llama_cpp_2::model::params::LlamaModelParams;
    use llama_cpp_2::model::{LlamaChatMessage, LlamaChatTemplate, LlamaModel};
    use llama_cpp_2::sampling::LlamaSampler;
    use std::ffi::CStr;
    use std::num::NonZeroU32;
    use std::path::Path;
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
        recipe: Recipe,
        model: LlamaModel,
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
        let model = LlamaModel::load_from_file(backend, path, &LlamaModelParams::default())
            .map_err(|e| format!("failed to load model: {e}"))?;
        let arch = model
            .meta_val_str("general.architecture")
            .map_err(|e| format!("model has no architecture metadata: {e}"))?;
        let recipe = recipe_for_arch(&arch)?;
        let embedded = model
            .chat_template(None)
            .ok()
            .and_then(|t| t.to_string().ok())
            .unwrap_or_default();
        if !embedded.contains(recipe.template_marker()) {
            return Err("unexpected chat template".to_owned());
        }
        *guard = Some(MtEngine {
            model_path: path.to_owned(),
            recipe,
            model,
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

    fn build_sampler(s: &Sampling, n_vocab: i32) -> LlamaSampler {
        // The penalty sampler only sees tokens accepted through `sample()`, i.e.
        // generated ones, so names and numbers copied from the source are not penalised.
        let mut chain = vec![LlamaSampler::penalties(
            n_vocab,
            s.penalty_last_n,
            s.repeat_penalty,
            0.0,
            0.0,
        )];
        if let Some(k) = s.top_k {
            chain.push(LlamaSampler::top_k(k));
        }
        if let Some(p) = s.top_p {
            chain.push(LlamaSampler::top_p(p, 1));
        }
        if let Some(p) = s.min_p {
            chain.push(LlamaSampler::min_p(p, 1));
        }
        chain.push(LlamaSampler::temp(s.temp));
        chain.push(LlamaSampler::dist(s.seed));
        LlamaSampler::chain_simple(chain)
    }

    pub(super) fn translate_streaming(
        path: &str,
        text: &str,
        src: &str,
        tgt: &str,
        mut on_partial: impl FnMut(&str),
    ) -> Result<String, String> {
        let backend = backend()?;
        let mut guard = lock_engine();
        ensure_loaded(&mut guard, path)?;
        let MtEngine { recipe, model, .. } =
            guard.as_mut().ok_or("translation model is not loaded")?;

        let msgs = chat_messages(recipe, src, tgt, text)?
            .into_iter()
            .map(|(role, content)| LlamaChatMessage::new(role.to_owned(), content))
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| format!("invalid chat message: {e}"))?;
        let tmpl = LlamaChatTemplate::new(recipe.template_name())
            .map_err(|e| format!("invalid chat template name: {e}"))?;
        let mut prompt = model
            .apply_chat_template(&tmpl, &msgs, true)
            .map_err(|e| format!("failed to render prompt: {e}"))?;

        let vocab = model.vocab();
        // Both Jinja templates emit BOS and neither built-in template does, and
        // should_add_bos() is false for Hy-MT2, so prepend it by text for both.
        let bos = vocab.bos();
        if bos.0 >= 0 {
            if let Some(bos_text) = vocab.text(bos).and_then(|c| c.to_str().ok()) {
                if !bos_text.is_empty() && !prompt.starts_with(bos_text) {
                    prompt.insert_str(0, bos_text);
                }
            }
        }

        let prompt_tokens = vocab.tokenize(prompt.as_bytes(), false, true);
        let src_tokens = vocab.tokenize(text.as_bytes(), false, false).len();
        let n_ctx = N_CTX as usize;
        if prompt_tokens.is_empty() || prompt_tokens.len() > n_ctx - MIN_GEN_ROOM {
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

        let mut sampler = build_sampler(recipe.sampling(), model.n_vocab());
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
            // sample() already feeds the token to the sampler chain; accepting it
            // again would double-count it in the repetition penalty.
            let tok = sampler.sample(&ctx, batch.n_tokens() - 1);
            if vocab.is_eog(tok) {
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
    fn recipe_for_arch_maps_known_archs() {
        assert_eq!(recipe_for_arch("lfm2"), Ok(Recipe::Lfm2EnJa));
        assert_eq!(recipe_for_arch("hunyuan-dense"), Ok(Recipe::HyMt2));
        let err = recipe_for_arch("llama").unwrap_err();
        assert!(err.contains("unsupported translation model architecture: llama"));
    }

    #[test]
    fn language_names() {
        assert_eq!(language_name("eng_Latn"), Some("English"));
        assert_eq!(language_name("jpn_Jpan"), Some("Japanese"));
        assert_eq!(language_name("kor_Hang"), None);
    }

    #[test]
    fn lfm2_messages() {
        let to_ja = chat_messages(&Recipe::Lfm2EnJa, "eng_Latn", "jpn_Jpan", "Hello").unwrap();
        assert_eq!(
            to_ja,
            vec![
                ("system", "Translate to Japanese.".to_owned()),
                ("user", "Hello".to_owned())
            ]
        );
        let to_en = chat_messages(&Recipe::Lfm2EnJa, "jpn_Jpan", "eng_Latn", "こんにちは").unwrap();
        assert_eq!(to_en[0], ("system", "Translate to English.".to_owned()));
        assert_eq!(to_en[1], ("user", "こんにちは".to_owned()));
        assert!(chat_messages(&Recipe::Lfm2EnJa, "kor_Hang", "eng_Latn", "x").is_err());
    }

    #[test]
    fn hymt2_messages() {
        let msgs = chat_messages(&Recipe::HyMt2, "jpn_Jpan", "eng_Latn", "こんにちは").unwrap();
        assert_eq!(
            msgs,
            vec![(
                "user",
                "Translate the following text into English. Note that you should only output the translated result without any additional explanation:\n\nこんにちは"
                    .to_owned()
            )]
        );
        assert!(chat_messages(&Recipe::HyMt2, "eng_Latn", "kor_Hang", "x").is_err());
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
