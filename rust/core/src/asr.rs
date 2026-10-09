//! Offline ASR via the sherpa-onnx C API (SenseVoice). Android-only; host stub.
//!
//! The recognizer is expensive to build but reusable, so it is kept resident in
//! a process-global keyed by `model_dir`; only the lightweight per-utterance
//! stream is created on each call. `unload` frees it when the app switches away
//! from this engine.

#[cfg(target_os = "android")]
pub fn load(model_dir: &str) -> Result<(), String> {
    imp::load(model_dir)
}

/// Returns `(transcript, detected_lang_tag)`; the tag is SenseVoice's raw
/// `<|xx|>` language marker (empty if unavailable).
#[cfg(target_os = "android")]
pub fn recognize(
    model_dir: &str,
    samples: &[f32],
    sample_rate: i32,
) -> Result<(String, String), String> {
    imp::recognize(model_dir, samples, sample_rate)
}

/// Drops the resident recognizer (no-op if none). Waits for a recognition in flight.
#[cfg(target_os = "android")]
pub fn unload() {
    imp::unload()
}

#[cfg(not(target_os = "android"))]
pub fn unload() {}

#[cfg(not(target_os = "android"))]
pub fn load(_model_dir: &str) -> Result<(), String> {
    Err("ASR is only available on Android (native sherpa-onnx not linked on host)".to_owned())
}

#[cfg(not(target_os = "android"))]
pub fn recognize(
    _model_dir: &str,
    _samples: &[f32],
    _sample_rate: i32,
) -> Result<(String, String), String> {
    Err("ASR is only available on Android (native sherpa-onnx not linked on host)".to_owned())
}

// --- Streaming (online) ASR: Nemotron 3.5 (multilingual) via sherpa-onnx's
// OnlineRecognizer. A separate engine from the offline SenseVoice path above;
// the recognizer and its single stream are kept resident across chunks. ---

/// Loads/configures the streaming recognizer. The three endpoint rules (trailing
/// silence in seconds before / after decoded speech, and max utterance length)
/// are baked into the recognizer at creation, so changing them recreates it.
/// `language` is a stream option (not recognizer config), so changing it only
/// updates the live stream.
#[cfg(target_os = "android")]
pub fn stream_load(
    model_dir: &str,
    rule1: f32,
    rule2: f32,
    rule3: f32,
    language: &str,
) -> Result<(), String> {
    stream_imp::load(model_dir, rule1, rule2, rule3, language)
}

/// Drops the resident recognizer and stream (no-op if none).
#[cfg(target_os = "android")]
pub fn stream_unload() {
    stream_imp::unload()
}

#[cfg(not(target_os = "android"))]
pub fn stream_unload() {}

/// Feeds one chunk of mono f32 PCM into the resident stream and decodes whatever
/// is ready. Returns `(partial_text, is_endpoint)`; on an endpoint the stream is
/// reset so the returned text is the final transcript for that utterance.
#[cfg(target_os = "android")]
pub fn stream_accept(
    model_dir: &str,
    samples: &[f32],
    sample_rate: i32,
) -> Result<(String, bool), String> {
    stream_imp::accept(model_dir, samples, sample_rate)
}

/// Clears the resident stream's state, discarding any partial utterance.
#[cfg(target_os = "android")]
pub fn stream_reset(model_dir: &str) -> Result<(), String> {
    stream_imp::reset(model_dir)
}

#[cfg(not(target_os = "android"))]
pub fn stream_load(
    _model_dir: &str,
    _rule1: f32,
    _rule2: f32,
    _rule3: f32,
    _language: &str,
) -> Result<(), String> {
    Err("streaming ASR is only available on Android".to_owned())
}

#[cfg(not(target_os = "android"))]
pub fn stream_accept(
    _model_dir: &str,
    _samples: &[f32],
    _sample_rate: i32,
) -> Result<(String, bool), String> {
    Err("streaming ASR is only available on Android".to_owned())
}

#[cfg(not(target_os = "android"))]
pub fn stream_reset(_model_dir: &str) -> Result<(), String> {
    Err("streaming ASR is only available on Android".to_owned())
}

#[cfg(target_os = "android")]
#[allow(dead_code)] // many config fields are part of the ABI but unused for SenseVoice
mod imp {
    use std::ffi::{CStr, CString};
    use std::os::raw::{c_char, c_void};
    use std::path::Path;
    use std::sync::{Mutex, OnceLock};

    // --- C struct mirrors (sherpa-onnx c-api.h v1.13.8). Order/types must match exactly. ---

    #[repr(C)]
    struct TransducerModelConfig {
        encoder: *const c_char,
        decoder: *const c_char,
        joiner: *const c_char,
    }
    #[repr(C)]
    struct ParaformerModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct NemoEncDecCtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct WhisperModelConfig {
        encoder: *const c_char,
        decoder: *const c_char,
        language: *const c_char,
        task: *const c_char,
        tail_paddings: i32,
        enable_token_timestamps: i32,
        enable_segment_timestamps: i32,
    }
    #[repr(C)]
    struct TdnnModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct SenseVoiceModelConfig {
        model: *const c_char,
        language: *const c_char,
        use_itn: i32,
    }
    #[repr(C)]
    struct MoonshineModelConfig {
        preprocessor: *const c_char,
        encoder: *const c_char,
        uncached_decoder: *const c_char,
        cached_decoder: *const c_char,
        merged_decoder: *const c_char,
    }
    #[repr(C)]
    struct FireRedAsrModelConfig {
        encoder: *const c_char,
        decoder: *const c_char,
    }
    #[repr(C)]
    struct DolphinModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct ZipformerCtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct CanaryModelConfig {
        encoder: *const c_char,
        decoder: *const c_char,
        src_lang: *const c_char,
        tgt_lang: *const c_char,
        use_pnc: i32,
    }
    #[repr(C)]
    struct WenetCtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct OmnilingualAsrCtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct MedAsrCtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct FunASRNanoModelConfig {
        encoder_adaptor: *const c_char,
        llm: *const c_char,
        embedding: *const c_char,
        tokenizer: *const c_char,
        system_prompt: *const c_char,
        user_prompt: *const c_char,
        max_new_tokens: i32,
        temperature: f32,
        top_p: f32,
        seed: i32,
        language: *const c_char,
        itn: i32,
        hotwords: *const c_char,
    }
    #[repr(C)]
    struct FireRedAsrCtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct Qwen3AsrModelConfig {
        conv_frontend: *const c_char,
        encoder: *const c_char,
        decoder: *const c_char,
        tokenizer: *const c_char,
        max_total_len: i32,
        max_new_tokens: i32,
        temperature: f32,
        top_p: f32,
        seed: i32,
        hotwords: *const c_char,
    }
    #[repr(C)]
    struct CohereTranscribeModelConfig {
        encoder: *const c_char,
        decoder: *const c_char,
        language: *const c_char,
        use_punct: i32,
        use_itn: i32,
    }

    #[repr(C)]
    struct OfflineModelConfig {
        transducer: TransducerModelConfig,
        paraformer: ParaformerModelConfig,
        nemo_ctc: NemoEncDecCtcModelConfig,
        whisper: WhisperModelConfig,
        tdnn: TdnnModelConfig,
        tokens: *const c_char,
        num_threads: i32,
        debug: i32,
        provider: *const c_char,
        model_type: *const c_char,
        modeling_unit: *const c_char,
        bpe_vocab: *const c_char,
        telespeech_ctc: *const c_char,
        sense_voice: SenseVoiceModelConfig,
        moonshine: MoonshineModelConfig,
        fire_red_asr: FireRedAsrModelConfig,
        dolphin: DolphinModelConfig,
        zipformer_ctc: ZipformerCtcModelConfig,
        canary: CanaryModelConfig,
        wenet_ctc: WenetCtcModelConfig,
        omnilingual: OmnilingualAsrCtcModelConfig,
        medasr: MedAsrCtcModelConfig,
        funasr_nano: FunASRNanoModelConfig,
        fire_red_asr_ctc: FireRedAsrCtcModelConfig,
        qwen3_asr: Qwen3AsrModelConfig,
        cohere_transcribe: CohereTranscribeModelConfig,
    }

    #[repr(C)]
    struct FeatureConfig {
        sample_rate: i32,
        feature_dim: i32,
    }
    #[repr(C)]
    struct OfflineLmConfig {
        model: *const c_char,
        scale: f32,
    }
    #[repr(C)]
    struct HomophoneReplacerConfig {
        dict_dir: *const c_char,
        lexicon: *const c_char,
        rule_fsts: *const c_char,
    }
    #[repr(C)]
    struct OfflineRecognizerConfig {
        feat_config: FeatureConfig,
        model_config: OfflineModelConfig,
        lm_config: OfflineLmConfig,
        decoding_method: *const c_char,
        max_active_paths: i32,
        hotwords_file: *const c_char,
        hotwords_score: f32,
        rule_fsts: *const c_char,
        rule_fars: *const c_char,
        blank_penalty: f32,
        hr: HomophoneReplacerConfig,
    }

    // Full mirror of SherpaOnnxOfflineRecognizerResult (c-api.h v1.13.8). We read
    // `text` and `lang` (SenseVoice fills `lang` with a tag like "<|en|>"); every
    // preceding field must be declared so `lang`'s offset is correct.
    #[repr(C)]
    struct RecognizerResult {
        text: *const c_char,
        timestamps: *const f32,
        count: i32,
        tokens: *const c_char,
        tokens_arr: *const *const c_char,
        json: *const c_char,
        lang: *const c_char,
        emotion: *const c_char,
        event: *const c_char,
        durations: *const f32,
        ys_log_probs: *const f32,
        segment_timestamps: *const f32,
        segment_durations: *const f32,
        segment_texts: *const c_char,
        segment_texts_arr: *const *const c_char,
        segment_count: i32,
    }

    extern "C" {
        fn SherpaOnnxCreateOfflineRecognizer(
            config: *const OfflineRecognizerConfig,
        ) -> *const c_void;
        fn SherpaOnnxDestroyOfflineRecognizer(recognizer: *const c_void);
        fn SherpaOnnxCreateOfflineStream(recognizer: *const c_void) -> *const c_void;
        fn SherpaOnnxDestroyOfflineStream(stream: *const c_void);
        fn SherpaOnnxAcceptWaveformOffline(
            stream: *const c_void,
            sample_rate: i32,
            samples: *const f32,
            n: i32,
        );
        fn SherpaOnnxDecodeOfflineStream(recognizer: *const c_void, stream: *const c_void);
        fn SherpaOnnxGetOfflineStreamResult(stream: *const c_void) -> *const RecognizerResult;
        fn SherpaOnnxDestroyOfflineRecognizerResult(r: *const RecognizerResult);
    }

    /// Resident SenseVoice recognizer. The sherpa handle is a raw pointer, so it
    /// is not auto-`Send`; access is serialized through `ENGINE`'s `Mutex`, which
    /// makes single-owner cross-thread use sound.
    struct AsrEngine {
        model_dir: String,
        recognizer: *const c_void,
    }

    unsafe impl Send for AsrEngine {}

    impl Drop for AsrEngine {
        fn drop(&mut self) {
            unsafe { SherpaOnnxDestroyOfflineRecognizer(self.recognizer) };
        }
    }

    static ENGINE: OnceLock<Mutex<Option<AsrEngine>>> = OnceLock::new();

    fn engine_cell() -> &'static Mutex<Option<AsrEngine>> {
        ENGINE.get_or_init(|| Mutex::new(None))
    }

    fn create_recognizer(model_dir: &str) -> Result<*const c_void, String> {
        let dir = Path::new(model_dir);
        let model_path = dir.join("model.int8.onnx");
        let tokens_path = dir.join("tokens.txt");
        for p in [&model_path, &tokens_path] {
            if std::fs::File::open(p).is_err() {
                return Err(format!("cannot open {}", p.display()));
            }
        }

        let model = CString::new(model_path.to_string_lossy().into_owned()).map_err(|e| e.to_string())?;
        let tokens = CString::new(tokens_path.to_string_lossy().into_owned()).map_err(|e| e.to_string())?;
        let language = CString::new("auto").unwrap(); // language ID
        let provider = CString::new("cpu").unwrap();
        let decoding = CString::new("greedy_search").unwrap();

        let mut cfg: OfflineRecognizerConfig = unsafe { std::mem::zeroed() };
        cfg.feat_config.sample_rate = 16000;
        cfg.feat_config.feature_dim = 80;
        cfg.model_config.tokens = tokens.as_ptr();
        cfg.model_config.num_threads = 2;
        cfg.model_config.debug = 1;
        cfg.model_config.provider = provider.as_ptr();
        cfg.model_config.sense_voice.model = model.as_ptr();
        cfg.model_config.sense_voice.language = language.as_ptr();
        cfg.model_config.sense_voice.use_itn = 1;
        cfg.decoding_method = decoding.as_ptr();

        // sherpa copies the config strings internally, so the CStrings above can
        // be dropped once this returns.
        let recognizer = unsafe { SherpaOnnxCreateOfflineRecognizer(&cfg) };
        if recognizer.is_null() {
            return Err("SherpaOnnxCreateOfflineRecognizer returned null".to_owned());
        }
        Ok(recognizer)
    }

    fn ensure_loaded<'a>(
        guard: &'a mut Option<AsrEngine>,
        model_dir: &str,
    ) -> Result<&'a AsrEngine, String> {
        let stale = guard.as_ref().map(|e| e.model_dir.as_str()) != Some(model_dir);
        if stale {
            let recognizer = create_recognizer(model_dir)?;
            *guard = Some(AsrEngine {
                model_dir: model_dir.to_owned(),
                recognizer,
            });
        }
        Ok(guard.as_ref().expect("engine just loaded"))
    }

    pub fn load(model_dir: &str) -> Result<(), String> {
        let mut guard = engine_cell().lock().map_err(|e| e.to_string())?;
        ensure_loaded(&mut guard, model_dir)?;
        Ok(())
    }

    pub fn unload() {
        // A poisoned lock still guards a valid Option; dropping the engine is the goal.
        *engine_cell().lock().unwrap_or_else(|e| e.into_inner()) = None;
    }

    unsafe fn cstr(p: *const c_char) -> String {
        if p.is_null() {
            String::new()
        } else {
            CStr::from_ptr(p).to_string_lossy().into_owned()
        }
    }

    pub fn recognize(
        model_dir: &str,
        samples: &[f32],
        sample_rate: i32,
    ) -> Result<(String, String), String> {
        let mut guard = engine_cell().lock().map_err(|e| e.to_string())?;
        let recognizer = ensure_loaded(&mut guard, model_dir)?.recognizer;

        // The recognizer is reused; only the per-utterance stream is transient.
        let stream = unsafe { SherpaOnnxCreateOfflineStream(recognizer) };
        if stream.is_null() {
            return Err("CreateOfflineStream returned null".to_owned());
        }
        unsafe {
            SherpaOnnxAcceptWaveformOffline(
                stream,
                sample_rate,
                samples.as_ptr(),
                samples.len() as i32,
            );
            SherpaOnnxDecodeOfflineStream(recognizer, stream);
        }
        let res = unsafe { SherpaOnnxGetOfflineStreamResult(stream) };
        let (text, lang) = if res.is_null() {
            (String::new(), String::new())
        } else {
            let t = unsafe { cstr((*res).text) };
            let l = unsafe { cstr((*res).lang) };
            unsafe { SherpaOnnxDestroyOfflineRecognizerResult(res) };
            (t, l)
        };
        unsafe { SherpaOnnxDestroyOfflineStream(stream) };
        Ok((text, lang))
    }
}

#[cfg(target_os = "android")]
#[allow(dead_code)] // several config fields are part of the ABI but unused here
mod stream_imp {
    use std::ffi::{CStr, CString};
    use std::os::raw::{c_char, c_void};
    use std::path::Path;
    use std::sync::{Mutex, OnceLock};

    // --- C struct mirrors (sherpa-onnx c-api.h v1.13.8). Order/types must match exactly. ---

    #[repr(C)]
    struct OnlineTransducerModelConfig {
        encoder: *const c_char,
        decoder: *const c_char,
        joiner: *const c_char,
    }
    #[repr(C)]
    struct OnlineParaformerModelConfig {
        encoder: *const c_char,
        decoder: *const c_char,
    }
    #[repr(C)]
    struct OnlineZipformer2CtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct OnlineNemoCtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct OnlineToneCtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct OnlineModelConfig {
        transducer: OnlineTransducerModelConfig,
        paraformer: OnlineParaformerModelConfig,
        zipformer2_ctc: OnlineZipformer2CtcModelConfig,
        tokens: *const c_char,
        num_threads: i32,
        provider: *const c_char,
        debug: i32,
        model_type: *const c_char,
        modeling_unit: *const c_char,
        bpe_vocab: *const c_char,
        tokens_buf: *const c_char,
        tokens_buf_size: i32,
        nemo_ctc: OnlineNemoCtcModelConfig,
        t_one_ctc: OnlineToneCtcModelConfig,
    }
    #[repr(C)]
    struct FeatureConfig {
        sample_rate: i32,
        feature_dim: i32,
    }
    #[repr(C)]
    struct OnlineCtcFstDecoderConfig {
        graph: *const c_char,
        max_active: i32,
    }
    #[repr(C)]
    struct HomophoneReplacerConfig {
        dict_dir: *const c_char,
        lexicon: *const c_char,
        rule_fsts: *const c_char,
    }
    #[repr(C)]
    struct OnlineRecognizerConfig {
        feat_config: FeatureConfig,
        model_config: OnlineModelConfig,
        decoding_method: *const c_char,
        max_active_paths: i32,
        enable_endpoint: i32,
        rule1_min_trailing_silence: f32,
        rule2_min_trailing_silence: f32,
        rule3_min_utterance_length: f32,
        hotwords_file: *const c_char,
        hotwords_score: f32,
        ctc_fst_decoder_config: OnlineCtcFstDecoderConfig,
        rule_fsts: *const c_char,
        rule_fars: *const c_char,
        blank_penalty: f32,
        hotwords_buf: *const c_char,
        hotwords_buf_size: i32,
        hr: HomophoneReplacerConfig,
    }
    #[repr(C)]
    struct OnlineRecognizerResult {
        text: *const c_char,
        tokens: *const c_char,
        tokens_arr: *const *const c_char,
        timestamps: *const f32,
        count: i32,
        json: *const c_char,
    }

    extern "C" {
        fn SherpaOnnxCreateOnlineRecognizer(config: *const OnlineRecognizerConfig) -> *const c_void;
        fn SherpaOnnxDestroyOnlineRecognizer(recognizer: *const c_void);
        fn SherpaOnnxCreateOnlineStream(recognizer: *const c_void) -> *const c_void;
        fn SherpaOnnxDestroyOnlineStream(stream: *const c_void);
        // Options survive `SherpaOnnxOnlineStreamReset`; "" and "auto" select the default prompt.
        fn SherpaOnnxOnlineStreamSetOption(
            stream: *const c_void,
            key: *const c_char,
            value: *const c_char,
        );
        fn SherpaOnnxOnlineStreamAcceptWaveform(
            stream: *const c_void,
            sample_rate: i32,
            samples: *const f32,
            n: i32,
        );
        fn SherpaOnnxIsOnlineStreamReady(
            recognizer: *const c_void,
            stream: *const c_void,
        ) -> i32;
        fn SherpaOnnxDecodeOnlineStream(recognizer: *const c_void, stream: *const c_void);
        fn SherpaOnnxGetOnlineStreamResult(
            recognizer: *const c_void,
            stream: *const c_void,
        ) -> *const OnlineRecognizerResult;
        fn SherpaOnnxDestroyOnlineRecognizerResult(r: *const OnlineRecognizerResult);
        fn SherpaOnnxOnlineStreamReset(recognizer: *const c_void, stream: *const c_void);
        fn SherpaOnnxOnlineStreamIsEndpoint(
            recognizer: *const c_void,
            stream: *const c_void,
        ) -> i32;
    }

    // Nemotron 3.5 is multilingual; "auto" lets it pick the language per utterance.
    const DEFAULT_LANGUAGE: &str = "auto";

    /// Endpoint rules (seconds), baked into the recognizer at creation.
    #[derive(Clone, Copy, PartialEq)]
    struct EndpointRules {
        rule1: f32, // min trailing silence before anything is decoded
        rule2: f32, // min trailing silence after speech (ends a turn)
        rule3: f32, // max utterance length (force-cut)
    }

    impl Default for EndpointRules {
        fn default() -> Self {
            EndpointRules { rule1: 2.4, rule2: 1.2, rule3: 20.0 }
        }
    }

    /// Resident streaming recognizer plus its single live stream. Both are raw
    /// sherpa pointers (not auto-`Send`); all access is serialized through
    /// `ENGINE`'s `Mutex`, which makes single-owner cross-thread use sound.
    struct StreamEngine {
        model_dir: String,
        rules: EndpointRules,
        language: String,
        recognizer: *const c_void,
        stream: *const c_void,
    }

    unsafe impl Send for StreamEngine {}

    impl Drop for StreamEngine {
        fn drop(&mut self) {
            unsafe {
                SherpaOnnxDestroyOnlineStream(self.stream);
                SherpaOnnxDestroyOnlineRecognizer(self.recognizer);
            }
        }
    }

    static ENGINE: OnceLock<Mutex<Option<StreamEngine>>> = OnceLock::new();

    fn engine_cell() -> &'static Mutex<Option<StreamEngine>> {
        ENGINE.get_or_init(|| Mutex::new(None))
    }

    /// Sets the stream's decoding language. Sherpa reads it per stream, which is
    /// why it can change without recreating the recognizer.
    fn set_language(stream: *const c_void, language: &str) -> Result<(), String> {
        let key = CString::new("language").unwrap();
        let value = CString::new(language).map_err(|e| e.to_string())?;
        unsafe { SherpaOnnxOnlineStreamSetOption(stream, key.as_ptr(), value.as_ptr()) };
        Ok(())
    }

    fn create_engine(
        model_dir: &str,
        rules: EndpointRules,
        language: &str,
    ) -> Result<StreamEngine, String> {
        let dir = Path::new(model_dir);
        let encoder_path = dir.join("encoder.int8.onnx");
        let decoder_path = dir.join("decoder.int8.onnx");
        let joiner_path = dir.join("joiner.int8.onnx");
        let tokens_path = dir.join("tokens.txt");
        for p in [&encoder_path, &decoder_path, &joiner_path, &tokens_path] {
            if std::fs::File::open(p).is_err() {
                return Err(format!("cannot open {}", p.display()));
            }
        }

        let encoder = CString::new(encoder_path.to_string_lossy().into_owned()).map_err(|e| e.to_string())?;
        let decoder = CString::new(decoder_path.to_string_lossy().into_owned()).map_err(|e| e.to_string())?;
        let joiner = CString::new(joiner_path.to_string_lossy().into_owned()).map_err(|e| e.to_string())?;
        let tokens = CString::new(tokens_path.to_string_lossy().into_owned()).map_err(|e| e.to_string())?;
        let provider = CString::new("cpu").unwrap();
        let decoding = CString::new("greedy_search").unwrap();

        let mut cfg: OnlineRecognizerConfig = unsafe { std::mem::zeroed() };
        // feature_dim is read from the encoder's metadata for this model; the
        // value here is a placeholder that sherpa overrides.
        cfg.feat_config.sample_rate = 16000;
        cfg.feat_config.feature_dim = 80;
        cfg.model_config.transducer.encoder = encoder.as_ptr();
        cfg.model_config.transducer.decoder = decoder.as_ptr();
        cfg.model_config.transducer.joiner = joiner.as_ptr();
        cfg.model_config.tokens = tokens.as_ptr();
        cfg.model_config.num_threads = 2;
        cfg.model_config.provider = provider.as_ptr();
        cfg.model_config.debug = 1;
        // model_type left empty: sherpa auto-detects the transducer flavor from
        // the encoder metadata (the streaming Nemotron model needs no flag).
        cfg.decoding_method = decoding.as_ptr();
        cfg.enable_endpoint = 1;
        cfg.rule1_min_trailing_silence = rules.rule1;
        cfg.rule2_min_trailing_silence = rules.rule2;
        cfg.rule3_min_utterance_length = rules.rule3;

        let recognizer = unsafe { SherpaOnnxCreateOnlineRecognizer(&cfg) };
        if recognizer.is_null() {
            return Err("SherpaOnnxCreateOnlineRecognizer returned null".to_owned());
        }
        let stream = unsafe { SherpaOnnxCreateOnlineStream(recognizer) };
        if stream.is_null() {
            unsafe { SherpaOnnxDestroyOnlineRecognizer(recognizer) };
            return Err("SherpaOnnxCreateOnlineStream returned null".to_owned());
        }
        // Build the engine before SetOption so its Drop frees both handles on failure.
        let engine = StreamEngine {
            model_dir: model_dir.to_owned(),
            rules,
            language: language.to_owned(),
            recognizer,
            stream,
        };
        set_language(engine.stream, language)?;
        Ok(engine)
    }

    /// Loads the engine, recreating it when `model_dir` or the endpoint `rules`
    /// differ from what is resident (rules are immutable post-creation). A
    /// different `language` alone is applied to the existing stream.
    fn ensure_loaded<'a>(
        guard: &'a mut Option<StreamEngine>,
        model_dir: &str,
        rules: EndpointRules,
        language: &str,
    ) -> Result<&'a StreamEngine, String> {
        let stale = match guard.as_ref() {
            Some(e) => e.model_dir != model_dir || e.rules != rules,
            None => true,
        };
        if stale {
            *guard = Some(create_engine(model_dir, rules, language)?);
        } else if let Some(e) = guard.as_mut() {
            if e.language != language {
                set_language(e.stream, language)?;
                e.language = language.to_owned();
            }
        }
        Ok(guard.as_ref().expect("engine just loaded"))
    }

    /// Reuses the resident engine for `model_dir`, or creates one with default
    /// rules and language if none is loaded yet (a fallback; callers normally
    /// `load` first, including after an unload).
    fn ensure_any<'a>(
        guard: &'a mut Option<StreamEngine>,
        model_dir: &str,
    ) -> Result<&'a StreamEngine, String> {
        let rules = guard.as_ref().map_or_else(EndpointRules::default, |e| e.rules);
        let language = guard
            .as_ref()
            .map_or_else(|| DEFAULT_LANGUAGE.to_owned(), |e| e.language.clone());
        ensure_loaded(guard, model_dir, rules, &language)
    }

    pub fn load(
        model_dir: &str,
        rule1: f32,
        rule2: f32,
        rule3: f32,
        language: &str,
    ) -> Result<(), String> {
        let language = if language.is_empty() { DEFAULT_LANGUAGE } else { language };
        let mut guard = engine_cell().lock().map_err(|e| e.to_string())?;
        ensure_loaded(&mut guard, model_dir, EndpointRules { rule1, rule2, rule3 }, language)?;
        Ok(())
    }

    pub fn unload() {
        // A poisoned lock still guards a valid Option; dropping the engine is the goal.
        *engine_cell().lock().unwrap_or_else(|e| e.into_inner()) = None;
    }

    unsafe fn cstr(p: *const c_char) -> String {
        if p.is_null() {
            String::new()
        } else {
            CStr::from_ptr(p).to_string_lossy().into_owned()
        }
    }

    pub fn accept(
        model_dir: &str,
        samples: &[f32],
        sample_rate: i32,
    ) -> Result<(String, bool), String> {
        let mut guard = engine_cell().lock().map_err(|e| e.to_string())?;
        let engine = ensure_any(&mut guard, model_dir)?;
        let (recognizer, stream) = (engine.recognizer, engine.stream);

        unsafe {
            SherpaOnnxOnlineStreamAcceptWaveform(
                stream,
                sample_rate,
                samples.as_ptr(),
                samples.len() as i32,
            );
            while SherpaOnnxIsOnlineStreamReady(recognizer, stream) != 0 {
                SherpaOnnxDecodeOnlineStream(recognizer, stream);
            }
        }

        let res = unsafe { SherpaOnnxGetOnlineStreamResult(recognizer, stream) };
        let text = if res.is_null() {
            String::new()
        } else {
            let t = unsafe { cstr((*res).text) };
            unsafe { SherpaOnnxDestroyOnlineRecognizerResult(res) };
            t
        };

        let is_endpoint = unsafe { SherpaOnnxOnlineStreamIsEndpoint(recognizer, stream) } != 0;
        if is_endpoint {
            // Reset so the next chunk begins a fresh utterance; the text above is
            // the finalized transcript for the segment just ended.
            unsafe { SherpaOnnxOnlineStreamReset(recognizer, stream) };
        }
        Ok((text, is_endpoint))
    }

    pub fn reset(model_dir: &str) -> Result<(), String> {
        let mut guard = engine_cell().lock().map_err(|e| e.to_string())?;
        let engine = ensure_any(&mut guard, model_dir)?;
        unsafe { SherpaOnnxOnlineStreamReset(engine.recognizer, engine.stream) };
        Ok(())
    }
}
