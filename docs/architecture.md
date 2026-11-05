# Yakumo — Architecture

> Describes the current implementation (as of 2026-11).
> Build steps are in `README.md`; release steps are in `README.RELEASE.md`.

---

## 1. Overview

Yakumo translates speech between English and Japanese. By default all inference runs **on the device**.
The inference core is the Rust crate `translatecore`, called from the Jetpack Compose UI through UniFFI + JNA.
Online translation is optional: it hands a whole turn to a cloud speech-to-speech model instead.

```
Kotlin / Compose (android/app)   UI, AudioRecord capture, OS TTS, model provisioning, settings
        │  UniFFI + JNA
        ▼
Rust core (rust/core)            VAD / ASR orchestration and FFI, translation via llama.cpp
        │  C API / static linking
        ▼
Native libs (jniLibs)            sherpa-onnx + onnxruntime, libtranslatecore.so (llama.cpp linked statically)
```

Audio I/O (`AudioRecord` / `TextToSpeech` / `AudioTrack`) stays on the Kotlin side.
The Rust side only does pure conversion and inference, which keeps it testable and portable (for example to iOS).

---

## 2. Translation engine abstraction

Offline and online implementations sit behind the interface in `translate/SpeechTranslator.kt`.
The UI (`ui/session/NewSessionScreen.kt`) only picks an implementation with a toggle; displaying and saving turns is shared.

| Implementation | Files | Contents |
|---|---|---|
| `OfflineTranslator` | `translate/OfflineTranslator.kt` | Silero VAD v6 → SenseVoice ASR (or streaming Nemotron 3.5) → llama.cpp translation (GGUF) → OS TTS |
| `OpenAiTranslator` | `translate/OpenAiTranslator.kt` + `OpenAiRealtime.kt` | OpenAI Realtime translations (S2S) |
| `GeminiTranslator` | `translate/GeminiTranslator.kt` + `GeminiLive.kt` | Gemini Live Translate (S2S) |
| `OfflineConversationTranslator` | `translate/OfflineConversationTranslator.kt` | Conversation mode. Runs `OfflineTranslator` with hooks that take the direction from the Speaking state, and speaks through `TtsPlayback` |
| `OnlineConversationTranslator` | `translate/OnlineConversationTranslator.kt` | Conversation mode. Runs one `OpenAiTranslator` / `GeminiTranslator` per direction (two in total) on one microphone, and plays through `PcmPlayback` |

`RealtimeSupport.kt` holds the parts shared by both online implementations:
`RealtimeTurnAssembler` (assembles source / translation deltas into one row) and
`RealtimeAudioSink` (plays 24 kHz PCM16 through `AudioTrack`).
In Conversation, translated audio goes to `audio/PcmPlayback` instead of `RealtimeAudioSink` (swapped in through `audioOut`).

A session has one of two modes (§5). **Listen** (default) uses the first three implementations.
**Conversation** picks `OfflineConversationTranslator` or `OnlineConversationTranslator` as a
`ConversationTranslator` (a `SpeechTranslator` with `setSpeaking`), and `audio/` switches the output device (§5a).

---

## 3. Offline path

```
AudioRecord (VOICE_RECOGNITION, 16 kHz mono PCM16, ~100 ms frames)
  → VAD          Silero VAD v6 (Rust: vad_load / vad_accept / vad_flush)                 → segments
  → ASR          SenseVoice int8 (with language tag) or streaming Nemotron 3.5           → transcript
  → Translation  GGUF translation LLM (llama.cpp: PLaMo 3 Translate 0.8B or 2B)          → translation
  → Speech       OS android.speech.tts.TextToSpeech
  → Storage      filesDir/sessions/<id>.json
```

### Segmentation (VAD)

Segmentation is delegated to Silero VAD v6.2.3.
v6 has the same interface as v5 (512-sample windows at 16 kHz) and the same probability scale,
so the Rust code is unchanged from v4.
The four `VadParams` values are all adjustable with sliders in Settings:

| Parameter | Default | Meaning |
|---|---|---|
| `threshold` | 0.5 | Speech probability threshold |
| `minSilenceMs` | 500 | Trailing silence that closes a segment |
| `minSpeechMs` | 250 | Shorter utterances are dropped |
| `maxSpeechMs` | 15000 | Forced cut for long utterances |

The defaults are the values used with v4 (Silero's own default threshold is still 0.5).
They have not been re-tuned for v6 because no comparison recordings exist yet. The next step is to record
a few clips in quiet and noisy rooms and compare v4 and v6 segmentation. Saved user settings are not reset.

### ASR (two exclusive modes)

Settings → Speech recognition selects **Segmented** or **Streaming** (setting `asrMode`).
Only one is used at a time; the engine of the other is unloaded (see below).

- **Segmented** (default): VAD → **SenseVoice int8** (zh-en-ja-ko-yue). It returns a `<|en|>` / `<|ja|>` language tag,
  which is used directly to choose the translation direction. Most accurate.
- **Streaming**: **Nemotron 3.5 ASR streaming 0.6B** (`asr_stream`, multilingual).
  It produces partial results while the user is speaking, but its Japanese accuracy is lower than SenseVoice and it
  **returns no language tag** (direction detection: §5). Turns are split by sherpa's endpoint rules
  (`EndpointParams` rule1/2/3, defaults 2.4 / 1.2 / 20.0 s), not by the VAD.
  Kotlin always passes `"auto"` as the stream option `language`: one stream handles both speakers,
  and a fixed language would misrecognize the other speaker.

### Translation

llama.cpp (the `llama-cpp-2` crate) is linked statically into `libtranslatecore.so` and runs a GGUF translation LLM on the CPU
(`rust/core/src/mt.rs`). The user picks one model in Settings → Translation (setting `mtModel`, default 0.8B):

| Model | Characteristics |
|---|---|
| PLaMo 3 Translate 0.8B (Q4_0) | Small and fast |
| PLaMo 3 Translate 2B (Q4_0) | More accurate. Larger and slower |

Q4_0 is used because llama.cpp's Arm CPU backend has optimized kernels for it; the model card reports EN↔JA accuracy
close to Q4_K_M at a slightly smaller size.

- **Only the GGUF architecture `plamo3` is accepted.** Switching to another quantization or size of PLaMo 3 Translate
  only needs a manifest edit. Any other architecture fails at load time, as does a vocabulary without the
  `<|plamo:tag|>` / `<|plamo:bos|>` control tokens.
- **The prompt is built directly in PFML3**, as the model card recommends for applications, instead of through a chat
  template: `<|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input<|plamo:msg|><source>`
  `<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|><en|ja><|plamo:msg|>`. The source language is omitted, so a
  misdetected source still translates. llama.cpp's plamo2 tokenizer does not insert BOS, so the BOS token is prepended.
- **Tokenizer override**: the GGUF declares the `plamo3` tokenizer, which the bundled llama.cpp predates
  (ggml-org/llama.cpp#30045) and rejects. The model is loaded with `tokenizer.ggml.model` overridden to `plamo2`, the same
  Unigram tokenizer without plamo3's pre-segmentation (only runs of 4+ identical characters or 2+ spaces split
  differently). Remove the override once `llama-cpp-2` bundles a llama.cpp with the plamo3 tokenizer.
- **Generation stops** at an end-of-generation token or at `<|plamo:tag|>` / `<|plamo:bos|>`, which the model emits
  after the translation.
- **Decoding is greedy** (temperature 0, as in the model card), so the same input gives the same output.
- **Generation length** is capped at `min(3 × source tokens + 32, 512)`, with a 1024-token context. The cap stops runaway
  generation. An input whose prompt leaves no room for output is rejected with `input too long for translation`.
- **Streaming**: `mt_translate_streaming` calls the callback after each generated token with the accumulated text so far
  (not a delta). A token piece can end in the middle of a UTF-8 character, so bytes are held until they form a complete
  character. This lets the UI show the translation before it is final.

### CPU requirement (dot-product gate)

arm64 ggml-cpu is built with `armv8.2-a+dotprod` (§8). On a CPU without dotprod
(Cortex-A53 / A73 generation, for example) the first dotprod instruction raises SIGILL and kills the process.
So every MT entry point checks the `ASIMDDP` bit of `AT_HWCAP` before initializing `LlamaBackend` or loading a model
(`cpu_supported()`; the pure bit check is tested on the host).
If the CPU lacks it, `mt_supported()` returns false; Kotlin shows a note in Settings and refuses to start offline
(Online mode still works). x86_64 is a baseline build, so the gate always passes there.

### Model residency and unloading

The recognizer, detector, and translation model are held in process-global Rust state (`OnceLock<Mutex<Option<Engine>>>`).
Each loads once and is reused by later calls.

- **Translation**: only the weights (the GGUF, memory-mapped) stay resident. The LLM context (KV cache) is created and
  dropped per request. When a different model is requested, the old weights are freed **before** the new load, so two
  models are never in memory at once.
- **Unload API**: `asr_unload` / `asr_stream_unload` / `vad_unload` / `mt_unload`. Each takes the engine mutex and sets it
  to `None`, so a running recognition or translation finishes before the engine is freed. The next use loads it again lazily.
- **When unloading happens**: at session start (the unused ASR engine), when the ASR mode or translation model changes in
  Settings, and when a model is deleted (the engine using it is unloaded first).
- **When loading happens**: an offline session starts loading the translation model in the background when it starts.
  The load overlaps the first utterance; translation waits on the engine mutex. The preload button in Settings loads only
  what the current selection needs (VAD + SenseVoice for Segmented, Nemotron for Streaming, plus the translation model in both cases).

### Speech output

Speech uses the OS TTS engine (`Locale` + `setSpeechRate`).
The deciding reason is that the OS handles kanji grapheme-to-phoneme conversion for Japanese, so no on-device TTS model is shipped.
Android 11+ package visibility requires a `<queries>` entry for `TTS_SERVICE` in `AndroidManifest.xml`.
Offline Conversation does not share Listen's `TextToSpeech`; `TtsPlayback` owns its own instance (§5a),
so that the audio attributes used for output routing do not affect Listen.

---

## 4. Online path (optional)

The provider and key are set in Settings → Online translation; the toggle next to the microphone switches to online.
The toggle is enabled only when **the network is available and the selected provider has a key** (default is Offline).
Network state is watched with `registerDefaultNetworkCallback`.

| | OpenAI Realtime | Gemini Live |
|---|---|---|
| Model | `gpt-realtime-translate` | `gemini-3.5-live-translate-preview` |
| Auth | Issues an ephemeral token (600 s) with `POST /v1/realtime/translations/client_secrets`, then connects | Connects the WebSocket directly with the API key |
| Audio sent | 24 kHz PCM16 (linearly resampled on devices that only capture 16 kHz) | 16 kHz PCM16 |
| Turn end | Provider's end-of-turn signal | Sentence-final punctuation in the translation |
| Fallback (both) | Silence of `onlineIdleGapMs` (default 1200 ms) closes the turn | Same |

Capture uses `VOICE_COMMUNICATION` (hardware AEC reduces pickup of the translated audio). In Conversation, both sessions share one capture (§5).
Audio is sent in **40 ms chunks** (100 ms offline), read from `AudioRecord` directly into a `ByteArray`.

**Pre-connect audio buffer**: capture starts **before** the connection is ready (OpenAI: token + WebSocket; Gemini: WebSocket + setup).
Until the socket is ready, audio goes into `PreConnectAudioBuffer` (up to 2 s: 96,000 B for OpenAI and 64,000 B for Gemini after resampling;
the oldest data is dropped beyond that). Once ready, the buffered audio is sent in order after the session setup, and later audio is sent directly.
This keeps the start of speech that was spoken while connecting. If the connection fails, `teardown()` also stops the capture.
Translated audio is played through `AudioTrack`; OS TTS is not used.
Keys are stored per provider, **encrypted with Tink AEAD under an Android Keystore master key**, and saved
base64-encoded in SharedPreferences (the plaintext never reaches the prefs).

**Listen limitation**: one session translates in one direction. The target language is fixed and the source is auto-detected.
Face-to-face two-way conversation is handled by Conversation mode, which runs two of these one-way sessions, one per direction (§5).

---

## 5. Conversation model and direction detection

The supported languages are the `LanguageOption` list (FLORES code + ASR tag + `Locale` + offline capability): six rows, EN / JA / FR / DE / IT / ZH (Simplified).
Only EN and JA have `offline = true`; FR / DE / IT / ZH are online only
(SenseVoice cannot recognize FR/DE/IT, and the translation core only knows `eng_Latn` / `jpn_Jpan`).
The UI holds a `Conversation` of "my language (fixed in Settings, EN / JA)" and "partner language (per session)",
which is reduced to `LanguagePair` + `InputMode` at the translation engine boundary.

### Session modes

| | Listen (default) | Conversation |
|---|---|---|
| Partner language | Auto (null) or a specific language; only offline-capable languages | Always a specific language (no Auto). Online-only languages cannot be picked while offline |
| Direction | Detected per utterance (below) | Speaking button state (no detection) |
| Output | Media volume (OS TTS / `AudioTrack`) | Earphones or speaker depending on the listener (§5a) |
| API level | Any | **API 31+ only** |

A saved Conversation mode read on a device below API 31 falls back to Listen, so the app never stays in a mode it cannot start.

### Direction detection in Listen

The SenseVoice language tag takes priority. When there is no tag or it is unknown, a script heuristic decides.
Nemotron 3.5 (Streaming) is multilingual but returns no language tag, so Streaming always uses the heuristic
(text containing Japanese characters is Japanese; anything else is the other language).
Only Japanese can be told apart by script, so Listen's partner language is limited to the offline-capable languages (EN / JA).

### Direction in Conversation

The speaker is a `Speaker` (`ME` / `PARTNER`).
While **Speaking is off**, the microphone is treated as the partner (`PARTNER`: partner → my language);
while it is **on**, as me (`ME`: my → partner language). A session always starts with Speaking off.

- The **Speaking button** is a `PttLatch`: a **tap latches** it on (until the next tap), and a **hold** keeps it on only while pressed.
  Pressing for 400 ms or longer from off counts as a hold (off on release); a shorter press is a tap (latch).
  A tap while latched turns it off on release. The latch resets when recording stops.
- The listener of a translation is the opposite of the speaker: `ME`'s translation goes to `Audience.PARTNER`, `PARTNER`'s to `Audience.ME`.
- `ConversationInputGate` (capture thread only) decides the direction in one place. The first chunk after a switch carries
  the previous speaker. With it, offline Segmented flushes the VAD of the previous direction before moving on
  (Streaming emits the finalized text in the previous direction, then resets the recognizer), and online appends
  silence (`SWITCH_TAIL_MS` = 800 ms) to the previous direction's session so the provider closes the open turn.
- Turn `id`s are numbered per speaker, so `directionalTurnId` makes `ME` odd and `PARTNER` even to keep list keys unique.
  Turns carry `speaker`, and the saved session stores `speaker` and `mode`.
  Turns without `speaker` (older versions / Listen) are shown as before.

### Two online sessions

Online Conversation opens one realtime session per direction with the same provider, **two at once**
(`OnlineConversationTranslator`). The partner → me session is fixed with `FORCE_A`, the me → partner session with `FORCE_B`.
**Both connect at start** so that pressing Speaking does not wait for a connection; **billing can therefore double**.

- One `AudioRecord` (`VOICE_COMMUNICATION`) is shared, and each chunk is sent only to the session selected by Speaking.
  The idle session receives nothing.
- Audio captured before connecting is held in each session's `PreConnectAudioBuffer` (§4).
- When either session ends or fails, the whole session stops and keeps the first error.
  On stop, it waits up to 5 s for the inner sessions to finish before releasing the router.
- The status line combines both directions, such as `Partner→JA: …` · `You→FR: …`.
- Language codes: OpenAI uses `zh`, Gemini uses **`zh-Hans`** (Gemini's supported-language table has no plain `zh`; see `floresToGeminiLang`).
  Other languages use the same code for both.

### Offline Conversation

Offline runs `OfflineTranslator` with `OfflineConversationHooks`. `conversationDirection` takes the direction from the
Speaking state, so auto-detection is not used (in both Segmented and Streaming).
Each VAD segment / Streaming finalized text is tagged with the direction at capture time and passed downstream.
On a direction switch, Streaming emits the finalized text so far in the previous direction and resets the recognizer with `asrStreamReset`.
Because of the language support of SenseVoice / Nemotron and the translation models, offline Conversation is **EN ↔ JA only**.
Starting offline with FR/DE/IT/ZH selected is refused, and the app points to Online.

---

## 5a. Audio output routing (Conversation)

In Conversation, the translation I listen to (of the partner's speech) and the one the partner listens to (of my speech)
go to different outputs: mine to **earphones**, the partner's to the **built-in speaker**.
Listen is unchanged (audio mode stays `MODE_NORMAL`).

### `audio/` package

| File | Role |
|---|---|
| `AudioRouter.kt` | `AudioRouter` interface (`begin` / `routeTo` / `playbackAttributes` / `builtinMic` / `end`), `Audience` / `ResolvedOutput`, and the router registry `AudioRouters` |
| `CommunicationDeviceRouter.kt` | Implementation for API 31+: `MODE_IN_COMMUNICATION` + `setCommunicationDevice` |
| `PcmPlayback.kt` | Plays online translated audio (24 kHz PCM16) through `AudioTrack`. Implements `EchoGate` |
| `TtsPlayback.kt` | Speaks offline translations with its own `TextToSpeech`. Implements `EchoGate` |

Routing is behind `AudioRouter` so that it can be replaced without touching the engines if the assumptions below fail on real devices.

### How it works

1. At session start, `begin()` sets `AudioManager.mode` to `MODE_IN_COMMUNICATION` (permission: `MODIFY_AUDIO_SETTINGS`)
   and checks whether earphones are connected.
2. `routeTo(audience)` is called per utterance (segment). For `ME` it selects the first earphone found in priority order
   (wired headset → wired headphones → USB → BLE → Bluetooth SCO → hearing aid); for `PARTNER`, the built-in speaker,
   via `setCommunicationDevice`. Devices are queried from the OS on every call and not cached. After a switch it waits
   `ROUTE_SETTLE_MS` (200 ms); without the wait, the start of the next utterance plays on the old device.
3. Playback is **one segment at a time**. Translations for me and for the partner alternate and never play simultaneously
   (whichever reaches the head of the queue first plays first). Online creates a new `AudioTrack` per utterance on the selected device.
4. `AudioTrack` and TTS always use `router.playbackAttributes` (`USAGE_VOICE_COMMUNICATION`). Normal media attributes do not follow the communication device.
5. At the end, `end()` clears the communication device and restores `MODE_NORMAL`. `end()` is idempotent and callable from any thread.
   Several safeguards call it: the engine's `finally`; a new session first ends any leftover router; and the service's `onDestroy`
   ends only its own generation's router via `releaseActive` (the generation check keeps it from ending a newer session's router).
6. Capture is pinned to the built-in microphone (`builtinMic()`), so it does not switch to a Bluetooth headset's SCO input.
7. Communication playback follows the call volume, so while recording in Conversation the screen sets `volumeControlStream` to `STREAM_VOICE_CALL` and the volume keys control that volume.

**Without earphones**, `begin()` reports that the outputs cannot be separated, and the screen shows a note.
`routeTo` selects the built-in speaker in both directions, so both play on the speaker
(`ME` also falls back to the speaker when no earphone is found).

### Echo gate

If the microphone picks up translated audio from the speaker and feeds it into the other direction, translations trigger more translations. The echo gate prevents this.

- `PcmPlayback` / `TtsPlayback` expose `EchoGate.speakerBusy`. It is true only **while audio actually plays on the speaker**
  (plus `ECHO_TAIL_MS` = 300 ms for reverberation). When `routeTo` returns `SPEAKER`, or the switch failed and the output is unknown,
  playback counts as speaker playback (erring on the side of muting).
- The capture thread decides every chunk with `ConversationInputGate.decide(speaking, speakerBusy)`.
  Only the **partner (`PARTNER`) direction** is muted. Without earphones the partner's translation also plays on the speaker,
  so the partner direction is muted while the speaker plays. **It never overrides Speaking (my direction).**
- Muted chunks are replaced with silence (zeros) rather than dropped, so the audio timeline given to the provider / VAD stays continuous.
- TTS skips locales without language data up front (no routing switch and no muting for them).

### Behavior on Stop

Stopping Conversation discards queued and playing translated audio (the `PcmPlayback` / `TtsPlayback` queues) immediately.
The translated text was saved when it was shown, so it is kept. Listen plays the last utterance to the end.

### Assumptions not verified on a device

Routing has been checked **only by building and unit tests, not on a real device**. These are still assumptions:

- During `MODE_IN_COMMUNICATION`, `setCommunicationDevice` can switch between wired / USB / Bluetooth earphones and the built-in speaker per utterance.
- `ROUTE_SETTLE_MS` (200 ms) is enough to keep the start of audio after a switch.
- Whether Bluetooth earphones use SCO or LE Audio, and whether input stays on the built-in microphone. A2DP-only devices count as no earphones.
- `TextToSpeech.setAudioAttributes` makes TTS follow the speaker / earphone selection too.
- After a session ends, other apps' playback is unaffected (`MODE_NORMAL` and clearing the communication device reliably take effect).
- Whether Streaming Conversation loses the last few words when Speaking switches (if it does, send silence in the old direction before `asrStreamReset`).

On a device, check in order that the mode is restored with wired / Bluetooth / no earphones / below API 31 / abnormal exit (task removal, Stop, one session dropping).

---

## 6. Model provisioning

Models are not bundled in the APK. On first use they are downloaded into app-internal storage (`filesDir`)
according to the manifest `assets/models.json`.

- An entry has `id` / `dir` / `checkFile` and either `archive` (tar.bz2) or `files[]` (individual URLs).
  `size` / `sha256` are optional; Hugging Face URLs pin a revision.
- tar.bz2 is extracted with Apache Commons Compress (Java's `ZipInputStream` does not support bz2).
- Downloads follow redirects manually (GitHub / Hugging Face CDN hops) and write atomically (`.part` → rename).
  Extraction also goes to a staging directory and is renamed on completion, so an interruption never leaves a partial model.
- **Integrity check**: SHA-256 is computed while writing (no second read), and size and sha256 are compared with the
  manifest before the rename (`integrityError`). On mismatch the `.part` is deleted and the download fails.
  Only verified files are renamed, so `isPresent` is a plain existence check. This prevents sherpa from crashing on a wrong file.
- **Cleaning up old models**: the manifest's `obsoleteDirs` lists directories of replaced models
  (old VAD, old Nemotron, old translation models). Settings shows their size and can delete them.
  To replace a model, edit `dir` (a new one, so existing installs download again), `checkFile`, and `files[]`,
  and add the old `dir` to `obsoleteDirs`.

| ID | Model | Use | Approx. size |
|---|---|---|---|
| `vad` | Silero VAD v6.2.3 | Turn segmentation (Segmented) | ~2 MB |
| `asr` | SenseVoice int8 (zh-en-ja-ko-yue) | ASR + language ID (Segmented) | ~230 MB |
| `asr_stream` | Nemotron 3.5 ASR streaming 0.6B int8 | Low-latency multilingual ASR (Streaming) | ~475 MB |
| `mt_plamo3_0_8b` | PLaMo 3 Translate 0.8B (GGUF, Q4_0) | Translation (default) | ~548 MB |
| `mt_plamo3_2b` | PLaMo 3 Translate 2B (GGUF, Q4_0) | Translation (more accurate, more RAM) | ~1.7 GB |

In Settings → Models, entries needed by the current ASR mode and translation model are marked "· in use".
The "Download models for current settings" button downloads the missing ones
(Segmented: `vad` + `asr` + translation model; Streaming: `asr_stream` + translation model).
Each model can also be deleted in Settings (not while recording); the native engine using it is unloaded first
(the GGUF is memory-mapped, so it must not be deleted while loaded).

> **Internal storage is required**: on some OEM builds (ColorOS / OxygenOS, for example) a raw NDK `open()` on
> external storage (`Android/data`) fails with EACCES. Java's `File.exists()` succeeds there while `listFiles()`
> is denied, so models must live under `filesDir`.

---

## 7. Persistence and settings

- **Sessions**: `data/SessionStore.kt`. One file per session at `filesDir/sessions/<id>.json`.
  There are few sessions and no schema is needed, so kotlinx.serialization JSON is used instead of a database.
- **Settings**: `data/Settings.kt` (SharedPreferences). Speech rate / auto-speak / ASR mode (`asrMode`: `SEGMENTED` / `STREAMING`) /
  translation model (`mtModel`: `PLAMO3_0_8B` / `PLAMO3_2B`, default `PLAMO3_0_8B`; unknown values, including the removed `LFM2` / `HYMT2`, fall back to it) /
  the four VAD parameters / three endpoint rules / online provider, idle gap, and encrypted keys.
  `asrMode` uses the saved value if present, otherwise migrates the old `streamingAsr` boolean,
  otherwise defaults to `SEGMENTED`. Saving `asrMode` deletes the old key.
- **Session mode**: `SessionMode` (`LISTEN` / `CONVERSATION`) and a separate partner language per mode
  (Listen allows Auto; Conversation is always a specific language). Unknown values fall back to `LISTEN`,
  as does a saved Conversation read below API 31.
- **Key encryption**: `data/SecureKeyStore.kt` (Tink AEAD).
  `EncryptedSharedPreferences` is not used because it was deprecated in 2026.

---

## 8. Native libraries generated / fetched at build time

Every `.so` in `jniLibs/` is either a build output or an upstream binary, so none is committed; Gradle produces them
(excluded by `android/app/.gitignore`). Supported ABIs are **arm64-v8a** (devices) and **x86_64** (emulator).

1. **`fetchSherpaPrebuilt`** downloads the sherpa-onnx release tar.bz2 (cached in the Gradle user home) and extracts
   `libsherpa-onnx-{c,cxx}-api.so` and `libonnxruntime.so` into `jniLibs/<abi>/`.
2. **`cargoBuildRustCore`** cross-compiles `rust/core` with cargo-ndk and writes
   `jniLibs/<abi>/libtranslatecore.so`.

The order matters: `build.rs` links `libsherpa-onnx-c-api.so`, so 1 runs before 2.
`preBuild` depends on 2, so the `.so` files exist before AGP merges jniLibs. Both tasks declare inputs and outputs and
are skipped when nothing changed.

The required toolchain (JDK 17+ / Android SDK / NDK 27.2.12479018 / Rust + cargo-ndk / CMake + Ninja) is listed under
[Prerequisites in README.md](../README.md#前提).

### Building llama.cpp

The `llama-cpp-2` crate builds llama.cpp with CMake and links it statically into `libtranslatecore.so`.

- **CMake 3.22+ and Ninja** are required. `rust/core/.cargo/config.toml` sets `CMAKE_GENERATOR=Ninja`
  (cmake-rs passes no `-G` for Android targets, and on Windows it would pick a Visual Studio generator, which cannot drive the NDK).
  The Android SDK `cmake;<ver>` package ships both; `tools/build-rust-core.ps1` adds the newest one to `PATH` when `cmake` is not found.
  CI has a step that checks CMake and Ninja are present.
- **cargo-ndk runs once per ABI** (the Gradle `cargoBuildRustCore*` tasks and `tools/build-rust-core.ps1`).
  `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod` is set for arm64-v8a only. llama-cpp-sys-2 forwards every `GGML_*` environment
  variable to CMake for all ABIs, so putting it in `.cargo/config.toml` would leak it into x86_64. x86_64 stays at the baseline.
- **`--platform 24`** (matching minSdk) is passed; llama.cpp's CMake build reads it as `ANDROID_PLATFORM`.
- **libc++ is linked statically** (the `android-static-stdcxx` feature of `llama-cpp-2`). sherpa-onnx does not ship
  `libc++_shared.so`, so `libtranslatecore.so` must not have `libc++_shared.so` in its NEEDED entries.
- `[profile.dev.package.llama-cpp-sys-2]` sets `opt-level = 3` so ggml is optimized even in debug builds.
- llama.cpp is an Android-only dependency, so host builds (`cargo test`, `uniffi-bindgen`) need no CMake or C++ toolchain.

### onnxruntime

`libonnxruntime.so` is shipped only as a dependency of sherpa-onnx (extracted by `fetchSherpaPrebuilt`).
The Rust side does not use it directly, and it is not built separately.

---

## 9. FFI conventions and pitfalls

- sherpa's C structs from `c-api.h` are mirrored with `#[repr(C)]` including **every field**.
  Unused embedded configs must be defined too, or the offsets of later fields shift. They are initialized with `mem::zeroed()`.
- Raw pointers are kept resident, so engines use `unsafe impl Send` and free everything in one `Drop`.
- PCM is passed as `ByteArray` (PCM16 LE) and converted from i16 to f32 in Rust (avoiding boxed Float arrays).
- sherpa's native logs go to stderr and do not reach logcat. For diagnosing failures, it is fastest to check file
  readability on the Rust side first and include the result in the error message.
- The UniFFI bindings (`uniffi/translatecore/translatecore.kt`) are committed.
  Regenerate them with `pwsh tools/build-rust-core.ps1` only when the Rust public API changes.

---

## 10. Known limitations

- Offline supports EN↔JA only. FR / DE / IT / ZH are available only in online Conversation (§5).
  Extending offline needs support in ASR (SenseVoice / Nemotron 3.5), the translation model, and OS TTS
- Offline translation needs an arm64 CPU with the dot-product instruction (ASIMDDP). On other devices the gate in §3
  refuses to start offline. **Remaining risk**: ggml-cpu's C++ static initializers run when `libtranslatecore.so` is
  `dlopen`ed, before any Rust code. If one of them executes a dotprod instruction, the gate cannot prevent the crash.
  Static initializers normally do no quantized dot products, but startup on old devices (Cortex-A53 only, for example) has not been confirmed.
- PLaMo 3 Translate is tokenized with llama.cpp's plamo2 tokenizer until the bundled llama.cpp supports plamo3 (§3).
- Streaming mode (Nemotron 3.5) is less accurate for Japanese than SenseVoice.
- Online Listen is one-way (fixed target + auto-detected source). Two-way is handled by Conversation (two sessions).
- Conversation requires Android 12 (API 31) or later and cannot be selected below that.
- Online Conversation connects two sessions at once, so billing can double.
- Conversation output routing (`MODE_IN_COMMUNICATION` + `setCommunicationDevice`) is **not verified on a device** (§5a).
  Clipped audio starts, Bluetooth routing, and TTS following the output device remain unconfirmed until tested on hardware.
- Without earphones, Conversation plays both directions on the speaker. The echo gate mutes the partner direction during
  playback, so partner speech can be missed while a translation plays (my Speaking direction is never muted).
- Stopping Conversation discards queued and playing translated audio (the text is already saved).
- Listen with speaker playback can pick up its own translated audio as echo. Online enables hardware AEC,
  but earphones are recommended.
- Turn splitting is heuristic (VAD parameters / endpoint rules / idle gap), so it depends on the environment and speaking style.
