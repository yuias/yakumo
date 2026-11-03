# Release builds

How to build a signed release APK of やくも (Yakumo), locally or with GitHub
Actions. A release build needs the same toolchain as a debug build; see
[Prerequisites in README.md](README.md#前提).

## Local build

### 1. Create the keystore (once)

```sh
keytool -genkeypair -v \
  -keystore android/yakumo.jks \
  -alias yakumo \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

`keytool` ships with the JDK. It asks for a keystore password, a key password,
and a name; any stable name is fine.

> ⚠️ **Back up `yakumo.jks` and both passwords somewhere off this machine.**
> Android accepts an update only when it is signed with the same key, and a lost
> key cannot be recovered.

### 2. Write `keystore.properties` (once)

```sh
cp android/keystore.properties.template android/keystore.properties
```

Fill in the passwords:

```properties
storeFile=yakumo.jks
storePassword=<keystore password>
keyAlias=yakumo
keyPassword=<key password>
```

`storeFile` is relative to `android/` (an absolute path also works).
`android/keystore.properties` and `*.jks` are gitignored.

### 3. Bump the version

In `android/app/build.gradle.kts`:

```kotlin
versionCode = 9        // integer, must increase for every shipped build
versionName = "0.8.0"  // shown in Settings → About
```

### 4. Build

```powershell
pwsh tools/build-release.ps1    # Windows
```

```sh
sh tools/build-release.sh       # Linux / macOS
```

The script checks that `android/keystore.properties` has all four values and
that the keystore it names exists, and lists anything missing. It then runs
`:app:assembleRelease`, fails if the APK is not signed, and prints the signing
certificate with `apksigner` (found through `ANDROID_HOME`).

Output: `android/app/build/outputs/apk/release/yakumo.apk`

### 5. Install

```sh
adb install -r android/app/build/outputs/apk/release/yakumo.apk
```

## GitHub Actions

`.github/workflows/release.yml` builds and signs the same APK.

### Repository secrets

Set these under **Settings → Secrets and variables → Actions**:

| Secret | Contents |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `yakumo.jks`, base64-encoded on a single line |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias (`yakumo`) |
| `RELEASE_KEY_PASSWORD` | Key password |

Encode the keystore and paste the output as the secret value:

```sh
base64 -w0 android/yakumo.jks                 # Linux / Git Bash
```

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("android\yakumo.jks"))
```

### Running it

- **Tag push**: `git tag v0.8.0 && git push origin v0.8.0`. The workflow attaches
  `yakumo-<tag>.apk` to the GitHub Release for that tag.
- **Manual run**: *Actions → Release build → Run workflow*. The APK is uploaded
  only as a workflow artifact (`yakumo-release-apk`), which works as a dry run.

Tag the commit that contains the version bump, so the tag and `versionName` agree.

## Notes

- **Unsigned fallback**: Gradle does not fail when a signing value is missing; it
  produces `yakumo-unsigned.apk`, which Android refuses to install. The
  build scripts treat that as an error. A blank value counts as missing.
- **Environment variables**: instead of `keystore.properties`, Gradle also reads
  `YAKUMO_KEYSTORE_FILE`, `YAKUMO_KEYSTORE_PASSWORD`, `YAKUMO_KEY_ALIAS`, and
  `YAKUMO_KEY_PASSWORD` (values in the file take precedence). CI uses these;
  the local build scripts require the file.
- **Version code**: Android installs an APK over an existing one only if its
  `versionCode` is higher.
- **Build hash**: Settings → About shows the short git hash of the build
  (`BuildConfig.GIT_HASH`), so a build from an unexpected commit can be identified.
- **Debug and release side by side**: the debug build uses the application id
  suffix `.debug` and the label `Yakumo (debug)` / `やくも (debug)`, so both can be
  installed at once.
- **No shrinking**: the release build has `isMinifyEnabled = false`. Most of the
  APK size is native libraries; models are downloaded at runtime.
- **Git LFS**: `gradle-wrapper.jar` is stored in Git LFS (see `.gitattributes`), so
  the workflow checks out with `lfs: true`.

## Troubleshooting

**`Keystore was tampered with, or password was incorrect`.**
`storePassword` is wrong, or `storeFile` does not point at the keystore.

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.**
The installed app was signed with a different key. Uninstall it first with
`adb uninstall app.rly3h.yakumo`; this also deletes its downloaded models and
saved sessions.

**`INSTALL_FAILED_VERSION_DOWNGRADE`.**
The installed app has an equal or higher `versionCode`. Bump it, or uninstall first.

**`cargo: command not found` / NDK errors.**
The release build compiles the Rust core like any other build. Check the
[prerequisites](README.md#前提): Rust with the Android targets, `cargo-ndk`, and
NDK 27.2.12479018 reachable through `ANDROID_NDK_HOME`.
