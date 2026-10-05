import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

buildscript {
  repositories { mavenCentral() }
  // Reused by the fetchSherpaPrebuilt task to extract the release tar.bz2,
  // mirroring the in-app extraction (same library, same version).
  dependencies { classpath("org.apache.commons:commons-compress:1.28.0") }
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Short git hash of the build, surfaced in Settings → About. "unknown" if git
// is unavailable. Uses providers.exec so it stays configuration-cache safe
// (a raw ProcessBuilder at configuration time is rejected by the CC).
fun gitHash(): String =
  try {
    providers.exec {
      commandLine("git", "rev-parse", "--short", "HEAD")
      isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { "unknown" }
  } catch (e: Exception) {
    "unknown"
  }

// Release signing credentials, read from keystore.properties (local builds,
// gitignored) or environment variables (CI). When none are present the release
// build is left unsigned, so debug builds and secret-less CI runs still succeed.
val keystoreProps = Properties().apply {
  val f = rootProject.file("keystore.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
  (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }
val releaseStorePath = signingValue("storeFile", "YAKUMO_KEYSTORE_FILE")

// The ABIs the Rust core and the sherpa prebuilts are produced for. Declared
// here so the packaging filter below and the native build tasks further down
// cannot drift apart.
val androidAbis = listOf("arm64-v8a", "x86_64")

android {
    namespace = "app.rly3h.yakumo"
    // AndroidX 2026.08 (core-ktx 1.19, compose-ui 1.12) requires compiling
    // against 37. targetSdk stays at 36 so no runtime behavior changes ride
    // along with the dependency bump.
    compileSdk = 37
    defaultConfig {
        applicationId = "app.rly3h.yakumo"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "0.5.0"
        buildConfigField("String", "GIT_HASH", "\"${gitHash()}\"")

        // Without this the JNA aar drags libjnidispatch.so in for every ABI it
        // ships. Those stubs are enough for an armeabi-v7a/x86 device to accept
        // the install, and the app then dies on the first call into a core that
        // was never built for it.
        ndk { abiFilters += androidAbis }
    }

    signingConfigs {
        // Only declared when credentials are available; otherwise the release
        // build falls through to unsigned rather than failing configuration.
        if (releaseStorePath != null) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath)
                storePassword = signingValue("storePassword", "YAKUMO_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "YAKUMO_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "YAKUMO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Distinct applicationId so a debug build can sit alongside a
            // release build on the same device (their signing keys differ, so
            // they cannot overwrite each other under one id).
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

// ---- Rust core (libtranslatecore.so) --------------------------------------
// Cross-compiled from ../../rust/core with cargo-ndk rather than committed, so
// the ~190 MB build artifact stays out of the repo. The task drops one .so per
// ABI into jniLibs, where AGP's jniLibs merge picks it up. Requires a Rust
// toolchain + cargo-ndk + the android rustup targets (aarch64-linux-android,
// x86_64-linux-android); see docs/architecture.md §8.
val rustCoreDir = rootDir.parentFile.resolve("rust/core")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

val cargoBuildRustCore = tasks.register<Exec>("cargoBuildRustCore") {
    group = "build"
    description = "Cross-compiles the Rust core into jniLibs via cargo-ndk."
    workingDir = rustCoreDir // cargo-ndk resolves the crate from the cwd Cargo.toml

    inputs.dir(rustCoreDir.resolve("src"))
    inputs.file(rustCoreDir.resolve("Cargo.toml"))
    inputs.file(rustCoreDir.resolve("Cargo.lock"))
    inputs.file(rustCoreDir.resolve("build.rs"))
    outputs.files(androidAbis.map { jniLibsDir.file("$it/libtranslatecore.so") })

    // Exec inherits the parent environment, so cargo-ndk picks up ANDROID_NDK_HOME
    // (or ANDROID_HOME/ndk) on its own — no SDK path to thread through here.
    commandLine = buildList {
        add("cargo"); add("ndk")
        androidAbis.forEach { add("-t"); add(it) }
        add("-o"); add(jniLibsDir.asFile.absolutePath)
        add("build"); add("--release")
    }
}

// ---- Third-party sherpa-onnx prebuilt libs --------------------------------
// libonnxruntime.so + libsherpa-onnx-{c,cxx}-api.so are prebuilt binaries from
// the sherpa-onnx release, fetched at build time rather than committed. They
// must land in jniLibs before cargoBuildRustCore links libsherpa-onnx-c-api.so
// (build.rs) and before AGP merges jniLibs into the APK.
val sherpaVersion = "1.13.8"
val sherpaLibs = listOf(
    "libsherpa-onnx-c-api.so",
    "libsherpa-onnx-cxx-api.so",
    "libonnxruntime.so",
)

val fetchSherpaPrebuilt = tasks.register("fetchSherpaPrebuilt") {
    group = "build"
    description = "Downloads the prebuilt sherpa-onnx native libs into jniLibs."

    val version = sherpaVersion
    val libs = sherpaLibs
    val abis = androidAbis
    val outDir = jniLibsDir.asFile
    // Cached in Gradle user home so `clean` and fresh checkouts don't re-download.
    val cacheDir = File(gradle.gradleUserHomeDir, "caches/sherpa-onnx-prebuilt")

    inputs.property("sherpaVersion", version)
    outputs.files(abis.flatMap { abi -> libs.map { File(outDir, "$abi/$it") } })

    doLast {
        val archive = File(cacheDir, "sherpa-onnx-v$version-android.tar.bz2")
        if (!archive.exists() || archive.length() == 0L) {
            cacheDir.mkdirs()
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                "v$version/sherpa-onnx-v$version-android.tar.bz2"
            logger.lifecycle("Fetching $url")
            // Manual redirect follow: GitHub bounces release assets to a CDN host.
            var current = url
            var hops = 0
            while (true) {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 30_000
                    readTimeout = 60_000
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: error("redirect without Location")
                    conn.disconnect()
                    current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                    check(++hops <= 8) { "too many redirects" }
                    continue
                }
                check(code == 200) { "HTTP $code for $current" }
                val part = File(archive.parentFile, archive.name + ".part")
                conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                if (archive.exists()) archive.delete()
                check(part.renameTo(archive)) { "rename ${part.name} -> ${archive.name} failed" }
                break
            }
        }

        val wanted = abis.flatMap { abi -> libs.map { "jniLibs/$abi/$it" } }.toSet()
        BZip2CompressorInputStream(archive.inputStream().buffered()).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val rel = entry.name.removePrefix("./")
                    if (!entry.isDirectory && rel in wanted) {
                        val dest = File(outDir, rel.removePrefix("jniLibs/"))
                        dest.parentFile.mkdirs()
                        dest.outputStream().use { tar.copyTo(it) }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }
}

// build.rs links against jniLibs/<abi>/libsherpa-onnx-c-api.so, so the prebuilt
// libs must be in place before the Rust core compiles.
cargoBuildRustCore.configure { dependsOn(fetchSherpaPrebuilt) }

// preBuild gates every variant task, so the .so exists before the jniLibs merge.
tasks.named("preBuild") { dependsOn(cargoBuildRustCore) }

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-core")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Rust core (UniFFI): generated Kotlin bindings call into libtranslatecore.so via JNA.
  implementation("net.java.dev.jna:jna:5.19.1@aar")

  // Model provisioning: parse the JSON manifest + extract tar.bz2 archives in-app.
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
  implementation("org.apache.commons:commons-compress:1.28.0")

  // Online mode (OpenAI Realtime): WebSocket transport + on-device API-key encryption.
  implementation(libs.okhttp)
  implementation(libs.tink.android)
}
