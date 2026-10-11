package app.rly3h.yakumo.translate

import android.content.Context
import android.media.MediaRecorder
import app.rly3h.yakumo.audio.AudioRouter
import app.rly3h.yakumo.audio.PcmPlayback
import app.rly3h.yakumo.data.OnlineProvider
import app.rly3h.yakumo.data.Settings
import app.rly3h.yakumo.ui.session.InputMode
import app.rly3h.yakumo.ui.session.LanguageOption
import app.rly3h.yakumo.ui.session.LanguagePair
import app.rly3h.yakumo.ui.session.ONLINE_CHUNK_MS
import app.rly3h.yakumo.ui.session.SAMPLE_RATE_16K
import app.rly3h.yakumo.ui.session.SAMPLE_RATE_24K
import app.rly3h.yakumo.ui.session.linearResample16to24
import app.rly3h.yakumo.ui.session.rawChunkCaptureLoop
import app.rly3h.yakumo.ui.session.supportsCaptureRate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Online Conversation engine: two simultaneous realtime sessions of the same
 * provider, one per direction, fed from a single shared microphone capture.
 *
 * The partner-to-me session and the me-to-partner session both connect at start
 * so pressing Speaking never waits for a handshake. Each captured chunk goes
 * only to the session the Speaking control selects (plus a short silent tail to
 * the one that was just left, so the provider closes its open turn); the idle
 * session receives nothing, which avoids billing and false translations.
 * Translated audio from both sessions is serialized through [PcmPlayback].
 */
internal class OnlineConversationTranslator(
  private val context: Context,
  private val settings: Settings,
  private val provider: OnlineProvider,
  private val mine: LanguageOption,
  private val partner: LanguageOption,
  private val router: AudioRouter,
) : ConversationTranslator {
  private val running = AtomicBoolean(false)
  private val started = AtomicBoolean(false)
  private val finishedOnce = AtomicBoolean(false)
  private val firstError = AtomicReference<String?>(null)
  private val stopRequested = CompletableDeferred<Unit>()
  private val allInnerFinished = CompletableDeferred<Unit>()
  private val innerFinished = BooleanArray(Speaker.entries.size)

  @Volatile private var speaking = false

  private val playback = PcmPlayback(router) { settings.autoSpeak }

  // The partner session translates partner -> mine and the me session mine -> partner; the
  // pair is (a = partner, b = mine) so FORCE_A / FORCE_B select those directions.
  private val pair = LanguagePair(a = partner, b = mine)
  private val partnerEngine = newEngine(Speaker.PARTNER)
  private val meEngine = newEngine(Speaker.ME)

  private val statusLock = Any()
  private val statuses = arrayOfNulls<String>(Speaker.entries.size)
  @Volatile private var routeNote: String? = null

  @Volatile private var outer: TranslatorCallbacks? = null

  override fun setSpeaking(speaking: Boolean) {
    this.speaking = speaking
  }

  @OptIn(DelicateCoroutinesApi::class)
  override fun start(scope: CoroutineScope, callbacks: TranslatorCallbacks) {
    if (!started.compareAndSet(false, true)) return
    running.set(true)
    outer = callbacks
    // ATOMIC: the body must begin even if the scope is already cancelled, so that the finally
    // below always runs and the router is always ended.
    scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
      try {
        // Stop or cancellation may already have arrived; the inner engines ignore stop() until
        // their own start(), so do not start them for a session that is over.
        if (!isActive || stopRequested.isCompleted) return@launch
        val info = router.begin()
        routeNote = info.note
        info.note?.let { callbacks.onStatus(it) }
        launch { playback.run() }

        partnerEngine.start(
          this,
          DirectionalCallbacks(Speaker.PARTNER, callbacks, ::onInnerStatus, ::onInnerFinished),
        )
        meEngine.start(
          this,
          DirectionalCallbacks(Speaker.ME, callbacks, ::onInnerStatus, ::onInnerFinished),
        )
        // The mic opens once, after both sessions are launched: audio fed meanwhile is held in
        // each session's pre-connect buffer.
        launch { captureLoop() }

        stopRequested.await()
        // The first stop() may have hit engines that had not started yet (a no-op for them).
        runCatching { partnerEngine.stop() }
        runCatching { meEngine.stop() }
        // A session stuck connecting only notices stop() later; do not hold the router for it.
        withTimeoutOrNull(STOP_GRACE_MS) { allInnerFinished.await() }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        firstError.compareAndSet(null, e.message ?: e.toString())
      } finally {
        running.set(false)
        runCatching { partnerEngine.stop() }
        runCatching { meEngine.stop() }
        playback.close()
        router.end()
        if (finishedOnce.compareAndSet(false, true)) callbacks.onFinished(firstError.get())
        coroutineContext.cancelChildren()
      }
    }
  }

  override fun stop() = requestStop(null)

  // --- Session lifecycle ---

  private fun newEngine(speaker: Speaker): FeedableTranslator {
    val mode = if (speaker == Speaker.PARTNER) InputMode.FORCE_A else InputMode.FORCE_B
    val audience = audienceFor(speaker)
    val out: (ByteArray) -> Unit = { playback.enqueue(audience, it) }
    return when (provider) {
      OnlineProvider.OPENAI ->
        OpenAiTranslator(context, settings, pair, mode, ownCapture = false, audioOut = out)
      OnlineProvider.GEMINI ->
        GeminiTranslator(context, settings, pair, mode, ownCapture = false, audioOut = out)
    }
  }

  /** The first error wins; either session ending (or a mic failure) takes the whole session down. */
  private fun requestStop(error: String?) {
    if (error != null) firstError.compareAndSet(null, error)
    running.set(false)
    stopRequested.complete(Unit)
    runCatching { partnerEngine.stop() }
    runCatching { meEngine.stop() }
  }

  private fun onInnerFinished(speaker: Speaker, error: String?) {
    val allDone = synchronized(innerFinished) {
      innerFinished[speaker.ordinal] = true
      innerFinished.all { it }
    }
    requestStop(error)
    if (allDone) allInnerFinished.complete(Unit)
  }

  // --- Status ---

  private fun onInnerStatus(speaker: Speaker, text: String) {
    // Delivered under the lock so an older merged line cannot overwrite a newer one.
    synchronized(statusLock) {
      statuses[speaker.ordinal] = text
      outer?.onStatus(composeStatus())
    }
  }

  // Each inner session only sees its own direction, so the outer engine merges both lines.
  private fun composeStatus(): String {
    val parts = buildList {
      statuses[Speaker.PARTNER.ordinal]?.let { add("Partner→${mine.short}: $it") }
      statuses[Speaker.ME.ordinal]?.let { add("You→${partner.short}: $it") }
      routeNote?.let { add(it) }
    }
    return parts.joinToString(" · ")
  }

  // --- Shared capture ---

  private fun captureLoop() {
    // Both sessions use the same provider, hence the same input rate.
    val targetRate = partnerEngine.inputSampleRate
    val nativeRate = !(targetRate == SAMPLE_RATE_24K && !supportsCaptureRate(SAMPLE_RATE_24K))
    val captureRate = if (nativeRate) targetRate else SAMPLE_RATE_16K
    val gate = ConversationInputGate()
    try {
      rawChunkCaptureLoop(
        running,
        captureRate,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        ONLINE_CHUNK_MS,
        preferredDevice = router.builtinMic(),
      ) { raw ->
        route(gate, if (nativeRate) raw else linearResample16to24(raw))
      }
    } catch (e: Throwable) {
      if (running.get()) requestStop("Mic error: ${e.message}")
    }
  }

  private fun route(gate: ConversationInputGate, chunk: ByteArray) {
    val decision = gate.decide(speaking, playback.speakerBusy)
    decision.switchedFrom?.let { prev ->
      // Silence lets the provider's VAD close the turn the user just walked away from.
      // Sized from the target rate: a short AudioRecord read would otherwise shorten the tail.
      val rate = partnerEngine.inputSampleRate
      val chunkBytes = onlineChunkBytes(rate)
      val engine = engineOf(prev)
      repeat((switchTailBytes(rate) + chunkBytes - 1) / chunkBytes) {
        engine.feed(ByteArray(chunkBytes))
      }
    }
    // Zeros, not a dropped chunk, keep the provider's audio timeline continuous.
    engineOf(decision.active).feed(if (decision.muted) ByteArray(chunk.size) else chunk)
  }

  private fun engineOf(speaker: Speaker): FeedableTranslator =
    if (speaker == Speaker.ME) meEngine else partnerEngine

  private companion object {
    /** How long stop() waits for the inner sessions before releasing the router anyway. */
    const val STOP_GRACE_MS = 5000L
  }
}

/** Silence sent to the session being left after a Speaking toggle. */
internal const val SWITCH_TAIL_MS = 800

/** Bytes of 16-bit mono PCM in one [ONLINE_CHUNK_MS] chunk at [rate]. */
internal fun onlineChunkBytes(rate: Int): Int = rate * 2 * ONLINE_CHUNK_MS / 1000

/** Bytes of 16-bit mono PCM in the [SWITCH_TAIL_MS] silent tail at [rate]. */
internal fun switchTailBytes(rate: Int): Int = rate * 2 * SWITCH_TAIL_MS / 1000
