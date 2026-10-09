//! Silero VAD via the sherpa-onnx C API. Android-only; host stub.
//!
//! Replaces the previous energy-RMS segmenter: Silero is a learned VAD that
//! holds up under non-stationary noise (airports, cafes) where a fixed energy
//! threshold collapses. The detector is fed a continuous stream in arbitrary
//! chunks and emits finished speech segments, which we hand to SenseVoice.
//!
//! The detector is expensive to build but reusable, so it is kept resident in a
//! process-global keyed by `(model_dir, params)`; changing the tuning params
//! recreates it (they are baked in at creation, as with the streaming ASR).

#[cfg(target_os = "android")]
pub fn load(
    model_dir: &str,
    threshold: f32,
    min_silence_s: f32,
    min_speech_s: f32,
    max_speech_s: f32,
) -> Result<(), String> {
    imp::load(model_dir, threshold, min_silence_s, min_speech_s, max_speech_s)
}

/// Feeds one chunk of mono f32 PCM into the resident detector and returns any
/// speech segments that completed on this chunk (each as a run of samples).
#[cfg(target_os = "android")]
pub fn accept(model_dir: &str, samples: &[f32], sample_rate: i32) -> Result<Vec<Vec<f32>>, String> {
    imp::accept(model_dir, samples, sample_rate)
}

/// Forces the in-progress utterance (if any) to be emitted, e.g. when recording
/// stops mid-sentence. Returns the flushed segment(s).
#[cfg(target_os = "android")]
pub fn flush(model_dir: &str) -> Result<Vec<Vec<f32>>, String> {
    imp::flush(model_dir)
}

/// Clears all detector state, discarding any buffered audio.
#[cfg(target_os = "android")]
pub fn reset(model_dir: &str) -> Result<(), String> {
    imp::reset(model_dir)
}

/// Drops the resident detector (no-op if none).
#[cfg(target_os = "android")]
pub fn unload() {
    imp::unload()
}

#[cfg(not(target_os = "android"))]
pub fn unload() {}

#[cfg(not(target_os = "android"))]
pub fn load(
    _model_dir: &str,
    _threshold: f32,
    _min_silence_s: f32,
    _min_speech_s: f32,
    _max_speech_s: f32,
) -> Result<(), String> {
    Err("VAD is only available on Android (native sherpa-onnx not linked on host)".to_owned())
}

#[cfg(not(target_os = "android"))]
pub fn accept(_model_dir: &str, _samples: &[f32], _sample_rate: i32) -> Result<Vec<Vec<f32>>, String> {
    Err("VAD is only available on Android".to_owned())
}

#[cfg(not(target_os = "android"))]
pub fn flush(_model_dir: &str) -> Result<Vec<Vec<f32>>, String> {
    Err("VAD is only available on Android".to_owned())
}

#[cfg(not(target_os = "android"))]
pub fn reset(_model_dir: &str) -> Result<(), String> {
    Err("VAD is only available on Android".to_owned())
}

#[cfg(target_os = "android")]
#[allow(dead_code)] // some config fields are part of the ABI but unused for Silero
mod imp {
    use std::ffi::CString;
    use std::os::raw::{c_char, c_void};
    use std::path::Path;
    use std::sync::{Mutex, OnceLock};

    // --- C struct mirrors (sherpa-onnx c-api.h v1.13.8). Order/types must match exactly. ---

    #[repr(C)]
    struct SileroVadModelConfig {
        model: *const c_char,
        threshold: f32,
        min_silence_duration: f32,
        min_speech_duration: f32,
        window_size: i32,
        max_speech_duration: f32,
    }
    #[repr(C)]
    struct TenVadModelConfig {
        model: *const c_char,
        threshold: f32,
        min_silence_duration: f32,
        min_speech_duration: f32,
        window_size: i32,
        max_speech_duration: f32,
    }
    #[repr(C)]
    struct VadModelConfig {
        silero_vad: SileroVadModelConfig,
        sample_rate: i32,
        num_threads: i32,
        provider: *const c_char,
        debug: i32,
        ten_vad: TenVadModelConfig,
    }
    #[repr(C)]
    struct SpeechSegment {
        start: i32,
        samples: *const f32,
        n: i32,
    }

    extern "C" {
        fn SherpaOnnxCreateVoiceActivityDetector(
            config: *const VadModelConfig,
            buffer_size_in_seconds: f32,
        ) -> *const c_void;
        fn SherpaOnnxDestroyVoiceActivityDetector(p: *const c_void);
        fn SherpaOnnxVoiceActivityDetectorAcceptWaveform(
            p: *const c_void,
            samples: *const f32,
            n: i32,
        );
        fn SherpaOnnxVoiceActivityDetectorEmpty(p: *const c_void) -> i32;
        fn SherpaOnnxVoiceActivityDetectorPop(p: *const c_void);
        fn SherpaOnnxVoiceActivityDetectorFront(p: *const c_void) -> *const SpeechSegment;
        fn SherpaOnnxDestroySpeechSegment(p: *const SpeechSegment);
        fn SherpaOnnxVoiceActivityDetectorReset(p: *const c_void);
        fn SherpaOnnxVoiceActivityDetectorFlush(p: *const c_void);
    }

    // Silero v5/v6 expect 512-sample (32 ms @ 16 kHz) analysis windows.
    const WINDOW_SIZE: i32 = 512;
    // Detector ring buffer; must comfortably exceed max_speech_duration.
    const BUFFER_SECONDS: f32 = 30.0;

    /// Tuning baked into the detector at creation (changing any recreates it).
    #[derive(Clone, Copy, PartialEq)]
    struct Params {
        threshold: f32,
        min_silence: f32,
        min_speech: f32,
        max_speech: f32,
    }

    /// Resident Silero detector. The sherpa handle is a raw pointer (not auto-
    /// `Send`); all access is serialized through `ENGINE`'s `Mutex`, which makes
    /// single-owner cross-thread use sound.
    struct VadEngine {
        model_dir: String,
        params: Params,
        vad: *const c_void,
    }

    unsafe impl Send for VadEngine {}

    impl Drop for VadEngine {
        fn drop(&mut self) {
            unsafe { SherpaOnnxDestroyVoiceActivityDetector(self.vad) };
        }
    }

    static ENGINE: OnceLock<Mutex<Option<VadEngine>>> = OnceLock::new();

    fn engine_cell() -> &'static Mutex<Option<VadEngine>> {
        ENGINE.get_or_init(|| Mutex::new(None))
    }

    fn create(model_dir: &str, params: Params) -> Result<VadEngine, String> {
        let model_path = Path::new(model_dir).join("silero_vad.onnx");
        if std::fs::File::open(&model_path).is_err() {
            return Err(format!("cannot open {}", model_path.display()));
        }
        let model = CString::new(model_path.to_string_lossy().into_owned()).map_err(|e| e.to_string())?;
        let provider = CString::new("cpu").unwrap();

        let mut cfg: VadModelConfig = unsafe { std::mem::zeroed() };
        cfg.silero_vad.model = model.as_ptr();
        cfg.silero_vad.threshold = params.threshold;
        cfg.silero_vad.min_silence_duration = params.min_silence;
        cfg.silero_vad.min_speech_duration = params.min_speech;
        cfg.silero_vad.window_size = WINDOW_SIZE;
        cfg.silero_vad.max_speech_duration = params.max_speech;
        cfg.sample_rate = 16000;
        cfg.num_threads = 1;
        cfg.provider = provider.as_ptr();
        cfg.debug = 0;

        let vad = unsafe { SherpaOnnxCreateVoiceActivityDetector(&cfg, BUFFER_SECONDS) };
        if vad.is_null() {
            return Err("SherpaOnnxCreateVoiceActivityDetector returned null".to_owned());
        }
        Ok(VadEngine {
            model_dir: model_dir.to_owned(),
            params,
            vad,
        })
    }

    fn ensure_loaded<'a>(
        guard: &'a mut Option<VadEngine>,
        model_dir: &str,
        params: Params,
    ) -> Result<&'a VadEngine, String> {
        let stale = match guard.as_ref() {
            Some(e) => e.model_dir != model_dir || e.params != params,
            None => true,
        };
        if stale {
            *guard = Some(create(model_dir, params)?);
        }
        Ok(guard.as_ref().expect("engine just loaded"))
    }

    /// Reuses the resident detector for `model_dir`; errors if none is loaded
    /// (callers `load` first, which bakes in the tuning params).
    fn vad_for(guard: &Option<VadEngine>, model_dir: &str) -> Result<*const c_void, String> {
        match guard.as_ref() {
            Some(e) if e.model_dir == model_dir => Ok(e.vad),
            _ => Err("VAD not loaded for this model_dir".to_owned()),
        }
    }

    /// Drains every completed segment out of the detector's queue.
    unsafe fn drain(vad: *const c_void) -> Vec<Vec<f32>> {
        let mut segs = Vec::new();
        while SherpaOnnxVoiceActivityDetectorEmpty(vad) == 0 {
            let seg = SherpaOnnxVoiceActivityDetectorFront(vad);
            if !seg.is_null() {
                let n = (*seg).n.max(0) as usize;
                segs.push(std::slice::from_raw_parts((*seg).samples, n).to_vec());
                SherpaOnnxDestroySpeechSegment(seg);
            }
            SherpaOnnxVoiceActivityDetectorPop(vad);
        }
        segs
    }

    pub fn load(
        model_dir: &str,
        threshold: f32,
        min_silence_s: f32,
        min_speech_s: f32,
        max_speech_s: f32,
    ) -> Result<(), String> {
        let mut guard = engine_cell().lock().map_err(|e| e.to_string())?;
        ensure_loaded(
            &mut guard,
            model_dir,
            Params {
                threshold,
                min_silence: min_silence_s,
                min_speech: min_speech_s,
                max_speech: max_speech_s,
            },
        )?;
        Ok(())
    }

    pub fn unload() {
        // A poisoned lock still guards a valid Option; dropping the engine is the goal.
        *engine_cell().lock().unwrap_or_else(|e| e.into_inner()) = None;
    }

    pub fn accept(model_dir: &str, samples: &[f32], _sample_rate: i32) -> Result<Vec<Vec<f32>>, String> {
        let guard = engine_cell().lock().map_err(|e| e.to_string())?;
        let vad = vad_for(&guard, model_dir)?;
        unsafe {
            SherpaOnnxVoiceActivityDetectorAcceptWaveform(vad, samples.as_ptr(), samples.len() as i32);
            Ok(drain(vad))
        }
    }

    pub fn flush(model_dir: &str) -> Result<Vec<Vec<f32>>, String> {
        let guard = engine_cell().lock().map_err(|e| e.to_string())?;
        let vad = vad_for(&guard, model_dir)?;
        unsafe {
            SherpaOnnxVoiceActivityDetectorFlush(vad);
            Ok(drain(vad))
        }
    }

    pub fn reset(model_dir: &str) -> Result<(), String> {
        let guard = engine_cell().lock().map_err(|e| e.to_string())?;
        let vad = vad_for(&guard, model_dir)?;
        unsafe { SherpaOnnxVoiceActivityDetectorReset(vad) };
        Ok(())
    }
}
