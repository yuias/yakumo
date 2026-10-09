# Builds the Rust core for Android and regenerates the UniFFI Kotlin bindings.
# Run from anywhere; paths are resolved relative to this script.
#
#   pwsh rust/build-android.ps1            # arm64 + x86_64 (default)
#   pwsh rust/build-android.ps1 -Release   # optimized build
param(
    [string[]]$Abis = @('arm64-v8a', 'x86_64'),
    [switch]$Release
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot          # repo root
$crate = Join-Path $PSScriptRoot 'core'
$jniLibs = Join-Path $root 'android\app\src\main\jniLibs'
$ktOut = Join-Path $root 'android\app\src\main\java'

# cargo-ndk locates the NDK via these; set them so the script is self-contained.
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk\27.2.12479018'

# llama.cpp is built with CMake + Ninja; the Android SDK `cmake;<ver>` package ships both.
if (-not (Get-Command cmake -ErrorAction SilentlyContinue)) {
    $sdkCmake = Get-ChildItem (Join-Path $env:ANDROID_HOME 'cmake') -Directory -ErrorAction SilentlyContinue |
        Sort-Object { [version]($_.Name -replace '[^\d.].*$', '') } -Descending | Select-Object -First 1
    if ($sdkCmake) {
        $env:PATH = (Join-Path $sdkCmake.FullName 'bin') + [IO.Path]::PathSeparator + $env:PATH
    }
}

# [string[]] is load-bearing: an `if` expression returns through the pipeline,
# which unwraps a one-element array to a bare string. Splatting that string
# passes it one character at a time, so cargo sees a lone '-'.
[string[]]$profileArgs = if ($Release) { @('--release') } else { @() }

Push-Location $crate
try {
    Write-Host '== Building .so via cargo-ndk ==' -ForegroundColor Cyan
    # One invocation per ABI: llama-cpp-sys-2 forwards every GGML_* env var to CMake for
    # all ABIs, so GGML_CPU_ARM_ARCH is set for arm64 only and cleared otherwise.
    # Keep the value in sync with android/app/build.gradle.kts.
    foreach ($abi in $Abis) {
        if ($abi -eq 'arm64-v8a') {
            $env:GGML_CPU_ARM_ARCH = 'armv8.2-a+dotprod'
        }
        else {
            Remove-Item Env:GGML_CPU_ARM_ARCH -ErrorAction SilentlyContinue
        }
        cargo ndk --platform 24 -t $abi -o $jniLibs build @profileArgs
        if ($LASTEXITCODE -ne 0) { throw "cargo ndk failed for $abi" }
    }

    Write-Host '== Generating Kotlin bindings ==' -ForegroundColor Cyan
    $lib = Join-Path $jniLibs 'arm64-v8a\libtranslatecore.so'
    cargo run --quiet --bin uniffi-bindgen generate --library $lib --language kotlin --out-dir $ktOut
}
finally {
    Pop-Location
}
Write-Host 'Done.' -ForegroundColor Green
