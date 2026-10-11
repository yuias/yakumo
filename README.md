# やくも (Yakumo) — EN↔JA 音声翻訳

Android 向けの英語↔日本語音声翻訳アプリ。既定では、初回にモデルをダウンロードした後は音声認識と機械翻訳(llama.cpp で動かす GGUF の翻訳 LLM)が**完全オフライン**で動き、訳文は OS のテキスト読み上げ(TTS)エンジンで発話する。オプトインのオンラインモードでは、1 ターンをクラウドの speech-to-speech モデル(OpenAI Realtime または Gemini Live Translate)に任せることもできる。推論の中核は Rust で書かれており、UniFFI 経由で Jetpack Compose の UI から呼び出す。

## 機能

- オフラインの ASR → 翻訳 → 発話パイプライン(推論時にネットワーク不要)
- 音声認識は排他の 2 モードから選ぶ(Settings → Speech recognition)
  - **Segmented**: Silero VAD v6 でターンを分割し、SenseVoice で認識する。言語を発話ごとに自動判定する
  - **Streaming**: Nemotron 3.5(多言語)で話している最中の途中結果を表示する。ターンは end-of-turn ルールで区切る
- 翻訳モデルは 2 種類から選ぶ(Settings → Translation)。LFM2-350M-ENJP-MT(既定、小さくて速い)または Hy-MT2-1.8B(多言語、大きい)。訳文はトークンごとにストリーム表示する
- 使わない側のエンジンはメモリから解放する(モード / モデルの切り替え時、モデルの削除時)
- OS の TTS エンジンによる発話(再生速度は可変)
- 任意の**オンラインモード**(OpenAI Realtime または Gemini Live Translate による speech-to-speech)— 後述
- 2 つの**セッションモード**(New Session 画面で切り替え)— 後述の「Listen と Conversation」
  - **Listen**(既定): 従来どおりの一方向の使い方。相手の話を聞いて自分の言語で読み上げる。言語は発話ごとに自動判定する
  - **Conversation**: 自分と相手が交互に話す対面の会話。Speaking ボタンで話者を切り替え、訳の出力先をイヤホンとスピーカーに振り分ける。**Android 12 以降のみ**

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
- **CMake(3.22 以上)と Ninja**: llama.cpp のビルドに使う。どちらも PATH 上に必要(Windows / Linux / macOS 共通)。Android SDK の `cmake;<ver>` パッケージ(`cmake` と `ninja` の両方を同梱)をインストールして `<ANDROID_HOME>/cmake/<ver>/bin` を PATH に通すか、システムのパッケージマネージャで入れる。`rust/build-android.ps1` は `cmake` が見つからないとき、最新の Android SDK 版を自動で PATH に追加する。`rust/core/.cargo/config.toml` が `CMAKE_GENERATOR=Ninja` を指定している(cmake-rs は Android ターゲットに `-G` を渡さず、Windows では Visual Studio ジェネレータが選ばれて NDK を駆動できないため)。

### ビルドとインストール

```sh
cd android
./gradlew assembleDebug
```

通常の jniLibs マージの前に、2 つのタスクが追加で走る:

1. **`fetchSherpaPrebuilt`** — sherpa-onnx のビルド済み `.so` をダウンロードし(Gradle ユーザーホームにキャッシュ)、`jniLibs/<abi>/` に展開する。
2. **`cargoBuildRustCore`** — `rust/core` を cargo-ndk でクロスコンパイルし、`jniLibs/<abi>/libtranslatecore.so` を生成する。`build.rs` が `libsherpa-onnx-c-api.so` にリンクするため、fetch の後に実行する必要がある。

どちらも入出力を宣言しているので、変更がなければスキップされる。対応 ABI は **arm64-v8a**(実機)と **x86_64**(エミュレータ)。

クリーンビルドでは各 ABI 向けに llama.cpp もコンパイルするため、時間がかかる(ローカルで 2 分前後)。arm64 の ggml-cpu は dot-product 命令つき(`GGML_CPU_ARM_ARCH=armv8.2-a+dotprod`)でビルドする。この変数は `android/app/build.gradle.kts` と `rust/build-android.ps1` が arm64 のときだけ設定する(llama-cpp-sys-2 は `GGML_*` 環境変数を全 ABI の CMake に渡すため、`rust/core/.cargo/config.toml` には置かない)。x86_64 はベースラインのまま。

APK は `android/app/build/outputs/apk/debug/app-debug.apk` に出力される。`adb install`(または任意の Android CLI)でインストールして起動し、最初の翻訳の前に、**Settings** で音声認識モード(Segmented / Streaming)と翻訳モデル(LFM2 / Hy-MT2)を選び、**Settings → Models → Download models for current settings** を実行する。現在の選択に必要なモデルのうち未取得のものだけをダウンロードする(Segmented なら `vad` + `asr` + 選択中の翻訳モデル、Streaming なら `asr_stream` + 選択中の翻訳モデル)。モデルごとの行から個別にダウンロード・削除もでき、取得済みのモデルは削除してから再ダウンロードする。ダウンロードしたファイルはマニフェストのサイズ(と、分かっていれば sha256)で検証し、一致しないものは受け付けない。旧バージョンのモデル(旧 VAD、旧 Nemotron、旧翻訳モデル)が残っていれば、Models の「Old model files from earlier versions」行から削除できる。

オフライン翻訳には、ARMv8.2 の dot-product 命令(ASIMDDP)を持つ arm64 の CPU が必要(Cortex-A55 / A76 世代以降の端末はほぼ該当)。非対応の端末ではクラッシュさせず、オフライン開始を断って Online モードを案内する。

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
Rust core (rust/core/)         pipeline orchestration, ASR via FFI, MT via llama.cpp
        │  C API / 静的リンク
        ▼
Native libs (jniLibs/)         sherpa-onnx + onnxruntime, libtranslatecore.so (llama.cpp を静的リンク)
```

- **録音**は Kotlin 側(`AudioRecord`)に置き、**再生**は OS の `TextToSpeech` エンジンを使う。Rust は純粋な変換と推論だけを担うので、テストしやすい。
- **翻訳**は llama.cpp(`llama-cpp-2` crate 経由)で GGUF の翻訳 LLM を実行する。llama.cpp は `libtranslatecore.so` に静的リンクされ、libc++ も静的に取り込む。`libonnxruntime.so` は sherpa-onnx の依存としてのみ同梱する。

エンジンの抽象化、VAD/エンドポイントのパラメータ、モデル配布、FFI の規約などの全体像は [docs/architecture.md](docs/architecture.md) を参照。

## モデル

モデルは APK に**同梱しない**。初回使用時に `android/app/src/main/assets/models.json` のマニフェストに従って、アプリの内部ストレージにダウンロードする(一部の OEM では NDK の生の `open()` が外部ストレージで拒否されるため、内部ストレージが必須)。

| ID | モデル | 役割 | 配布元 | おおよそのサイズ |
|---|---|---|---|---|
| `vad` | Silero VAD v6.2.3 | 音声区間検出 / ターン分割(Segmented) | [snakers4/silero-vad](https://github.com/snakers4/silero-vad) | ~2 MB |
| `asr` | sherpa-onnx SenseVoice int8 (zh-en-ja-ko-yue) | ASR + 言語判定(Segmented) | [k2-fsa releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) | ~230 MB |
| `asr_stream` | sherpa-onnx Nemotron 3.5 ASR streaming 0.6B int8 | 低遅延の多言語ストリーミング ASR(Streaming) | [k2-fsa releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) | ~475 MB |
| `mt_lfm2` | LFM2-350M-ENJP-MT(GGUF、Q8_0) | 翻訳(既定、EN↔JA 専用) | [LiquidAI on HF](https://huggingface.co/LiquidAI/LFM2-350M-ENJP-MT-GGUF) | ~379 MB |
| `mt_hymt2` | Hy-MT2-1.8B(GGUF、Q4_K_M) | 翻訳(多言語、実行時にメモリを多く使う) | [tencent on HF](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF) | ~1.1 GB |

音声認識は **Segmented**(`vad` + `asr`)か **Streaming**(`asr_stream`)のどちらか一方を使う。Segmented の SenseVoice は英語と日本語の両方を扱い、翻訳方向の自動判定に使う言語タグも返す。精度は SenseVoice のほうが高い。Streaming の Nemotron 3.5 は話している最中の途中結果を出せる代わりに、日本語の精度は SenseVoice より低く、言語タグも出さないため、翻訳方向は文字種のヒューリスティックで決める。

翻訳モデルは LFM2 と Hy-MT2 のどちらか一方を選ぶ。両方ダウンロードしたまま保持できるが、メモリに載るのは選択中の 1 つだけ。アプリ内の言語ペアは EN↔JA なので、LFM2(EN↔JA 専用)が既定で、Hy-MT2 は多言語モデルをそのまま使う選択肢という位置づけ。Hy-MT2 は将来、より低ビットの量子化が mainline の llama.cpp で読めるようになり配布された時点で、マニフェストの差し替えだけで更新できる。

発話には端末の OS TTS エンジンを使うので、音声合成モデルはダウンロードしない。

## Listen と Conversation

New Session 画面の切り替えで 2 つのモードから選ぶ。既定は Listen で、Conversation を選べるのは Android 12(API 31)以降の端末だけ(それ未満では選択肢が無効になり、理由が表示される)。

- **Listen**: 発話ごとに言語を自動判定し、訳を OS の TTS(オンラインでは訳音声)で再生する。相手の言語は Auto か特定の言語を選べる。音声の経路は通常のメディア再生のままで、変更はない。
- **Conversation**: 自分の言語(Settings の「Your language」)と**相手の言語**を決めて会話する。相手の言語に Auto は無く、必ず具体的な言語を選ぶ。話者は言語の自動判定ではなく **Speaking ボタン**で決まる。

### Conversation の使い方

1. 相手の言語を選び、マイクを押して開始する。開始直後は「相手が話している」状態で、マイクの音声は相手の発話として相手 → 自分の言語に訳される。
2. 自分が話すときは **Speaking** を押す。押している間(または押してラッチした間)のマイクの音声は、自分 → 相手の言語に訳される。
   - **タップ**: ラッチ(オン)になり、もう一度タップするまで自分の発話として扱う。
   - **長押し**: 押している間だけオン。指を離すと相手側に戻る。
3. 各ターンは話者つきで表示され、履歴にも話者とモードが保存される。

### 言語とオンライン / オフライン

- 相手の言語は英語・日本語・フランス語・ドイツ語・イタリア語・中国語(簡体字)から選ぶ。
- **フランス語・ドイツ語・イタリア語・中国語はオンライン専用**。オフラインの会話は**日本語 ↔ 英語のみ**で、ピッカーではオンライン専用の言語がオフライン中は選べない。
- 中国語は、OpenAI には `zh`、Gemini には `zh-Hans` で渡す(Gemini の対応言語表に載っているのは `zh-Hans` のため)。
- **オンライン**: プロバイダの speech-to-speech セッションを**方向ごとに 1 本、計 2 本を同時に**開き、1 つのマイクの音声を Speaking の状態に応じて片方のセッションにだけ送る。2 本とも接続した状態で動くので、**課金も 2 本分になり得る**。
- **オフライン**: 既存のオフラインパイプライン(VAD または Streaming の ASR → 翻訳 → OS TTS)を、言語の自動判定ではなく Speaking の状態で方向を決めて動かす。

### 出力先(イヤホンとスピーカー)

訳した音声は、聞く人に応じて出力先を切り替える。

- 相手 → 自分の訳(自分が聞く): **イヤホン**
- 自分 → 相手の訳(相手が聞く): **本体スピーカー**

この切り替えは `MODE_IN_COMMUNICATION` と `AudioManager.setCommunicationDevice` で行い、発話(セグメント)ごとに出力先を交互に切り替える。**同時に両方へは出さない**。Conversation の間だけ音声モードを `MODE_IN_COMMUNICATION` にし、終了時やエラー時に `MODE_NORMAL` へ戻す。このために `MODIFY_AUDIO_SETTINGS` 権限を使う。

- 対象のイヤホンは有線・USB・Bluetooth(SCO / LE Audio)など。
- **イヤホンが無い場合**は、両方向とも本体スピーカーで再生し、画面に注意書きを出す。
- スピーカーから出た訳音声をマイクが拾って別の方向に再入力しないよう、**エコーゲート**が働く(詳細は [docs/architecture.md](docs/architecture.md))。
- Conversation で **Stop** を押すと、再生待ち・再生中の訳音声は破棄する。訳文のテキストは既に画面に出て保存済み。Listen では最後の読み上げを最後まで再生する。

> **未検証**: 出力先の切り替えは、`MODE_IN_COMMUNICATION` + `setCommunicationDevice` で端末が期待どおりにルーティングするという前提に立っている。この前提はビルドと単体テストまでしか確認しておらず、実機(有線 / Bluetooth / イヤホン無し)ではまだ確かめていない。端末によって切り替え直後の音頭が欠ける、Bluetooth の経路や音質が想定と違う、といったことが起こり得る。

## オンラインモード(任意)

既定はオフラインで、アカウントは不要。より低い遅延が欲しい場合は **Settings → Online translation** でオンライン翻訳を有効にし、プロバイダを選ぶ:

- **OpenAI Realtime**(`gpt-realtime-translate`)— アプリが API キーから短命のエフェメラルトークンを発行し、それでストリームを開く。
- **Gemini Live Translate**(`gemini-3.5-live-translate-preview`)— キーで WebSocket を直接認証する(トークン交換なし)。

API キーはプロバイダごとに保持し、Android Keystore のマスターキーのもとで Tink AEAD により端末内で暗号化する。選んだプロバイダのキーを貼り付けて **Test connection** を押すと、キーとネットワークを検証する(OpenAI はエフェメラルトークンの発行、Gemini は軽量な models 呼び出し)。マイク横のトグルで実行中のエンジンを切り替える。音声は 40 ms 単位のチャンクで送り、接続中に拾った発話(最大 2 秒分)は接続が確立してから送る。既定はオフラインのパイプラインのまま。オンライン中は音声が選んだプロバイダに送信され、そのキーに課金される。

どちらのエンジンも、1 ターンを原文の書き起こしとストリーミングされる訳文として表示し、翻訳された音声を再生する。ターンの区切りは、プロバイダが終了シグナルを送る場合はそれ(OpenAI)、訳文の文末句読点(継続的にストリームする Gemini で必要)、または無音の間で決まる。無音の長さは **Settings → Online translation** の「Pause to split a turn」で調整できる。

## ライセンス

アプリ本体は [MIT ライセンス](LICENSE)(GitHub のリリースバイナリとして非商用で配布)。モデルは APK に同梱せず、実行時に各配布元からダウンロードする。各モデルのライセンスは配布元に従う:

| モデル | ライセンス |
|---|---|
| Silero VAD v6.2.3 | MIT |
| SenseVoice(sherpa-onnx int8) | FunASR model license |
| Nemotron 3.5 ASR streaming 0.6B | OpenMDW-1.1 |
| LFM2-350M-ENJP-MT | LFM Open License v1.0(この非商用での利用は許諾されている。商用利用は年間売上 1,000 万ドル未満に限られる) |
| Hy-MT2-1.8B | Apache-2.0 |
