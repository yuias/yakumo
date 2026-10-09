//! translatecore — native pipeline core exposed to the Android app via UniFFI.
//!
//! PoC stage: only proves the Rust -> Kotlin bridge end to end. The real
//! TranslationEngine abstraction (NLLB / LLM / Realtime) lands in later phases.

uniffi::setup_scaffolding!();

mod asr;
mod mt;
mod translate;
mod vad;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TranslateError {
    #[error("{0}")]
    Failed(String),
}

/// Step A smoke: load onnxruntime via `ort` and open an ONNX model on device.
#[uniffi::export]
pub fn translate_smoke(ort_dylib: String, model_path: String) -> Result<String, TranslateError> {
    translate::smoke(&ort_dylib, &model_path).map_err(TranslateError::Failed)
}

/// Loads the NLLB models under `model_dir` into the resident engine without
/// translating, so the UI can warm them up once and surface a "loaded" state.
/// Idempotent: a no-op when the same `model_dir` is already resident.
#[uniffi::export]
pub fn translate_load(model_dir: String, ort_dylib: String) -> Result<(), TranslateError> {
    translate::load(&model_dir, &ort_dylib).map_err(TranslateError::Failed)
}

/// Translates `text` from `src_lang` to `tgt_lang` (NLLB FLORES codes, e.g.
/// "eng_Latn", "jpn_Jpan") using the NLLB ONNX models under `model_dir`. Loads
/// the models on first use, then reuses the resident engine.
#[uniffi::export]
pub fn translate_text(
    model_dir: String,
    text: String,
    src_lang: String,
    tgt_lang: String,
    ort_dylib: String,
) -> Result<String, TranslateError> {
    translate::translate(&model_dir, &text, &src_lang, &tgt_lang, &ort_dylib)
        .map_err(TranslateError::Failed)
}

/// Receives the partial translation as it is generated, one call per decoded
/// token with the text decoded so far. Implemented on the foreign (Kotlin) side.
#[uniffi::export(callback_interface)]
pub trait TranslationSink: Send {
    fn on_partial(&self, text: String);
}

/// Streaming variant of [`translate_text`]: returns the final translation and, as
/// it decodes, pushes each growing partial to `sink` so the UI can render the
/// translation left-to-right. Callbacks arrive on the calling thread.
#[uniffi::export]
pub fn translate_text_streaming(
    model_dir: String,
    text: String,
    src_lang: String,
    tgt_lang: String,
    ort_dylib: String,
    sink: Box<dyn TranslationSink>,
) -> Result<String, TranslateError> {
    translate::translate_streaming(&model_dir, &text, &src_lang, &tgt_lang, &ort_dylib, |s| {
        sink.on_partial(s.to_owned())
    })
    .map_err(TranslateError::Failed)
}

/// True when this device can run the llama.cpp translator (arm64 needs dot-product
/// instructions). Cheap and safe to call before any model exists.
#[uniffi::export]
pub fn mt_supported() -> bool {
    mt::cpu_supported()
}

/// llama.cpp's CPU feature summary when supported, otherwise a one-line reason.
#[uniffi::export]
pub fn mt_system_info() -> String {
    mt::system_info()
}

/// Loads the GGUF at `model_path` (a file, not a directory) into the resident
/// translation engine. Idempotent for the same path; a different path frees the
/// old model before loading the new one.
#[uniffi::export]
pub fn mt_load(model_path: String) -> Result<(), TranslateError> {
    mt::load(&model_path).map_err(TranslateError::Failed)
}

/// Translates `text` between FLORES-coded languages with the GGUF at `model_path`
/// (loaded lazily like [`mt_load`]). Returns the final translation and pushes the
/// cumulative text so far to `sink` as tokens decode, on the calling thread.
#[uniffi::export]
pub fn mt_translate_streaming(
    model_path: String,
    text: String,
    src_lang: String,
    tgt_lang: String,
    sink: Box<dyn TranslationSink>,
) -> Result<String, TranslateError> {
    mt::translate_streaming(&model_path, &text, &src_lang, &tgt_lang, |s| {
        sink.on_partial(s.to_owned())
    })
    .map_err(TranslateError::Failed)
}

/// Frees the resident translation model. No-op when none is loaded.
#[uniffi::export]
pub fn mt_unload() {
    mt::unload()
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum AsrError {
    #[error("{0}")]
    Failed(String),
}

/// Transcript plus SenseVoice's detected language tag (`<|en|>`, `<|ja|>`, ...;
/// empty if the model didn't report one). The tag drives translation direction.
#[derive(uniffi::Record)]
pub struct AsrResult {
    pub text: String,
    pub lang: String,
}

/// Loads the SenseVoice recognizer under `model_dir` into the resident engine
/// without recognizing anything, to warm it up. Idempotent per `model_dir`.
#[uniffi::export]
pub fn asr_load(model_dir: String) -> Result<(), AsrError> {
    asr::load(&model_dir).map_err(AsrError::Failed)
}

/// Frees the resident SenseVoice recognizer. No-op when none is loaded; the next
/// `asr_load` / `asr_recognize` reloads it.
#[uniffi::export]
pub fn asr_unload() {
    asr::unload()
}

/// Transcribes 16 kHz mono PCM (signed 16-bit little-endian) with the SenseVoice
/// model under `model_dir`. Language is auto-detected. Returns the transcript.
/// Bytes are used over the FFI to avoid boxing tens of thousands of floats.
/// Loads the recognizer on first use, then reuses the resident engine.
#[uniffi::export]
pub fn asr_recognize(
    model_dir: String,
    pcm16le: Vec<u8>,
    sample_rate: i32,
) -> Result<AsrResult, AsrError> {
    let samples: Vec<f32> = pcm16le
        .chunks_exact(2)
        .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0)
        .collect();
    let (text, lang) = asr::recognize(&model_dir, &samples, sample_rate).map_err(AsrError::Failed)?;
    Ok(AsrResult { text, lang })
}

/// Partial transcript from the streaming recognizer plus whether sherpa detected
/// an end-of-utterance on this chunk. On `endpoint`, `text` is the finalized
/// transcript for the segment that just ended and the stream has been reset.
#[derive(uniffi::Record)]
pub struct AsrStreamResult {
    pub text: String,
    pub endpoint: bool,
}

/// Loads the streaming (Nemotron 3.5) recognizer under `model_dir` and creates its
/// resident stream. `rule1`/`rule2`/`rule3` are the endpoint rules in seconds
/// (trailing silence before / after decoded speech, and max utterance length);
/// the recognizer is recreated when they change. `language` is the stream's
/// decoding language (`"auto"` or empty for automatic detection); changing it
/// only updates the live stream. Idempotent for equal arguments.
#[uniffi::export]
pub fn asr_stream_load(
    model_dir: String,
    rule1: f32,
    rule2: f32,
    rule3: f32,
    language: String,
) -> Result<(), AsrError> {
    asr::stream_load(&model_dir, rule1, rule2, rule3, &language).map_err(AsrError::Failed)
}

/// Frees the resident streaming recognizer and its stream. No-op when none is
/// loaded; the next `asr_stream_load` / `asr_stream_accept` reloads it.
#[uniffi::export]
pub fn asr_stream_unload() {
    asr::stream_unload()
}

/// Feeds one chunk of 16 kHz mono PCM (signed 16-bit little-endian) into the
/// resident streaming recognizer and returns the current partial transcript and
/// whether an utterance just ended. Loads the recognizer on first use.
#[uniffi::export]
pub fn asr_stream_accept(
    model_dir: String,
    pcm16le: Vec<u8>,
    sample_rate: i32,
) -> Result<AsrStreamResult, AsrError> {
    let samples: Vec<f32> = pcm16le
        .chunks_exact(2)
        .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0)
        .collect();
    let (text, endpoint) =
        asr::stream_accept(&model_dir, &samples, sample_rate).map_err(AsrError::Failed)?;
    Ok(AsrStreamResult { text, endpoint })
}

/// Discards any in-progress utterance in the resident stream (e.g. when the user
/// stops recording).
#[uniffi::export]
pub fn asr_stream_reset(model_dir: String) -> Result<(), AsrError> {
    asr::stream_reset(&model_dir).map_err(AsrError::Failed)
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum VadError {
    #[error("{0}")]
    Failed(String),
}

/// Packs mono f32 samples back into 16 kHz PCM16 little-endian bytes, the format
/// the SenseVoice recognizer consumes — so a VAD segment flows straight in.
fn samples_to_pcm16le(samples: Vec<f32>) -> Vec<u8> {
    let mut out = Vec::with_capacity(samples.len() * 2);
    for s in samples {
        let v = (s * 32768.0).clamp(-32768.0, 32767.0) as i16;
        out.extend_from_slice(&v.to_le_bytes());
    }
    out
}

/// Loads/configures the Silero VAD under `model_dir`. The tuning params (speech
/// probability `threshold`, and minimum silence / minimum speech / maximum speech
/// durations in seconds) are baked in at creation, so changing them recreates the
/// detector. Idempotent for equal arguments.
#[uniffi::export]
pub fn vad_load(
    model_dir: String,
    threshold: f32,
    min_silence_s: f32,
    min_speech_s: f32,
    max_speech_s: f32,
) -> Result<(), VadError> {
    vad::load(&model_dir, threshold, min_silence_s, min_speech_s, max_speech_s)
        .map_err(VadError::Failed)
}

/// Frees the resident Silero detector. No-op when none is loaded.
#[uniffi::export]
pub fn vad_unload() {
    vad::unload()
}

/// Feeds one chunk of 16 kHz mono PCM16 LE into the resident detector and returns
/// the speech segments that finished on this chunk (each as PCM16 LE, ready for
/// `asr_recognize`). Usually empty until a pause ends an utterance.
#[uniffi::export]
pub fn vad_accept(
    model_dir: String,
    pcm16le: Vec<u8>,
    sample_rate: i32,
) -> Result<Vec<Vec<u8>>, VadError> {
    let samples: Vec<f32> = pcm16le
        .chunks_exact(2)
        .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0)
        .collect();
    let segs = vad::accept(&model_dir, &samples, sample_rate).map_err(VadError::Failed)?;
    Ok(segs.into_iter().map(samples_to_pcm16le).collect())
}

/// Forces the in-progress utterance to be emitted (e.g. when recording stops
/// mid-sentence). Returns the flushed segment(s) as PCM16 LE.
#[uniffi::export]
pub fn vad_flush(model_dir: String) -> Result<Vec<Vec<u8>>, VadError> {
    let segs = vad::flush(&model_dir).map_err(VadError::Failed)?;
    Ok(segs.into_iter().map(samples_to_pcm16le).collect())
}

/// Discards any buffered audio / in-progress utterance in the resident detector.
#[uniffi::export]
pub fn vad_reset(model_dir: String) -> Result<(), VadError> {
    vad::reset(&model_dir).map_err(VadError::Failed)
}

/// Version banner for the native core. Used by the PoC to confirm the app is
/// actually executing Rust rather than a Kotlin stub.
#[uniffi::export]
pub fn core_version() -> String {
    format!("translatecore v{}", env!("CARGO_PKG_VERSION"))
}

/// Echoes a greeting from the native side, proving arguments cross the FFI boundary.
#[uniffi::export]
pub fn greeting(name: String) -> String {
    format!("{name} — wired through Rust core")
}

// sherpa-onnx C API binding. On Android we link the prebuilt libsherpa-onnx-c-api.so
// (see build.rs); on the host we stub it so tests/binding-generation still link.
#[cfg(target_os = "android")]
mod sherpa {
    use std::ffi::CStr;
    use std::os::raw::c_char;

    extern "C" {
        fn SherpaOnnxGetVersionStr() -> *const c_char;
    }

    pub fn version() -> String {
        let ptr = unsafe { SherpaOnnxGetVersionStr() };
        if ptr.is_null() {
            return "unknown".to_owned();
        }
        unsafe { CStr::from_ptr(ptr) }.to_string_lossy().into_owned()
    }
}

#[cfg(not(target_os = "android"))]
mod sherpa {
    pub fn version() -> String {
        "host-stub".to_owned()
    }
}

/// Reports the linked sherpa-onnx version. Calling this proves the heavy native
/// stack (sherpa-onnx + onnxruntime) actually loads and executes on the device,
/// without needing any model yet.
#[uniffi::export]
pub fn sherpa_version() -> String {
    format!("sherpa-onnx {}", sherpa::version())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn greeting_includes_input() {
        assert!(greeting("Android".into()).starts_with("Android"));
    }

    #[test]
    fn version_is_reported() {
        assert!(core_version().contains("translatecore"));
    }
}
