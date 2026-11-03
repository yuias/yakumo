# Builds a signed release APK on Windows.
#
#   pwsh tools/build-release.ps1
#
# Needs the gitignored signing files in place: android/keystore.properties and
# the keystore its storeFile points to (see README.RELEASE.md). Gradle quietly
# produces an unsigned APK when a credential is missing, so this script checks
# the files first and fails if the result is not signed.
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root 'android'
$propsFile = Join-Path $android 'keystore.properties'

# ---- Preflight: signing files ---------------------------------------------
$problems = @()
if (-not (Test-Path $propsFile)) {
    $problems += 'Missing android/keystore.properties (copy android/keystore.properties.template and fill it in).'
}
else {
    $props = @{}
    foreach ($line in Get-Content $propsFile) {
        if ($line -match '^\s*([^#!=:\s]+)\s*[=:]\s*(.*?)\s*$') { $props[$Matches[1]] = $Matches[2] }
    }
    foreach ($key in 'storeFile', 'storePassword', 'keyAlias', 'keyPassword') {
        # CHANGEME is the placeholder in keystore.properties.template.
        if (-not $props[$key] -or $props[$key] -eq 'CHANGEME') {
            $problems += "android/keystore.properties: '$key' is not set."
        }
    }
    if ($props['storeFile']) {
        # Gradle resolves storeFile with rootProject.file(), i.e. relative to android/.
        $store = if ([IO.Path]::IsPathRooted($props['storeFile'])) { $props['storeFile'] }
                 else { Join-Path $android $props['storeFile'] }
        if (-not (Test-Path $store -PathType Leaf)) {
            $problems += "Missing keystore '$($props['storeFile'])' (storeFile in android/keystore.properties, relative to android/)."
        }
    }
}
if ($problems) {
    $problems | ForEach-Object { Write-Host "error: $_" -ForegroundColor Red }
    Write-Host 'See README.RELEASE.md for how to create these files.'
    exit 1
}

# ---- Build ----------------------------------------------------------------
$outDir = Join-Path $android 'app\build\outputs\apk\release'
$apk = Join-Path $outDir 'app-release.apk'
# Remove earlier outputs so a stale APK cannot pass the check below.
Remove-Item (Join-Path $outDir 'app-release*.apk') -ErrorAction SilentlyContinue

Write-Host '== Building release APK ==' -ForegroundColor Cyan
& (Join-Path $android 'gradlew.bat') -p $android :app:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { Write-Host 'error: Gradle build failed.' -ForegroundColor Red; exit 1 }

if (-not (Test-Path $apk)) {
    Write-Host 'error: Gradle did not produce a signed APK (app-release.apk). Check the values in android/keystore.properties.' -ForegroundColor Red
    exit 1
}

# ---- Verify the signature -------------------------------------------------
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
$apksigner = $null
if ($sdk) {
    $apksigner = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object { [version]($_.Name -replace '[^\d.].*$', '') } -Descending |
        ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
}
if ($apksigner) {
    Write-Host '== Verifying signature ==' -ForegroundColor Cyan
    & $apksigner verify --print-certs $apk
    if ($LASTEXITCODE -ne 0) { Write-Host 'error: apksigner verify failed.' -ForegroundColor Red; exit 1 }
}
else {
    Write-Host 'warning: apksigner not found (set ANDROID_HOME); signature not verified.' -ForegroundColor Yellow
}

Write-Host "Signed APK: android/app/build/outputs/apk/release/app-release.apk" -ForegroundColor Green
