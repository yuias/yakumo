//! Machine translation via llama.cpp (GGUF models). Android-only; host stubs.
//!
//! llama.cpp is statically linked into `libtranslatecore.so`. Its CPU kernels are
//! built with `+dotprod` (GGML_CPU_ARM_ARCH in the per-ABI build calls), so on a core without ASIMDDP
//! the first quantized dot product raises SIGILL and kills the process. Every
//! entry point therefore checks [`cpu_supported`] before touching llama.cpp.

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

#[cfg(target_os = "android")]
mod imp {
    use super::{cpu_supported, UNSUPPORTED_MSG};
    use llama_cpp_2::llama_backend::LlamaBackend;
    use std::ffi::CStr;
    use std::sync::{Mutex, OnceLock};

    /// Initialized once, after the CPU gate, and never dropped: dropping would call
    /// `llama_backend_free` and make a later `init` fail.
    static BACKEND: OnceLock<LlamaBackend> = OnceLock::new();
    // `LlamaBackend::init` fails on a second call, so racing first callers must be
    // serialized instead of relying on `OnceLock::get_or_init`.
    static INIT_LOCK: Mutex<()> = Mutex::new(());

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
}
