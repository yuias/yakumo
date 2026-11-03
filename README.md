# Yakumo — EN ↔ JA 音声翻訳

Android 向けの英語 ↔ 日本語音声翻訳アプリケーション。    
デフォルトでは音声認識と翻訳をすべて端末内で処理し、訳文は OS の TTS (Text-to-Speech) で読み上げる。オプションで OpenAI Realtime や Gemini Live Translate を使ったオンライン翻訳にも対応。

## Features

- オフラインでの音声認識 → 翻訳 → 読み上げ (TTS)
- 2つの音声認識モード
  - **Segmented**: 発話ごとに区切って認識する (高精度、デフォルト)
  - **Streaming**: 発話中に認識結果を逐次表示する
- 翻訳モデルは `LFM2-350M-ENJP-MT` (default) と `Hy-MT2-1.8B` から選択可能
- オンライン翻訳モード (option: OpenAI Realtime / Gemini Live Translate)
- 2つのセッションモード
  - **Listen**: 相手の発話を聞き取り、自分の言語に翻訳して読み上げる
  - **Conversation**: 自分と相手が交互に話す対面での双方向会話 (Android 12 以降)

## Building

### Prerequisites

- JDK 17 以上
- Android SDK: platform `android-37`、NDK `27.2.12479018`
- Rust (stable)、Android target、`cargo-ndk`
  ```sh
  rustup target add aarch64-linux-android x86_64-linux-android
  cargo install cargo-ndk
  ```
- CMake 3.22 以上、Ninja

### Build / Install

Windows では `./gradlew` の代わりに `.\gradlew.bat` を実行する。  
署名付きのリリースビルド手順は [README.RELEASE.md](README.RELEASE.md) を参照。

```sh
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/yakumo.debug.apk
```

- Gradle ビルド時に `sherpa-onnx` の prebuilt binary をダウンロード、`llama.cpp` を含む Rust で記述しているコア部分を `cargo-ndk` でクロスコンパイルする。
- 対応 ABI は `arm64-v8a`, `x86_64`.
- CMake, Ninja は Android SDK の `cmake;<ver>` package を利用する場合は `<ANDROID_HOME>/cmake/<ver>/bin` を `PATH` に通す。
- UniFFI の Kotlin bindings (`uniffi/translatecore/translatecore.kt`) はこの Repository に含まれている。Rust 側の Public API を変更した場合のみ `pwsh tools/build-rust-core.ps1` で再生成する。
- ネイティブライブラリのビルド構成や内部設計の詳細は [docs/architecture.md](docs/architecture.md) を参照。

## Usage

1. **Settings** で音声認識モード (Segmented / Streaming) と翻訳モデルを選択する
2. **Settings > Models > Download models for current settings** から必要なモデルをダウンロード
3. New Session でマイクボタンを押して話す

オフライン翻訳には ARMv8.2 の dot-product 命令に対応した arm64 CPU (Cortex-A55 / A76 世代以降) が必要。非対応端末ではオフライン翻訳を起動できないため、オンラインモードを利用する。

### Listen / Conversation

New Session でモードを切り替えることができる。

- **Listen** (default): 発話ごとに言語を自動判定し翻訳して読み上げる。
- **Conversation**: 自分の言語 (Settings > "Your language") と相手の言語を指定して双方向で会話する。
  1. 相手の言語を選びマイクボタンを押して開始する。開始時は相手の発話待ちとなり、相手の言語 → 自分の言語へ翻訳する。
  2. 自分が話すときは **Speaking** を押す。Tap で ON / OFF が切り替わり、長押し中は押している間だけ ON になる。ON の間は自分の言語 → 相手の言語へ翻訳する。
  3. 相手の発話の訳はイヤホンから、自分の発話の訳は本体スピーカーから出力する。イヤホン未接続時はどちらも本体スピーカーから再生する。

Conversation モードの制約:

- Android 12 (API 31) 以降のみ対応。
- オフラインで利用できるのは日本語 ↔ 英語のみ。フランス語・ドイツ語・イタリア語・中国語 (簡体字) はオンライン時のみ選択可能。
- オンライン時は翻訳方向ごとに計 2つのセッションを同時接続するため、API Costs も 2セッション分かかる可能性がある。
- イヤホンとスピーカーの出力切り替えは実機未検証。端末によっては切り替え直後の音声冒頭が欠けたり Bluetooth のルーティングが想定通りに動作しない可能性がある。

### オンラインモード

1. **Settings > Online translation** で Provider (OpenAI Realtime / Gemini Live Translate) を選び、API key を設定する。
2. **Test connection** で接続を確認する。
3. New Session 画面のマイク横にあるトグルで Online に切り替える。

- オンライン時は音声データを選択した Provider に送信する。
- API key は端末内で暗号化して保存される。
- 発話が途中で切れたり、なかなか区切られない場合は、Settings > "Pause to split a turn" で区切り判定の無音時間を調整できる。

## モデル

モデルは APK には同梱していないためアプリ内でダウンロードする (`android/app/src/main/assets/models.json`).  
読み上げには OS の TTS を使うので音声合成モデルは使用しない。

| ID | モデル | 用途 | サイズ | License |
|---|---|---|---|---|
| `vad` | [Silero VAD](https://github.com/snakers4/silero-vad) v6.2.3 | 発話区間検出 (Segmented) | ~2 MB | MIT |
| `asr` | [SenseVoice int8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) (sherpa-onnx) | 音声認識・言語判定 (Segmented) | ~230 MB | FunASR model license |
| `asr_stream` | [Nemotron 3.5 ASR streaming 0.6B int8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) (sherpa-onnx) | 音声認識 (Streaming) | ~475 MB | OpenMDW-1.1 |
| `mt_lfm2` | [LFM2-350M-ENJP-MT](https://huggingface.co/LiquidAI/LFM2-350M-ENJP-MT-GGUF) (GGUF Q8_0) | 翻訳 (default, EN↔JA 専用) | ~379 MB | LFM Open License v1.0 |
| `mt_hymt2` | [Hy-MT2-1.8B](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF) (GGUF Q4_K_M) | 翻訳 (多言語) | ~1.1 GB | Apache-2.0 |

- Segmented は `vad` + `asr`、Streaming は `asr_stream` を使用する。
- Streaming は認識の途中経過を表示できる反面、日本語の認識精度は Segmented に劣る。
- 翻訳モデルは選択中の 1 つのみメモリにロードする。
  - LFM2 は軽量で高速
  - Hy-MT2 は多言語対応だがサイズが大きくメモリ消費量も多い
- LFM Open License v1.0 では条件付きで非商用利用が許諾されている (商用利用は年間売上 1,000 万米ドル未満に制限).

## License

Application 本体は [MIT License](LICENSE).  
各モデルのライセンスは上表の通りでそれぞれの配布元の条件に従う。
