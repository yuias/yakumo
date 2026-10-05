# やくも (Yakumo) — EN↔JA 音声翻訳

Android 向けの英語↔日本語音声翻訳アプリ。既定では、初回にモデルをダウンロードした後は音声認識と機械翻訳が**完全オフライン**で動き、訳文は OS のテキスト読み上げ(TTS)エンジンで発話する。オプトインのオンラインモードでは、1 ターンをクラウドの speech-to-speech モデル(OpenAI Realtime または Gemini Live Translate)に任せることもできる。推論の中核は Rust で書かれており、UniFFI 経由で Jetpack Compose の UI から呼び出す。

## 機能

- オフラインの ASR → 翻訳 → 発話パイプライン(推論時にネットワーク不要)
- 言語の自動判定(SenseVoice 多言語モデル)
- VAD によるターン分割(しきい値は調整可能)
- オプトインの低遅延**ストリーミング**英語 ASR(Nemotron)
- OS の TTS エンジンによる発話(再生速度は可変)
- 任意の**オンラインモード**(OpenAI Realtime または Gemini Live Translate による speech-to-speech)— 後述

## ビルド

### 前提

ネイティブライブラリは Gradle ビルド中にコンパイル/取得するため(`.so` はコミットしていない)、以下のツールチェーンが必要:

- **JDK 17 以上**(参照環境は Temurin 21)
- **Android SDK**: platform `android-37`、build-tools、**NDK 27.2.12479018**
- **Rust**(stable)と Android ターゲット:
  ```sh
  rustup target add aarch64-linux-android x86_64-linux-android
  ```
- **cargo-ndk**:
  ```sh
  cargo install cargo-ndk
  ```

### ビルドとインストール

```sh
cd android
./gradlew assembleDebug
```

通常の jniLibs マージの前に、2 つのタスクが追加で走る:

1. **`fetchSherpaPrebuilt`** — sherpa-onnx のビルド済み `.so` をダウンロードし(Gradle ユーザーホームにキャッシュ)、`jniLibs/<abi>/` に展開する。
2. **`cargoBuildRustCore`** — `rust/core` を cargo-ndk でクロスコンパイルし、`jniLibs/<abi>/libtranslatecore.so` を生成する。`build.rs` が `libsherpa-onnx-c-api.so` にリンクするため、fetch の後に実行する必要がある。

どちらも入出力を宣言しているので、変更がなければスキップされる。対応 ABI は **arm64-v8a**(実機)と **x86_64**(エミュレータ)。

APK は `android/app/build/outputs/apk/debug/app-debug.apk` に出力される。`adb install`(または任意の Android CLI)でインストールして起動し、最初の翻訳の前に **Settings → Models → Download missing models** を実行する。モデルごとの行から個別にダウンロード・削除もでき、取得済みのモデルは削除してから再ダウンロードする。実験的なストリーミング ASR モデル(`asr_stream`)はサイズが大きく英語専用なので、この一括ダウンロードには含まれず、**Settings → Experimental**(折りたたみ)の中のモデル行から個別に取得する。

### リリースビルド

署名付きリリース APK(キーストアの準備、ローカル署名、タグ駆動の GitHub Actions ワークフロー)は [README.RELEASE.md](README.RELEASE.md) にまとめてある。

### UniFFI バインディングの再生成

生成済みの Kotlin バインディング(`uniffi/translatecore/translatecore.kt`)はコミットしてある。再生成が必要なのは Rust の公開 API を変えたときだけ:

```powershell
pwsh rust/build-android.ps1
```

## アーキテクチャ概要

```
Kotlin / Compose (app/)        UI, AudioRecord capture, OS TTS, model provisioning, settings
        │  UniFFI + JNA
        ▼
Rust core (rust/core/)         pipeline orchestration, ASR/MT via FFI
        │  C API / dlopen
        ▼
Native libs (jniLibs/)         sherpa-onnx + onnxruntime, libtranslatecore.so
```

- **録音**は Kotlin 側(`AudioRecord`)に置き、**再生**は OS の `TextToSpeech` エンジンを使う。Rust は純粋な変換と推論だけを担うので、テストしやすい。
- **翻訳**は `ort` crate で NLLB の ONNX モデルを実行する。`ort` は sherpa-onnx に同梱の `libonnxruntime.so` を `dlopen` して使う。

エンジンの抽象化、VAD/エンドポイントのパラメータ、モデル配布、FFI の規約などの全体像は [docs/architecture.md](docs/architecture.md) を参照。

## モデル

モデルは APK に**同梱しない**。初回使用時に `android/app/src/main/assets/models.json` のマニフェストに従って、アプリの内部ストレージにダウンロードする(一部の OEM では NDK の生の `open()` が外部ストレージで拒否されるため、内部ストレージが必須)。

| ID | モデル | 役割 | 配布元 | おおよそのサイズ |
|---|---|---|---|---|
| `vad` | Silero VAD | 音声区間検出 / ターン分割 | [k2-fsa releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) | ~2 MB |
| `asr` | sherpa-onnx SenseVoice int8 (zh-en-ja-ko-yue) | ASR + 言語判定 | [k2-fsa releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) | ~230 MB |
| `nllb` | NLLB-200-distilled-600M(ONNX、量子化、merged decoder) | 翻訳 | [Xenova on HF](https://huggingface.co/Xenova/nllb-200-distilled-600M) | ~865 MB(encoder + decoder + tokenizer) |
| `asr_stream` | sherpa-onnx Nemotron streaming EN 0.6B int8 | 実験的な低遅延英語 ASR | [k2-fsa releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) | ~464 MB |

既定の認識器は SenseVoice(`asr`)。英語と日本語の両方を扱え、翻訳方向の自動判定に使う言語タグも返す。ストリーミングの Nemotron モデル(`asr_stream`)はオプトインの代替で、言語の対応範囲と引き換えに低遅延のライブ途中結果を得られる。ただし**英語専用**で言語タグも出さないため、EN→JA の流れにしか向かない。有効にするかどうかは **Settings → Experimental** で選ぶ。

発話には端末の OS TTS エンジンを使うので、音声合成モデルはダウンロードしない。NLLB は多言語モデルだが、アプリ内の言語ペアは EN↔JA なので、使うのはその 2 方向だけ。

## オンラインモード(任意)

既定はオフラインで、アカウントは不要。より低い遅延が欲しい場合は **Settings → Online translation** でオンライン翻訳を有効にし、プロバイダを選ぶ:

- **OpenAI Realtime**(`gpt-realtime-translate`)— アプリが API キーから短命のエフェメラルトークンを発行し、それでストリームを開く。
- **Gemini Live Translate**(`gemini-3.5-live-translate-preview`)— キーで WebSocket を直接認証する(トークン交換なし)。

API キーはプロバイダごとに保持し、Android Keystore のマスターキーのもとで Tink AEAD により端末内で暗号化する。選んだプロバイダのキーを貼り付けて **Test connection** を押すと、キーとネットワークを検証する(OpenAI はエフェメラルトークンの発行、Gemini は軽量な models 呼び出し)。マイク横のトグルで実行中のエンジンを切り替える。既定はオフラインのパイプラインのまま。オンライン中は音声が選んだプロバイダに送信され、そのキーに課金される。

どちらのエンジンも、1 ターンを原文の書き起こしとストリーミングされる訳文として表示し、翻訳された音声を再生する。ターンの区切りは、プロバイダが終了シグナルを送る場合はそれ(OpenAI)、訳文の文末句読点(継続的にストリームする Gemini で必要)、または無音の間で決まる。無音の長さは **Settings → Online translation** の「Pause to split a turn」で調整できる。

## ライセンス

未定。
