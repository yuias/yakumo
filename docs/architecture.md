# やくも — アーキテクチャ

> 対象: 実装済みの現行構成(2026-08 時点)。設計の経緯・フェーズ検証ログは
> リポジトリ外(`.claude/notes/architecture-history.md`)に退避してある。
> ビルド手順は `README.md`、リリース手順は `README.RELEASE.md` を参照。

---

## 1. 全体像

EN↔JA の音声翻訳を、既定では**完全オフライン**(端末内推論)で行う。
推論の中核は Rust crate `translatecore` で、UniFFI + JNA 経由で Jetpack Compose の UI から呼ぶ。
オンライン翻訳は任意で、クラウドの speech-to-speech モデルに 1 ターンを丸ごと預ける別経路。

```
Kotlin / Compose (android/app)   UI・AudioRecord キャプチャ・OS TTS・モデル配布・設定
        │  UniFFI + JNA
        ▼
Rust core (rust/core)            VAD / ASR / 翻訳のオーケストレーションと FFI
        │  C API / dlopen
        ▼
Native libs (jniLibs)            sherpa-onnx + onnxruntime, libtranslatecore.so
```

音声の入出力(`AudioRecord` / `TextToSpeech` / `AudioTrack`)は Kotlin 側に残す。
Rust 側は純粋な変換・推論だけを担当するので、テストしやすく iOS 等へも移植しやすい。

---

## 2. 翻訳エンジンの抽象

`translate/SpeechTranslator.kt` の interface に、オフライン / オンラインの実装をぶら下げる。
UI(`ui/session/NewSessionScreen.kt`)はトグルで実装を選ぶだけで、ターンの表示・保存は共通。

| 実装 | ファイル | 中身 |
|---|---|---|
| `OfflineTranslator` | `translate/OfflineTranslator.kt` | Silero VAD → SenseVoice ASR → NLLB 翻訳 → OS TTS |
| `OpenAiTranslator` | `translate/OpenAiTranslator.kt` + `OpenAiRealtime.kt` | OpenAI Realtime translations(S2S) |
| `GeminiTranslator` | `translate/GeminiTranslator.kt` + `GeminiLive.kt` | Gemini Live Translate(S2S) |

`RealtimeSupport.kt` に両オンライン実装の共通部品を置く:
`RealtimeTurnAssembler`(原文 / 訳文の delta を 1 行に組み立てる)と
`RealtimeAudioSink`(24kHz PCM16 を `AudioTrack` で再生)。

---

## 3. オフライン経路

```
AudioRecord (VOICE_RECOGNITION, 16kHz mono PCM16, ~100ms フレーム)
  → VAD        Silero VAD (Rust: vad_load / vad_accept / vad_flush)     → セグメント
  → ASR        SenseVoice int8 (言語タグ付き) または streaming Nemotron  → Transcript
  → 翻訳       NLLB-200-distilled-600M ONNX (ort, KVキャッシュ貪欲デコード) → Translate
  → 読み上げ   OS の android.speech.tts.TextToSpeech
  → 保存       filesDir/sessions/<id>.json
```

### 区切り(VAD)
セグメント化は Silero VAD に委譲済み(自前のエネルギー VAD は撤去)。
ノブは `VadParams` の 4 つで、すべて Settings のスライダーから可変:

| ノブ | 既定 | 意味 |
|---|---|---|
| `threshold` | 0.5 | 音声確率のしきい値 |
| `minSilenceMs` | 500 | セグメントを閉じる末尾無音長 |
| `minSpeechMs` | 250 | これより短い発話は捨てる |
| `maxSpeechMs` | 15000 | 長すぎる発話の強制カット |

### ASR
既定は **SenseVoice int8**(zh-en-ja-ko-yue)。`<|en|>` / `<|ja|>` の言語タグを返すので、
翻訳方向の自動判定にそのまま使う。

実験的に **Nemotron streaming EN 0.6B**(`asr_stream`)へ切り替えられる(Settings → Experimental)。
低遅延の部分認識が得られる代わりに **英語専用・言語タグなし**なので EN→JA 方向専用。
こちらのターン区切りは VAD ではなく sherpa の endpoint rule(`EndpointParams` rule1/2/3、既定 2.4 / 1.2 / 20.0 秒)に委ねる。

### 翻訳
`decoder_model_merged_quantized.onnx` + KV キャッシュの貪欲デコード。
step0 だけ `use_cache_branch=false` + 0 長 past で走らせ、以降は `present.*` を `past_key_values.*` に
付け替える(デコーダ自己注意 KV は所有権ごと移動、エンコーダ KV は不変なので参照で再利用)。
これでデコードが O(n²) → O(n)。レイヤ数は decoder の入力名から自動検出する。

`translate_text_streaming` はトークン単位でコールバックを返すので、訳文を確定前から表示できる。

### モデル常駐
recognizer / translator は Rust 側のプロセスグローバル(`OnceLock<Mutex<Option<Engine>>>`)に
`model_dir` をキーとして保持する。ロードは 1 回きりで、以降の呼び出しは再利用。
Settings のウォームアップボタン(`asr_load` / `translate_load` / `vad_load`)で先にロードしておける。

### 音声出力
OS の TTS エンジンに寄せている(`Locale` 指定 + `setSpeechRate`)。
日本語の漢字 g2p を OS 側が処理してくれるのが決め手で、オンデバイス TTS モデルは同梱しない。
Android 11+ のパッケージ可視性のため、`AndroidManifest.xml` に `<queries>` の `TTS_SERVICE` が要る。

---

## 4. オンライン経路(任意)

Settings → Online translation でプロバイダとキーを設定し、マイク横のトグルで切り替える。
トグルが有効になるのは **ネットワーク有 かつ 該当プロバイダのキー設定済み** のときだけ(既定は Offline)。
ネット状態は `registerDefaultNetworkCallback` で監視。

| | OpenAI Realtime | Gemini Live |
|---|---|---|
| モデル | `gpt-realtime-translate` | `gemini-3.5-live-translate-preview` |
| 認証 | `POST /v1/realtime/translations/client_secrets` で ephemeral(600s)を発行してから接続 | API キーで WebSocket に直結 |
| 送出音声 | 24kHz PCM16(16k しか取れない端末では線形リサンプル) | 16kHz PCM16 |
| ターン確定 | プロバイダの end-of-turn シグナル | 訳文の文末句読点 |
| 共通の保険 | 無音 `onlineIdleGapMs`(既定 1200ms)で強制的にターンを閉じる | 同左 |

キャプチャは `VOICE_COMMUNICATION`(HW AEC で訳音声の回り込みを軽減)。
訳音声は `AudioTrack` で再生するので OS TTS は使わない。
キーはプロバイダごとに別スロットで、**Tink AEAD + Android Keystore マスターキー**で暗号化して
SharedPreferences に base64 で置く(平文は prefs に触れない)。

**MVP 制約**: 片方向。target 言語は固定し、source は自動検出に任せる。

---

## 5. 会話モデルと方向判定

`LanguageOption`(FLORES コード + ASR タグ + `Locale`)の一覧が対応言語で、現状は EN / JA の 2 行。
UI は「自分の言語(Settings で固定)」と「相手の言語(セッションごと、null = 自動判定)」という
`Conversation` の形で持ち、翻訳エンジンの境界で `LanguagePair` + `InputMode` に畳む。

方向は SenseVoice の言語タグを最優先し、タグが無い/不明なら文字種のヒューリスティックに落とす。

---

## 6. モデル配布

モデルは APK に同梱せず、初回利用時に `assets/models.json` のマニフェストから
アプリ内部ストレージ(`filesDir`)へダウンロードする。

- エントリは `id` / `dir` / `checkFile` と、`archive`(tar.bz2)または `files[]`(個別 URL)。
- tar.bz2 の展開は Apache Commons Compress(Java の `ZipInputStream` は bz2 非対応)。
- ダウンロードは手動リダイレクト追跡(GitHub / HuggingFace の CDN ホップ)+ `.part` → rename の
  アトミック書き込み。展開も staging → 完了時 rename なので、中断で不完全なモデルが残らない。

| ID | モデル | 用途 | 概算 |
|---|---|---|---|
| `vad` | Silero VAD | ターン区切り | ~2 MB |
| `asr` | SenseVoice int8 (zh-en-ja-ko-yue) | ASR + 言語判定 | ~230 MB |
| `nllb` | NLLB-200-distilled-600M ONNX (quantized, merged decoder) | 翻訳 | ~865 MB |
| `asr_stream` | Nemotron streaming EN 0.6B int8 | 実験的な低遅延 EN ASR | ~464 MB |

`asr_stream` は大きく英語専用なので一括ダウンロードから外し、Settings → Experimental の
モデル行で個別に取得する。Settings ではモデルごとに削除もでき(録音中は不可)、削除後も
ネイティブ側にロード済みのモデルはプロセス再起動まで使われ続ける(アンロード API がないため)。

> **内部ストレージ必須**: 一部 OEM(ColorOS / OxygenOS 等)では NDK の生 `open()` が
> 外部ストレージ(`Android/data`)で EACCES になる。Java の `File.exists()` は通るのに
> `listFiles()` は拒否されるため、モデルは必ず `filesDir` 配下に置くこと。

---

## 7. 永続化と設定

- **セッション**: `data/SessionStore.kt`。`filesDir/sessions/<id>.json` に 1 セッション 1 ファイル。
  件数が少なくスキーマも要らないので DB は使わず kotlinx.serialization の JSON にしている。
- **設定**: `data/Settings.kt`(SharedPreferences)。読み上げ速度 / 自動読み上げ / streaming ASR /
  VAD 4 ノブ / endpoint 3 ルール / オンラインのプロバイダ・idle gap・暗号化済みキー。
- **キー暗号化**: `data/SecureKeyStore.kt`(Tink AEAD)。
  `EncryptedSharedPreferences` は 2026 に deprecated のため不採用。

---

## 8. ネイティブライブラリのビルド時生成/取得

`jniLibs/` の `.so` はすべて生成物か上流バイナリなので、リポジトリには入れず Gradle が用意する
(`android/app/.gitignore` で除外)。対応 ABI は **arm64-v8a**(実機)と **x86_64**(エミュレータ)。

1. **`fetchSherpaPrebuilt`** — sherpa-onnx リリースの tar.bz2 を取得(Gradle user home にキャッシュ)し、
   `libsherpa-onnx-{c,cxx}-api.so` と `libonnxruntime.so` を `jniLibs/<abi>/` へ展開する。
2. **`cargoBuildRustCore`** — cargo-ndk で `rust/core` をクロスコンパイルし
   `jniLibs/<abi>/libtranslatecore.so` を出力する。

順序が重要で、`build.rs` が `libsherpa-onnx-c-api.so` にリンクするため 1 → 2。
`preBuild` が 2 に依存し、AGP の jniLibs マージ前に `.so` が揃う。両タスクとも入出力を宣言しているので
差分が無ければスキップされる。

必要なツールチェーン(JDK 17+ / Android SDK 36 / NDK 27.2.12479018 / Rust + cargo-ndk)は
`README.md` の Prerequisites を参照。

### onnxruntime の解決
`ort` は `load-dynamic` で使い、onnxruntime を別ビルドしない。sherpa-onnx 同梱の
`libonnxruntime.so` を **soname `"libonnxruntime.so"` のまま** `dlopen` する。
`translatecore.so → sherpa → onnxruntime` の依存で既にプロセスに常駐しているため、
`extractNativeLibs=false`(AGP 既定、`.so` は APK 内 mmap でディスク展開されない)でも解決できる。
逆に絶対パスを渡すと `dlopen failed: not found` になる。

---

## 9. FFI の作法(ハマり所)

- sherpa の C 構造体は `c-api.h` を `#[repr(C)]` で**全フィールド**ミラーする。
  使わない埋め込み設定も定義しないと後続フィールドのオフセットがずれる。初期化は `mem::zeroed()`。
- 生ポインタを常駐させる都合上 `unsafe impl Send` + `Drop` で破棄を一元化する。
- PCM は `ByteArray`(PCM16 LE)で渡し、Rust 側で i16→f32 変換する(boxed Float 列を避ける)。
- sherpa のネイティブログは stderr で logcat に出ない。失敗診断は Rust 側でファイル可読性を
  先に確認してエラーメッセージに含めるのが速い。
- UniFFI バインディング(`uniffi/translatecore/translatecore.kt`)はコミット済み。
  Rust の公開 API を変えたときだけ `pwsh rust/build-android.ps1` で再生成する。

---

## 10. 既知の制約

- 対応言語は EN↔JA のみ。`LANGUAGES` に行を足せば広がるが、ASR(SenseVoice)と OS TTS の
  両対応が前提。
- オンラインは片方向(target 固定 + source 自動検出)。
- スピーカー再生だとエコーで自分の訳音声を拾い得る。オンラインは HW AEC を効かせているが、
  イヤホン利用を推奨。
- ターン区切りはヒューリスティック(VAD ノブ / endpoint rule / idle gap)なので、
  環境と話し方で体感が変わる。
