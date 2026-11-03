# やくも (Yakumo) — EN↔JA 音声翻訳

Android 向けの英語↔日本語音声翻訳アプリ。音声認識と翻訳は端末内で動き、訳文は OS のテキスト読み上げ(TTS)で再生する。オンラインモードを有効にすると、OpenAI Realtime または Gemini Live Translate で翻訳することもできる。

## 機能

- オフラインの音声認識 → 翻訳 → 読み上げ
- 音声認識は 2 モードから選ぶ
  - **Segmented**: 発話ごとに区切って認識する。精度が高い
  - **Streaming**: 話している最中から途中結果を表示する
- 翻訳モデルは LFM2-350M-ENJP-MT(既定)と Hy-MT2-1.8B から選ぶ
- 任意のオンラインモード(OpenAI Realtime / Gemini Live Translate)
- 2 つのセッションモード
  - **Listen**: 相手の話を聞いて自分の言語で読み上げる
  - **Conversation**: 自分と相手が交互に話す対面の会話(Android 12 以降)

## ビルド

### 前提

- JDK 17 以上
- Android SDK: platform `android-37`、NDK `27.2.12479018`
- Rust(stable)と Android ターゲット、cargo-ndk
  ```sh
  rustup target add aarch64-linux-android x86_64-linux-android
  cargo install cargo-ndk
  ```
- CMake 3.22 以上と Ninja(PATH 上)

### ビルドとインストール

```sh
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/yakumo.debug.apk
```

Windows では `./gradlew` の代わりに `.\gradlew.bat` を使う。署名付きのリリースビルドは [README.RELEASE.md](README.RELEASE.md) を参照。

### 補足

- Gradle のビルド中に sherpa-onnx のビルド済みライブラリをダウンロードし、Rust コア(llama.cpp を含む)を cargo-ndk でクロスコンパイルする。そのためクリーンビルドには数分かかる。対応 ABI は arm64-v8a(実機)と x86_64(エミュレータ)。
- CMake と Ninja は Android SDK の `cmake;<ver>` パッケージにも両方含まれている。`<ANDROID_HOME>/cmake/<ver>/bin` を PATH に通せばよい。
- UniFFI の Kotlin バインディング(`uniffi/translatecore/translatecore.kt`)はコミット済み。Rust の公開 API を変えたときだけ `pwsh tools/build-rust-core.ps1` で再生成する。
- ネイティブライブラリのビルドの詳細は [docs/architecture.md](docs/architecture.md) を参照。

## 使い方

1. **Settings** で音声認識モード(Segmented / Streaming)と翻訳モデルを選ぶ。
2. **Settings → Models → Download models for current settings** で、必要なモデルをダウンロードする。
3. New Session 画面でマイクを押して話す。

オフライン翻訳には、ARMv8.2 の dot-product 命令を持つ arm64 CPU(Cortex-A55 / A76 世代以降)が必要。非対応の端末ではオフラインで開始できないので、オンラインモードを使う。

### Listen と Conversation

New Session 画面で切り替える。

- **Listen**(既定): 発話ごとに言語を自動判定し、訳を読み上げる。
- **Conversation**: 自分の言語(Settings の「Your language」)と相手の言語を決めて会話する。
  1. 相手の言語を選び、マイクを押して開始する。最初は相手の発話として、相手 → 自分の言語に訳す。
  2. 自分が話すときは **Speaking** を押す。タップでオン / オフを切り替え、長押しなら押している間だけオンになる。オンの間は自分 → 相手の言語に訳す。
  3. 相手の発話の訳はイヤホンに、自分の発話の訳は本体スピーカーに出る。イヤホンが無いときは両方ともスピーカーに出る。

Conversation の制約:

- Android 12(API 31)以降のみ。
- オフラインでは日本語 ↔ 英語のみ。フランス語・ドイツ語・イタリア語・中国語(簡体字)はオンラインでだけ選べる。
- オンラインでは方向ごとに 1 本ずつ、計 2 本のセッションを同時に接続するので、課金も 2 本分になり得る。
- イヤホンとスピーカーの切り替えは、まだ実機で確かめていない。端末によっては切り替え直後の音の頭が欠けたり、Bluetooth の経路が想定と違ったりする可能性がある。

### オンラインモード

1. **Settings → Online translation** でプロバイダ(OpenAI Realtime / Gemini Live Translate)を選び、API キーを貼り付ける。
2. **Test connection** でキーとネットワークを確かめる。
3. New Session 画面のマイク横のトグルでオンラインに切り替える。

オンライン中は音声が選んだプロバイダに送信され、そのキーに課金される。API キーは端末内で暗号化して保存する。ターンが途中で切れる、またはなかなか区切られないときは「Pause to split a turn」で無音の長さを調整する。

## モデル

モデルは APK に含めず、アプリ内でダウンロードする(一覧は `android/app/src/main/assets/models.json`)。読み上げには OS の TTS を使うので、音声合成モデルは無い。

| ID | モデル | 用途 | サイズ | ライセンス |
|---|---|---|---|---|
| `vad` | [Silero VAD](https://github.com/snakers4/silero-vad) v6.2.3 | 発話の区切り(Segmented) | ~2 MB | MIT |
| `asr` | [SenseVoice int8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)(sherpa-onnx) | 音声認識 + 言語判定(Segmented) | ~230 MB | FunASR model license |
| `asr_stream` | [Nemotron 3.5 ASR streaming 0.6B int8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)(sherpa-onnx) | 音声認識(Streaming) | ~475 MB | OpenMDW-1.1 |
| `mt_lfm2` | [LFM2-350M-ENJP-MT](https://huggingface.co/LiquidAI/LFM2-350M-ENJP-MT-GGUF)(GGUF Q8_0) | 翻訳(既定、EN↔JA 専用) | ~379 MB | LFM Open License v1.0 |
| `mt_hymt2` | [Hy-MT2-1.8B](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF)(GGUF Q4_K_M) | 翻訳(多言語) | ~1.1 GB | Apache-2.0 |

- Segmented は `vad` + `asr`、Streaming は `asr_stream` を使う。Streaming は途中結果を出せる代わりに、日本語の精度が Segmented より低い。
- 翻訳モデルは選んだ 1 つだけをメモリに載せる。LFM2 は小さく速い。Hy-MT2 は大きく、メモリを多く使う。
- LFM Open License では、このアプリのような非商用の利用は許諾されている。商用利用は年間売上 1,000 万ドル未満に限られる。

## ライセンス

アプリ本体は [MIT ライセンス](LICENSE)(GitHub のリリースで非商用として配布)。モデルのライセンスは上の表のとおりで、各配布元の条件に従う。
