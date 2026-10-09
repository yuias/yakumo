package app.rly3h.yakumo.translate

import android.content.Context
import android.media.MediaRecorder
import android.util.Base64
import app.rly3h.yakumo.data.OnlineProvider
import app.rly3h.yakumo.data.Settings
import app.rly3h.yakumo.ui.session.InputMode
import app.rly3h.yakumo.ui.session.LanguagePair
import app.rly3h.yakumo.ui.session.ONLINE_CHUNK_MS
import app.rly3h.yakumo.ui.session.SAMPLE_RATE_16K
import app.rly3h.yakumo.ui.session.SAMPLE_RATE_24K
import app.rly3h.yakumo.ui.session.floresToLiveLang
import app.rly3h.yakumo.ui.session.linearResample16to24
import app.rly3h.yakumo.ui.session.rawChunkCaptureLoop
import app.rly3h.yakumo.ui.session.resolveDirection
import app.rly3h.yakumo.ui.session.supportsCaptureRate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Online engine: OpenAI Realtime speech-to-speech translation over a WebSocket.
 *
 * Flow: mint a short-lived ephemeral secret from the user's stored key → open
 * `wss://.../v1/realtime/translations` → push continuous 24 kHz PCM16 mic audio →
 * receive source/target transcript deltas (rendered into one turn row) and
 * translated-audio deltas (played via AudioTrack). The Realtime model detects the
 * source language itself; we only pin the target. MVP is one-directional — the
 * direction toggle picks the target language.
 */
internal class OpenAiTranslator(
  private val context: Context,
  private val settings: Settings,
  private val pair: LanguagePair,
  private val inputMode: InputMode,
) : SpeechTranslator {
  private val running = AtomicBoolean(false)

  @Volatile private var webSocket: WebSocket? = null
  @Volatile private var failure: String? = null
  // Completes true once `session.update` is sent, false on a failed open. Capture
  // buffers audio until then so it never precedes the session config.
  private val opened = CompletableDeferred<Boolean>()
  private val preConnect = PreConnectAudioBuffer(SAMPLE_RATE_24K * 2 * PRE_CONNECT_MS / 1000)
  private val finished = CompletableDeferred<Unit>()

  // Fixed direction for the session: target language is pinned, source auto-detected.
  private val resolved = resolveDirection(pair, inputMode, "", "")
  private val srcFlores = resolved.first.flores
  private val tgtFlores = resolved.second.flores

  private val audio = RealtimeAudioSink { settings.autoSpeak }

  private val json = Json { ignoreUnknownKeys = true }

  override fun start(scope: CoroutineScope, callbacks: TranslatorCallbacks) {
    running.set(true)
    val cb = callbacks
    val sink = RealtimeTurnAssembler(srcFlores, tgtFlores, cb)
    scope.launch(Dispatchers.IO) {
      try {
        val key = settings.apiKey(context, OnlineProvider.OPENAI)
        if (key.isNullOrBlank()) {
          cb.onFinished("No API key set. Add one in Settings → Online.")
          return@launch
        }
        // Start the mic before minting/connecting so speech during "Connecting…" is
        // buffered; teardown() stops it if the connect fails.
        launch(Dispatchers.IO) { captureLoop(cb) }
        cb.onStatus("Connecting to OpenAI…")
        val ephemeral = OpenAiRealtime.mintEphemeral(key, floresToLiveLang(tgtFlores))
        if (!running.get()) { cb.onFinished(null); return@launch } // stopped while minting

        val client = OkHttpClient.Builder()
          .pingInterval(20, TimeUnit.SECONDS)
          .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived stream
          .build()
        val request = Request.Builder()
          .url("${OpenAiRealtime.WS_URL}?model=${OpenAiRealtime.MODEL}")
          .header("Authorization", "Bearer $ephemeral")
          .header("OpenAI-Safety-Identifier", OpenAiRealtime.SAFETY_ID)
          .build()
        webSocket = client.newWebSocket(request, listener(cb, sink))

        val idleGapMs = settings.onlineIdleGapMs.toLong()
        launch(Dispatchers.IO) { audio.playbackLoop() }
        launch(Dispatchers.IO) { idleWatchdog(sink, idleGapMs) }

        finished.await()
        cb.onFinished(failure)
      } catch (e: Throwable) {
        cb.onFinished(e.message ?: e.toString())
      } finally {
        teardown()
      }
    }
  }

  override fun stop() {
    if (!running.compareAndSet(true, false)) return
    runCatching { webSocket?.close(1000, "client stop") }
    // Give the socket a moment to flush trailing deltas, then unblock start().
    if (!finished.isCompleted) finished.complete(Unit)
  }

  // --- WebSocket events ---

  private fun listener(cb: TranslatorCallbacks, sink: RealtimeTurnAssembler) = object : WebSocketListener() {
    override fun onOpen(ws: WebSocket, response: Response) {
      val lang = floresToLiveLang(tgtFlores)
      // Enabling input transcription is what makes the model emit source-language
      // (input_transcript) deltas; without it only the translation comes back.
      ws.send(
        """{"type":"session.update","session":{"audio":{""" +
          """"input":{"transcription":{"model":"$INPUT_TRANSCRIBE_MODEL"}},""" +
          """"output":{"language":"$lang"}}}}""",
      )
      cb.onStatus("Listening (OpenAI → ${labelOf(tgtFlores)})…")
      if (!opened.isCompleted) opened.complete(true)
    }

    override fun onMessage(ws: WebSocket, text: String) {
      handleEvent(text, cb, sink)
    }

    override fun onClosing(ws: WebSocket, code: Int, reason: String) {
      sink.finalize()
      if (!finished.isCompleted) finished.complete(Unit)
    }

    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
      if (running.get()) failure = "Online error: ${t.message}"
      if (!opened.isCompleted) opened.complete(false)
      if (!finished.isCompleted) finished.complete(Unit)
    }
  }

  private fun handleEvent(text: String, cb: TranslatorCallbacks, sink: RealtimeTurnAssembler) {
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
    val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
    when {
      type == "error" -> {
        val msg = (obj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        cb.onStatus("Online error: ${msg ?: type}")
      }
      type.contains("input") && type.contains("transcript") && type.endsWith("delta") ->
        sink.sourceDelta(delta(obj))
      type.contains("output") && type.contains("transcript") && type.endsWith("delta") ->
        sink.targetDelta(delta(obj))
      type.contains("output") && type.contains("audio") && type.endsWith("delta") ->
        audio.enqueueBase64(delta(obj))
      type.contains("transcript") && type.endsWith("done") -> sink.finalize()
    }
  }

  private fun delta(obj: JsonObject): String =
    obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()

  // No `*.done` event for a while after the last delta → close the open turn.
  private suspend fun idleWatchdog(sink: RealtimeTurnAssembler, idleGapMs: Long) {
    while (running.get()) {
      kotlinx.coroutines.delay(IDLE_POLL_MS)
      sink.finalizeIfIdle(idleGapMs)
    }
  }

  // --- Audio in (24 kHz PCM16) ---

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun captureLoop(cb: TranslatorCallbacks) {
    val native24k = supportsCaptureRate(SAMPLE_RATE_24K)
    val sampleRate = if (native24k) SAMPLE_RATE_24K else SAMPLE_RATE_16K
    try {
      rawChunkCaptureLoop(
        running,
        sampleRate,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        ONLINE_CHUNK_MS,
      ) { chunk ->
        // Buffer what is actually sent, i.e. after the fallback resample.
        val pcm24 = if (native24k) chunk else linearResample16to24(chunk)
        val ws = webSocket
        val gate = if (opened.isCompleted) opened.getCompleted() else null
        if (gate == false) return@rawChunkCaptureLoop // session is ending
        // `webSocket` is assigned after newWebSocket() returns and can lag onOpen.
        if (gate == null || ws == null) {
          preConnect.offer(pcm24)
          return@rawChunkCaptureLoop
        }
        preConnect.drain().forEach { sendAudio(ws, it) }
        sendAudio(ws, pcm24)
      }
    } catch (e: Throwable) {
      if (running.get()) cb.onStatus("Mic error: ${e.message}")
    }
  }

  private fun sendAudio(ws: WebSocket, pcm24: ByteArray) {
    // Drop audio rather than build an unbounded send queue on a stalled link.
    if (ws.queueSize() > MAX_WS_QUEUE_BYTES) return
    val b64 = Base64.encodeToString(pcm24, Base64.NO_WRAP)
    ws.send("""{"type":"session.input_audio_buffer.append","audio":"$b64"}""")
  }

  private fun teardown() {
    // Capture starts before the socket exists, so a failed connect must stop the mic.
    running.set(false)
    audio.close()
    runCatching { webSocket?.cancel() }
    webSocket = null
  }

  private fun labelOf(flores: String): String = floresToLiveLang(flores).uppercase()

  private companion object {
    const val INPUT_TRANSCRIBE_MODEL = "gpt-realtime-whisper"
    const val MAX_WS_QUEUE_BYTES = 256 * 1024L
    const val IDLE_POLL_MS = 300L
  }
}
