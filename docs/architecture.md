# やくも — アーキテクチャ

> 対象: 実装済みの現行構成(2026-10 時点)。
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
Rust core (rust/core)            VAD / ASR のオーケストレーションと FFI、翻訳は llama.cpp
        │  C API / 静的リンク
        ▼
Native libs (jniLibs)            sherpa-onnx + onnxruntime, libtranslatecore.so(llama.cpp を静的リンク)
```

音声の入出力(`AudioRecord` / `TextToSpeech` / `AudioTrack`)は Kotlin 側に残す。
Rust 側は純粋な変換・推論だけを担当するので、テストしやすく iOS 等へも移植しやすい。

---

## 2. 翻訳エンジンの抽象

`translate/SpeechTranslator.kt` の interface に、オフライン / オンラインの実装をぶら下げる。
UI(`ui/session/NewSessionScreen.kt`)はトグルで実装を選ぶだけで、ターンの表示・保存は共通。

| 実装 | ファイル | 中身 |
|---|---|---|
| `OfflineTranslator` | `translate/OfflineTranslator.kt` | Silero VAD v6 → SenseVoice ASR(または Nemotron 3.5 streaming) → llama.cpp 翻訳(GGUF) → OS TTS |
| `OpenAiTranslator` | `translate/OpenAiTranslator.kt` + `OpenAiRealtime.kt` | OpenAI Realtime translations(S2S) |
| `GeminiTranslator` | `translate/GeminiTranslator.kt` + `GeminiLive.kt` | Gemini Live Translate(S2S) |
| `OfflineConversationTranslator` | `translate/OfflineConversationTranslator.kt` | Conversation 用。`OfflineTranslator` を Speaking の状態で方向を決めるフック付きで動かし、`TtsPlayback` で読み上げる |
| `OnlineConversationTranslator` | `translate/OnlineConversationTranslator.kt` | Conversation 用。`OpenAiTranslator` / `GeminiTranslator` を方向ごとに 1 本、計 2 本を 1 つのマイクで動かし、`PcmPlayback` で再生する |

`RealtimeSupport.kt` に両オンライン実装の共通部品を置く:
`RealtimeTurnAssembler`(原文 / 訳文の delta を 1 行に組み立てる)と
`RealtimeAudioSink`(24kHz PCM16 を `AudioTrack` で再生)。
Conversation では訳音声を `RealtimeAudioSink` ではなく `audio/PcmPlayback` に渡す(`audioOut` で差し替える)。

セッションには 2 つのモードがある(§5)。**Listen**(既定)は上の 3 実装を従来どおり使う。
**Conversation** は `ConversationTranslator`(`setSpeaking` を持つ `SpeechTranslator`)として
`OfflineConversationTranslator` / `OnlineConversationTranslator` を選び、音声の出力先を `audio/` が切り替える(§5a)。

---

## 3. オフライン経路

```
AudioRecord (VOICE_RECOGNITION, 16kHz mono PCM16, ~100ms フレーム)
  → VAD        Silero VAD v6 (Rust: vad_load / vad_accept / vad_flush)                  → セグメント
  → ASR        SenseVoice int8 (言語タグ付き) または streaming Nemotron 3.5              → Transcript
  → 翻訳       GGUF 翻訳 LLM (llama.cpp: LFM2-350M-ENJP-MT または Hy-MT2-1.8B)          → Translate
  → 読み上げ   OS の android.speech.tts.TextToSpeech
  → 保存       filesDir/sessions/<id>.json
```

### 区切り(VAD)
セグメント化は Silero VAD v6.2.3 に委譲している(自前のエネルギー VAD は撤去)。
v6 は v5 と同じインターフェース(16 kHz で 512 サンプルの窓)・同じ確率スケールなので、
Rust 側のコードは v4 から変えていない。
ノブは `VadParams` の 4 つで、すべて Settings のスライダーから可変:

| ノブ | 既定 | 意味 |
|---|---|---|
| `threshold` | 0.5 | 音声確率のしきい値 |
| `minSilenceMs` | 500 | セグメントを閉じる末尾無音長 |
| `minSpeechMs` | 250 | これより短い発話は捨てる |
| `maxSpeechMs` | 15000 | 長すぎる発話の強制カット |

既定値は v4 のときのまま据え置いている(Silero 自身の既定しきい値も 0.5 のまま)。
手元に比較用の録音がないため、v6 向けの再調整は未実施。静かな部屋と騒がしい部屋で
数本録音し、v4 と v6 のセグメント分割を比べて既定値を見直すのが今後の課題。保存済みのユーザー設定はリセットしない。

### ASR(排他の 2 モード)
Settings → Speech recognition で **Segmented** か **Streaming** を選ぶ(設定名 `asrMode`)。
どちらか一方だけが使われ、使わない側のエンジンはアンロードされる(後述)。

- **Segmented**(既定): VAD → **SenseVoice int8**(zh-en-ja-ko-yue)。`<|en|>` / `<|ja|>` の言語タグを返すので、
  翻訳方向の自動判定にそのまま使う。精度は最も高い。
- **Streaming**: **Nemotron 3.5 ASR streaming 0.6B**(`asr_stream`、多言語)。
  話している最中の途中結果が得られる代わりに、日本語の精度は SenseVoice より低く、**言語タグは出ない**
  (方向判定は §5)。ターン区切りは VAD ではなく sherpa の endpoint rule
  (`EndpointParams` rule1/2/3、既定 2.4 / 1.2 / 20.0 秒)に委ねる。
  Kotlin は stream option の `language` を常に `"auto"` で渡す。1 本のストリームが両方の話者を扱うため、
  言語を固定すると相手側の発話を取り違えるから。

### 翻訳
llama.cpp(`llama-cpp-2` crate)を `libtranslatecore.so` に静的リンクし、GGUF の翻訳 LLM を CPU で実行する
(`rust/core/src/mt.rs`)。ユーザーが Settings → Translation で次のどちらかを選ぶ(設定名 `mtModel`、既定 LFM2):

| モデル | 特徴 | プロンプト |
|---|---|---|
| LFM2-350M-ENJP-MT(Q8_0) | EN↔JA 専用。小さく速い | system に `Translate to Japanese.` / `Translate to English.`、user に原文 |
| Hy-MT2-1.8B(Q4_K_M) | 多言語。大きく遅い | user に `Translate the following text into <言語名>. Note that you should only output the translated result without any additional explanation:` + 原文 |

- **レシピは GGUF の `general.architecture` で決める**(`lfm2` / `hunyuan-dense`)。同じモデルの別量子化への
  差し替えはマニフェストの編集だけで済む。それ以外のアーキテクチャはロード時にエラー。
- **チャットテンプレートは llama.cpp の組み込みを名前で指定**する(LFM2 は `chatml`、Hy-MT2 は `hunyuan-dense`)。
  GGUF 埋め込みテンプレートの自動検出は、Hy-MT2 のテンプレートを `hunyuan-vl` と誤判定して
  壊れたプロンプトを作るので使わない。BOS はどちらの組み込みテンプレートも出さないため、
  テキストとして先頭に付ける。埋め込みテンプレートにレシピ固有のマーカーが含まれるかもロード時に確認する。
- **サンプリングは各モデルカードの値に固定シードを組み合わせる**。LFM2 は temp 0.5 / min_p 0.1 / repeat penalty 1.05、
  Hy-MT2 は temp 0.7 / top_k 20 / top_p 0.6 / repeat penalty 1.05(同じ入力は同じ出力になる)。
- **生成長の上限**は `min(3 × 原文トークン数 + 32, 512)`。コンテキストは 1024。暴走した生成を打ち切るための上限で、
  プロンプトが長すぎて出力の余地が残らない入力は `input too long for translation` で断る。
- **ストリーミング**: `mt_translate_streaming` はトークン生成のたびに、その時点までの累積テキストをコールバックに返す
  (差分ではない)。トークン片が UTF-8 の途中で切れることがあるので、完全な文字になるまで溜めてから出す。
  訳文を確定前から表示できる。

### CPU 要件(dot-product ゲート)
arm64 の ggml-cpu は `armv8.2-a+dotprod` でビルドしている(§8)。dotprod を持たない CPU
(Cortex-A53 / A73 世代など)では最初の dotprod 命令で SIGILL となりプロセスごと落ちる。
そこで MT の全エントリポイントが、`LlamaBackend` の初期化やモデルのロードより前に
`AT_HWCAP` の `ASIMDDP` ビットを確認する(`cpu_supported()`、純粋なビット判定は host でテスト済み)。
非対応なら `mt_supported()` が false を返し、Kotlin は Settings に注意書きを出してオフライン開始を断る
(Online モードは使える)。x86_64 はベースラインのビルドなのでゲートは常に通る。

### モデル常駐とアンロード
recognizer / detector / 翻訳モデルは Rust 側のプロセスグローバル(`OnceLock<Mutex<Option<Engine>>>`)に保持する。
ロードは 1 回きりで、以降の呼び出しは再利用する。

- **翻訳**: 重み(GGUF を mmap)だけを常駐させ、LLM のコンテキスト(KV キャッシュ)はリクエストごとに作って捨てる。
  別のモデルを要求したら、新しいロードの**前に**古い重みを解放する(2 つが同時にメモリへ載らない)。
- **アンロード API**: `asr_unload` / `asr_stream_unload` / `vad_unload` / `mt_unload`。エンジンの mutex を取って
  `None` にするだけで、処理中の認識・翻訳はそれが終わってから解放される。次の利用で遅延ロードされる。
- **使うタイミング**: セッション開始時(使わない側の ASR エンジンを解放)、Settings での ASR モード / 翻訳モデルの切り替え時、
  モデルの削除時(削除前にそのモデルを担当するエンジンを解放する)。
- **ロードのタイミング**: オフラインセッションの開始時に、翻訳モデルのロードをバックグラウンドで始める。
  ロードは最初の発話と重なり、翻訳はエンジンの mutex 待ちになる。Settings のプリロードボタンは、現在の選択に必要なものだけを
  ロードする(Segmented なら VAD + SenseVoice、Streaming なら Nemotron、いずれも翻訳モデルを加える)。

### 音声出力
OS の TTS エンジンに寄せている(`Locale` 指定 + `setSpeechRate`)。
日本語の漢字 g2p を OS 側が処理してくれるのが決め手で、オンデバイス TTS モデルは同梱しない。
Android 11+ のパッケージ可視性のため、`AndroidManifest.xml` に `<queries>` の `TTS_SERVICE` が要る。
Conversation のオフラインは Listen の `TextToSpeech` を共有せず、`TtsPlayback` が専用のインスタンスを持つ(§5a)。
出力先用の属性が Listen 側に漏れないようにするため。

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

キャプチャは `VOICE_COMMUNICATION`(HW AEC で訳音声の回り込みを軽減)。Conversation では 2 セッションが 1 本のキャプチャを共有する(§5)。
音声は **40 ms チャンク**で送る(オフラインは 100 ms)。`AudioRecord` から `ByteArray` へ直接読む。

**接続前の音声バッファ**: キャプチャは接続(OpenAI はトークン発行 + WebSocket、Gemini は WebSocket + setup)の**前に**始める。
ソケットが準備できるまでの音声は `PreConnectAudioBuffer`(上限 2 秒分。リサンプル後で OpenAI 96,000 B、Gemini 64,000 B。
超えたら古いものから捨てる)に溜め、準備ができたらセッション設定の送信後に溜めた順で送り、以降は直接送る。
これで接続中に話した頭の部分が失われない。接続に失敗した場合は `teardown()` がキャプチャも止める。
訳音声は `AudioTrack` で再生するので OS TTS は使わない。
キーはプロバイダごとに別スロットで、**Tink AEAD + Android Keystore マスターキー**で暗号化して
SharedPreferences に base64 で置く(平文は prefs に触れない)。

**Listen の制約**: 1 つのセッションは片方向。target 言語は固定し、source は自動検出に任せる。
双方向の対面会話は、この単方向セッションを方向ごとに 2 本並べる Conversation モードで扱う(§5a)。

---

## 5. 会話モデルと方向判定

`LanguageOption`(FLORES コード + ASR タグ + `Locale` + オフライン可否)の一覧が対応言語で、EN / JA / FR / DE / IT / ZH(簡体字)の 6 行。
先頭の EN / JA だけが `offline = true` で、FR / DE / IT / ZH はオンライン専用
(SenseVoice が FR/DE/IT を認識できず、翻訳コアも `eng_Latn` / `jpn_Jpan` しか持たないため)。
UI は「自分の言語(Settings で固定、EN / JA)」と「相手の言語(セッションごと)」という
`Conversation` の形で持ち、翻訳エンジンの境界で `LanguagePair` + `InputMode` に畳む。

### セッションモード

| | Listen(既定) | Conversation |
|---|---|---|
| 相手の言語 | Auto(null)または特定の言語。選べるのはオフライン可の言語 | 必ず具体的な言語(Auto 無し)。オフライン中はオンライン専用の言語を選べない |
| 方向の決め方 | 発話ごとの自動判定(下記) | Speaking ボタンの状態(自動判定しない) |
| 出力 | メディア音量(OS TTS / `AudioTrack`) | 聞く人に応じてイヤホン / スピーカー(§5a) |
| API レベル | 制限なし | **API 31 以上のみ** |

保存済みの Conversation モードを API 31 未満の端末が読んだ場合は Listen に戻す(開始できないモードに留めない)。

### Listen の方向判定

方向は SenseVoice の言語タグを最優先し、タグが無い/不明なら文字種のヒューリスティックに落とす。
Nemotron 3.5(Streaming)は多言語だが言語タグを返さないので、常にこのヒューリスティックで方向を決める
(日本語の文字を含めば日本語側、含まなければもう一方の側と判定する)。
文字種で区別できるのは日本語だけなので、Listen の相手言語はオフライン可の言語(EN / JA)に限る。

### Conversation の方向

話者は `Speaker`(`ME` / `PARTNER`)で表す。
**Speaking がオフ**のあいだはマイクを相手の発話(`PARTNER`: 相手 → 自分の言語)、
**オン**のあいだは自分の発話(`ME`: 自分 → 相手の言語)として扱う。セッションは常にオフで始まる。

- **Speaking ボタン**は `PttLatch` で、**タップするとラッチ**(もう一度タップするまでオン)、**長押しの間だけ**オン(離すとオフ)。
  オフ状態から 400ms 以上押し続けると長押し(離すとオフ)、それより短ければタップ(ラッチ)とみなす。
  ラッチ中のタップは離した時点でオフになる。録音が止まるとラッチは解除される。
- 翻訳の聞き手は話者の反対側。`ME` の訳は `Audience.PARTNER`(相手が聞く)、`PARTNER` の訳は `Audience.ME`(自分が聞く)。
- 方向の判定は `ConversationInputGate`(キャプチャスレッド専用)が一元化している。切り替えの最初のチャンクには
  「直前の話者」が付く。これを使って、オフラインの Segmented は直前の方向の VAD を flush してから次へ進み
  (Streaming は確定テキストを直前の方向で確定してから認識状態をリセットする)、オンラインは直前の方向のセッションへ無音(`SWITCH_TAIL_MS` = 800ms)を足して、プロバイダに開いたターンを閉じさせる。
- ターンの `id` は話者ごとに別番号なので、`directionalTurnId` で `ME` を奇数、`PARTNER` を偶数にしてリストのキーを一意にする。
  ターンには `speaker` が付き、セッションの履歴にも `speaker` と `mode` を保存する。
  `speaker` が無い(旧版 / Listen)ターンの表示は従来どおり。

### オンラインの 2 セッション

オンラインの Conversation は、同じプロバイダのリアルタイムセッションを方向ごとに 1 本、**計 2 本を同時に**開く
(`OnlineConversationTranslator`)。相手 → 自分のセッションは `FORCE_A`、自分 → 相手のセッションは `FORCE_B` で方向を固定する。
Speaking を押した時点で接続を待たないよう、**2 本とも開始時に接続**する。したがって**課金も 2 本分になり得る**。

- マイクは 1 本の `AudioRecord`(`VOICE_COMMUNICATION`)を共有し、各チャンクを Speaking が選ぶ側のセッションにだけ送る。
  待機側のセッションには何も送らない。
- 接続前の音声は各セッションの `PreConnectAudioBuffer` に溜まる(§4)。
- どちらかのセッションが終了または失敗したら、最初のエラーを残してセッション全体を止める。
  停止時は内側のセッションが終わるのを最大 5 秒待ってから、ルーターを解放する。
- ステータスは「`Partner→JA: …` · `You→FR: …`」のように両方向の状態を 1 行にまとめる。
- 言語コードは OpenAI が `zh`、Gemini が **`zh-Hans`**(Gemini の対応言語表に素の `zh` は無い。`floresToGeminiLang`)。
  他の言語は両者で同じ。

### オフラインの Conversation

オフラインは `OfflineTranslator` に `OfflineConversationHooks` を渡して動かす。方向は Speaking の状態から
`conversationDirection` が決めるので、自動判定は使わない(Segmented / Streaming のどちらでも同じ)。
VAD が切ったセグメント / Streaming の確定テキストには、キャプチャ時の方向を付けて下流に流す。
Streaming は方向の切り替え時にそこまでの確定テキストを直前の方向で出し、`asrStreamReset` で認識状態をリセットする。
SenseVoice / Nemotron の言語対応とモデルの都合で、オフラインの Conversation は **EN ↔ JA のみ**。
FR/DE/IT/ZH を選んだ状態でオフライン開始しようとすると、開始を断ってオンラインを案内する。

---

## 5a. 音声の出力先ルーティング(Conversation)

Conversation では、自分が聞く訳(相手の発話の訳)と相手が聞く訳(自分の発話の訳)を別の出力に出したい。
自分向けは**イヤホン**、相手向けは**本体スピーカー**。Listen は従来どおり(音声モードは `MODE_NORMAL` のまま)。

### `audio/` パッケージ

| ファイル | 役割 |
|---|---|
| `AudioRouter.kt` | `AudioRouter` interface(`begin` / `routeTo` / `playbackAttributes` / `builtinMic` / `end`)、`Audience` / `ResolvedOutput`、ルーターの登録簿 `AudioRouters` |
| `CommunicationDeviceRouter.kt` | API 31 以上の実装。`MODE_IN_COMMUNICATION` + `setCommunicationDevice` |
| `PcmPlayback.kt` | オンラインの訳音声(24kHz PCM16)を `AudioTrack` で再生する。`EchoGate` を実装 |
| `TtsPlayback.kt` | オフラインの訳文を専用の `TextToSpeech` で読み上げる。`EchoGate` を実装 |

ルーティングの実装を `AudioRouter` の背後に置いているのは、下記の前提が実機で崩れたときにエンジンを触らず差し替えられるようにするため。

### 仕組み

1. セッション開始時に `begin()` が `AudioManager.mode` を `MODE_IN_COMMUNICATION` にする。権限は `MODIFY_AUDIO_SETTINGS`。
   イヤホンの有無もここで調べる。
2. 発話(セグメント)ごとに `routeTo(audience)` を呼ぶ。`ME` なら優先順(有線ヘッドセット → 有線ヘッドホン → USB → BLE → Bluetooth SCO → 補聴器)で
   最初に見つかったイヤホン、`PARTNER` なら内蔵スピーカーを `setCommunicationDevice` で指定する。
   デバイスは呼ぶたびに OS から引き直し、キャッシュしない。切り替えた直後は `ROUTE_SETTLE_MS`(200ms)待つ。
   待たないと次の発話の頭が古いデバイスに出る。
3. 再生は**一度に 1 セグメント**。自分向けと相手向けは交互に再生し、同時には出さない
   (キューの先頭が先に届いたほうを先に再生する)。オンラインは発話ごとに新しい `AudioTrack` を作り、
   選んだデバイスで開く。
4. `AudioTrack` と TTS には必ず `router.playbackAttributes`(`USAGE_VOICE_COMMUNICATION`)を使う。通常のメディア属性だと通信デバイスの設定に追従しない。
5. 終了時に `end()` が通信デバイスを解除して `MODE_NORMAL` に戻す。`end()` は冪等で、どのスレッドからも呼べる。
   次の安全策が重なる: エンジンの `finally`、新しいセッションが残った古いルーターを先に `end()` すること、
   サービスの `onDestroy` が自分の世代のルーターだけを `releaseActive` で終了すること(世代で新しいセッションのルーターを巻き込まない)。
6. キャプチャは内蔵マイクに固定する(`builtinMic()`)。Bluetooth ヘッドセットの SCO 入力に切り替わらないようにする意図。
7. 通信用の再生は通話音量に従うので、Conversation の録音中は画面で `volumeControlStream` を `STREAM_VOICE_CALL` にして、音量キーがその音量を操作するようにする。

**イヤホンが無い場合**は、`begin()` が「分離できない」ことを返し、画面に注意書きを出す。
`routeTo` はどちらの向きでも内蔵スピーカーを指定するので、両方向ともスピーカーで再生される
(`ME` でもイヤホンが見つからなければスピーカー)。

### エコーゲート

スピーカーで再生した訳音声をマイクが拾い、別の方向に再入力すると、訳が訳を呼ぶ。これを防ぐのがエコーゲート。

- `PcmPlayback` / `TtsPlayback` が `EchoGate.speakerBusy` を公開する。**実際にスピーカーで鳴らす間**(と、その後の残響用の `ECHO_TAIL_MS` = 300ms)だけ true。
  `routeTo` が `SPEAKER` を返したか、切り替えに失敗して出力先が不明なときは、スピーカー扱いにする(誤って黙らせる側に倒す)。
- キャプチャスレッドが `ConversationInputGate.decide(speaking, speakerBusy)` で毎チャンク判定する。
  ミュートするのは**相手側(`PARTNER`)の方向だけ**。イヤホンが無いと相手の訳もスピーカーで鳴るので、
  スピーカーの再生中は相手の方向を止める。**Speaking を押している間(自分の方向)は上書きしない**。
- ミュートしたチャンクは、捨てずに無音(ゼロ)に置き換える。プロバイダ / VAD に渡す音声のタイムラインを途切れさせないため。
- TTS は、言語データが無いロケールを先にスキップする(出力先の切り替えもミュートもしない)。

### Stop 時の挙動

Conversation で Stop すると、再生待ち・再生中の訳音声(`PcmPlayback` / `TtsPlayback` のキュー)は即座に破棄する。
訳文のテキストは画面に出た時点で保存済みなので失われない。Listen は最後の読み上げを最後まで再生する。

### 実機で未検証の前提

ルーティングは**ビルドと単体テストまでしか確認しておらず、実機では未検証**。次は仮定のままになっている:

- `MODE_IN_COMMUNICATION` 中に `setCommunicationDevice` で、有線 / USB / Bluetooth のイヤホンと内蔵スピーカーを発話ごとに切り替えられる。
- `ROUTE_SETTLE_MS`(200ms)で、切り替え直後の音頭が欠けない。
- Bluetooth イヤホンで SCO / LE Audio のどちらになり、入力が内蔵マイクのままか。A2DP のみの機器はイヤホン無し扱いになる。
- `TextToSpeech.setAudioAttributes` で、TTS もスピーカー / イヤホンに追従する。
- セッション終了後、他アプリの再生に影響しない(`MODE_NORMAL` と通信デバイスの解除が確実に戻る)。
- Streaming の Conversation で、Speaking の切り替え時に最後の数語が欠けないか(欠けるなら、方向を切り替える前に旧方向の無音を送ってから `asrStreamReset` する)。

端末が手に入ったら、有線 / Bluetooth / イヤホン無し / API 31 未満 / 異常終了(タスク削除・Stop・片方のセッション切断)でモードが戻ることを順に確認する。

---

## 6. モデル配布

モデルは APK に同梱せず、初回利用時に `assets/models.json` のマニフェストから
アプリ内部ストレージ(`filesDir`)へダウンロードする。

- エントリは `id` / `dir` / `checkFile` と、`archive`(tar.bz2)または `files[]`(個別 URL)。
  `size` / `sha256` は任意で、HuggingFace の URL はリビジョンを固定している。
- tar.bz2 の展開は Apache Commons Compress(Java の `ZipInputStream` は bz2 非対応)。
- ダウンロードは手動リダイレクト追跡(GitHub / HuggingFace の CDN ホップ)+ `.part` → rename の
  アトミック書き込み。展開も staging → 完了時 rename なので、中断で不完全なモデルが残らない。
- **完全性の検証**: 書き込みながら SHA-256 を計算し(2 回読みはしない)、rename の前にサイズと sha256 を
  マニフェストの値と突き合わせる(`integrityError`)。不一致なら `.part` を削除して失敗にする。
  検証済みのファイルだけが rename されるので、`isPresent` は単なる存在確認で済む。間違ったファイルが
  置かれて sherpa がクラッシュする、という事態を避けるための仕組み。
- **旧モデルの掃除**: マニフェストの `obsoleteDirs` に、置き換え済みのモデルのディレクトリ
  (旧 VAD、旧 Nemotron、旧翻訳モデル)を列挙する。Settings に残量を出し、ボタンで削除できる。
  モデルを差し替えるときは、新しい `dir`(既存のインストールに再ダウンロードさせるため)・`checkFile`・
  `files[]` を編集し、古い `dir` を `obsoleteDirs` に追記する。

| ID | モデル | 用途 | 概算 |
|---|---|---|---|
| `vad` | Silero VAD v6.2.3 | ターン区切り(Segmented) | ~2 MB |
| `asr` | SenseVoice int8 (zh-en-ja-ko-yue) | ASR + 言語判定(Segmented) | ~230 MB |
| `asr_stream` | Nemotron 3.5 ASR streaming 0.6B int8 | 低遅延の多言語 ASR(Streaming) | ~475 MB |
| `mt_lfm2` | LFM2-350M-ENJP-MT (GGUF, Q8_0) | 翻訳(既定、EN↔JA 専用) | ~379 MB |
| `mt_hymt2` | Hy-MT2-1.8B (GGUF, Q4_K_M) | 翻訳(多言語) | ~1.1 GB |

Settings の Models 行には、現在の ASR モード・翻訳モデルが必要とするエントリに「· in use」が付く。
上部の「Download models for current settings」ボタンは、そのうち未取得のものだけをダウンロードする
(Segmented は `vad` + `asr` + 翻訳モデル、Streaming は `asr_stream` + 翻訳モデル)。
Settings ではモデルごとに削除もでき(録音中は不可)、削除の前にそのモデルを担当するネイティブエンジンを
アンロードする(GGUF は mmap されているので、解放せずに消さない)。

Hy-MT2 の量子化は、より低ビットの型が mainline の llama.cpp に入り Tencent が再配布した時点で
変更しうる。その場合もマニフェストの編集だけで済む(JSON にコメントを書けないのでここに記す)。
現状の 1.25 / 2 ビット版 GGUF は mainline の llama.cpp ではロードできない。

> **内部ストレージ必須**: 一部 OEM(ColorOS / OxygenOS 等)では NDK の生 `open()` が
> 外部ストレージ(`Android/data`)で EACCES になる。Java の `File.exists()` は通るのに
> `listFiles()` は拒否されるため、モデルは必ず `filesDir` 配下に置くこと。

---

## 7. 永続化と設定

- **セッション**: `data/SessionStore.kt`。`filesDir/sessions/<id>.json` に 1 セッション 1 ファイル。
  件数が少なくスキーマも要らないので DB は使わず kotlinx.serialization の JSON にしている。
- **設定**: `data/Settings.kt`(SharedPreferences)。読み上げ速度 / 自動読み上げ / ASR モード(`asrMode`: `SEGMENTED` / `STREAMING`) /
  翻訳モデル(`mtModel`: `LFM2` / `HYMT2`、既定 `LFM2`、不明な値は `LFM2` に戻す) /
  VAD 4 ノブ / endpoint 3 ルール / オンラインのプロバイダ・idle gap・暗号化済みキー。
  `asrMode` は、保存値があればそれを優先し、なければ旧版の `streamingAsr`(真偽値)から移行し、
  どちらもなければ `SEGMENTED`。`asrMode` を保存した時点で旧キーは削除する。
- **セッションモード**: `SessionMode`(`LISTEN` / `CONVERSATION`)と、モードごとに別々に持つ相手の言語
  (Listen は Auto 可、Conversation は必ず具体的な言語)。不明な値は `LISTEN` に戻す。
  保存した Conversation を API 31 未満で読んだ場合も `LISTEN` に戻す。
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

必要なツールチェーン(JDK 17+ / Android SDK / NDK 27.2.12479018 / Rust + cargo-ndk / CMake + Ninja)は
`README.md` の「前提」を参照。

### llama.cpp のビルド
`llama-cpp-2` crate が llama.cpp を CMake でビルドし、`libtranslatecore.so` に静的リンクする。

- **CMake 3.22 以上と Ninja** が必要。`rust/core/.cargo/config.toml` が `CMAKE_GENERATOR=Ninja` を指定する
  (cmake-rs は Android ターゲットに `-G` を渡さず、Windows では Visual Studio ジェネレータが選ばれて NDK を駆動できないため)。
  CI は CMake / Ninja の有無を確認するステップを持つ。
- **ABI ごとに cargo-ndk を 1 回ずつ呼ぶ**(Gradle の `cargoBuildRustCore*` タスクと `rust/build-android.ps1`)。
  `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod` は arm64-v8a のときだけ設定する。llama-cpp-sys-2 は `GGML_*` 環境変数を
  全 ABI の CMake へ渡すので、`.cargo/config.toml` に置くと x86_64 にも漏れてしまう。x86_64 はベースラインのまま。
- **`--platform 24`**(minSdk に合わせる)を渡す。llama.cpp の CMake ビルドはこれを `ANDROID_PLATFORM` として読む。
- **libc++ は静的リンク**(`llama-cpp-2` の `android-static-stdcxx` feature)。sherpa-onnx は `libc++_shared.so` を
  同梱しないため、`libtranslatecore.so` の NEEDED に `libc++_shared.so` が入ってはいけない。
- debug ビルドでも ggml が最適化なしにならないよう、`[profile.dev.package.llama-cpp-sys-2]` で `opt-level = 3` にしている。
- llama.cpp は Android ターゲット限定の依存。host ビルド(`cargo test`、`uniffi-bindgen`)に CMake や
  C++ ツールチェーンを要求しないため。

### onnxruntime
`libonnxruntime.so` は sherpa-onnx の依存としてだけ同梱する(`fetchSherpaPrebuilt` が展開する)。
Rust 側からは直接使わず、別ビルドもしない。

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

- オフラインの対応言語は EN↔JA のみ。FR / DE / IT / ZH は Conversation のオンラインだけで使える(§5)。
  オフラインを広げるには、ASR(SenseVoice / Nemotron 3.5)・翻訳モデル・OS TTS の対応が前提
  (LFM2 は EN↔JA 専用なので、他の言語ペアには多言語の翻訳モデルが要る)。
- オフライン翻訳は dot-product 命令(ASIMDDP)を持つ arm64 CPU を要する。非対応の端末では §3 のゲートが
  オフライン開始を断る。**残るリスク**: ggml-cpu の C++ 静的初期化子は `libtranslatecore.so` の `dlopen` 時に、
  Rust のコードより前に走る。その中に dotprod 命令があるとゲートでは防げない。静的初期化子は通常、量子化の
  ドット積を行わないが、古い端末(Cortex-A53 のみなど)での起動確認はまだ取れていない。
- Hy-MT2 の 1.25 / 2 ビット版 GGUF は mainline の llama.cpp ではロードできないため使っていない(Q4_K_M を使用)。
- Streaming モード(Nemotron 3.5)の日本語精度は SenseVoice より低い。
- Listen のオンラインは片方向(target 固定 + source 自動検出)。双方向は Conversation(2 セッション)で扱う。
- Conversation は Android 12(API 31)以上のみ。それ未満では選べない。
- Conversation のオンラインは 2 セッションを同時に接続するので、課金も 2 本分になり得る。
- Conversation の出力先ルーティング(`MODE_IN_COMMUNICATION` + `setCommunicationDevice`)は**実機で未検証**(§5a)。
  音頭の欠け、Bluetooth の経路、TTS の出力先追従などは端末で確認するまで確定しない。
- Conversation でイヤホンが無いと、両方向ともスピーカーで鳴る。エコーゲートが相手方向のマイクを止めるため、
  訳の再生中は相手の発話を取りこぼし得る(自分が Speaking で話す方向は止めない)。
- Conversation で Stop すると、再生待ち・再生中の訳音声は破棄される(テキストは保存済み)。
- Listen でスピーカー再生だとエコーで自分の訳音声を拾い得る。オンラインは HW AEC を効かせているが、
  イヤホン利用を推奨。
- ターン区切りはヒューリスティック(VAD ノブ / endpoint rule / idle gap)なので、
  環境と話し方で体感が変わる。
