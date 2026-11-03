#!/usr/bin/env sh
# Builds a signed release APK on Linux / macOS.
#
#   sh tools/build-release.sh
#
# Needs the gitignored signing files in place: android/keystore.properties and
# the keystore its storeFile points to (see README.RELEASE.md). Gradle quietly
# produces an unsigned APK when a credential is missing, so this script checks
# the files first and fails if the result is not signed.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
android="$root/android"
props_file="$android/keystore.properties"

err() { printf 'error: %s\n' "$*" >&2; }

# ---- Preflight: signing files ---------------------------------------------
prop() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*[=:][[:space:]]*//p" "$props_file" | tr -d '\r' | sed 's/[[:space:]]*$//' | tail -n 1
}

ok=1
if [ ! -f "$props_file" ]; then
  err "Missing android/keystore.properties (copy android/keystore.properties.template and fill it in)."
  ok=0
else
  for key in storeFile storePassword keyAlias keyPassword; do
    value=$(prop "$key")
    # CHANGEME is the placeholder in keystore.properties.template.
    if [ -z "$value" ] || [ "$value" = CHANGEME ]; then
      err "android/keystore.properties: '$key' is not set."
      ok=0
    fi
  done
  store=$(prop storeFile)
  if [ -n "$store" ]; then
    # Gradle resolves storeFile with rootProject.file(), i.e. relative to android/.
    case "$store" in
      /*) path="$store" ;;
      *) path="$android/$store" ;;
    esac
    if [ ! -f "$path" ]; then
      err "Missing keystore '$store' (storeFile in android/keystore.properties, relative to android/)."
      ok=0
    fi
  fi
fi
if [ "$ok" -eq 0 ]; then
  echo "See README.RELEASE.md for how to create these files." >&2
  exit 1
fi

# ---- Build ----------------------------------------------------------------
out_dir="$android/app/build/outputs/apk/release"
apk="$out_dir/yakumo.apk"
# Remove earlier outputs so a stale APK cannot pass the check below.
rm -f "$out_dir"/yakumo*.apk

echo "== Building release APK =="
sh "$android/gradlew" -p "$android" :app:assembleRelease --console=plain || {
  err "Gradle build failed."
  exit 1
}

if [ ! -f "$apk" ]; then
  err "Gradle did not produce a signed APK (yakumo.apk). Check the values in android/keystore.properties."
  exit 1
fi

# ---- Verify the signature -------------------------------------------------
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
apksigner=
if [ -n "$sdk" ] && [ -d "$sdk/build-tools" ]; then
  for dir in $(ls "$sdk/build-tools" | sort -t. -k1,1n -k2,2n -k3,3n -r); do
    if [ -x "$sdk/build-tools/$dir/apksigner" ]; then
      apksigner="$sdk/build-tools/$dir/apksigner"
      break
    fi
  done
fi
if [ -n "$apksigner" ]; then
  echo "== Verifying signature =="
  "$apksigner" verify --print-certs "$apk" || {
    err "apksigner verify failed."
    exit 1
  }
else
  echo "warning: apksigner not found (set ANDROID_HOME); signature not verified." >&2
fi

echo "Signed APK: android/app/build/outputs/apk/release/yakumo.apk"
