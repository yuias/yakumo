package app.rly3h.yakumo.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TTS_INIT_TIMEOUT_MS = 5_000L
private const val UTTERANCE_BASE_TIMEOUT_MS = 2_000L
private const val UTTERANCE_PER_CHAR_MS = 150L
private const val UTTERANCE_MAX_TIMEOUT_MS = 60_000L

/** Upper bound on how long to wait for the TTS engine to report completion of [text]. */
internal fun utteranceTimeoutMs(text: String): Long =
  minOf(UTTERANCE_BASE_TIMEOUT_MS + UTTERANCE_PER_CHAR_MS * text.length, UTTERANCE_MAX_TIMEOUT_MS)

/**
 * Speaks offline translations one utterance at a time, routing each to its
 * audience's output first. Owns a dedicated [TextToSpeech] so the attributes set
 * here do not leak into the Listen-mode instance. [speak] never blocks; [run] is
 * the single consumer.
 *
 * @param autoSpeak read per utterance so muting mid-session takes effect immediately.
 */
internal class TtsPlayback(
  context: Context,
  private val router: AudioRouter,
  private val autoSpeak: () -> Boolean,
  private val speechRate: () -> Float,
) : EchoGate {
  private class Utterance(val audience: Audience, val text: String, val locale: Locale)

  private val queue = Channel<Utterance>(Channel.UNLIMITED)
  private val ready = CompletableDeferred<Boolean>()

  // Utterance ids that are in flight, completed from the engine's binder thread.
  private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
  private val nextId = AtomicLong()

  @Volatile private var closed = false

  @Volatile
  override var speakerBusy = false
    private set

  private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
    if (status == TextToSpeech.SUCCESS) {
      configure()
      ready.complete(true)
    } else {
      ready.complete(false)
    }
  }

  fun speak(audience: Audience, text: String, locale: Locale) {
    if (closed || text.isBlank()) return
    queue.trySend(Utterance(audience, text, locale))
  }

  /** Single consumer; returns after [close]. */
  suspend fun run() = withContext(Dispatchers.IO) {
    // Without a working engine every utterance is dropped silently: the translated text is still shown.
    val usable = withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) { ready.await() } == true
    for (utterance in queue) {
      if (closed) break
      if (!usable || !autoSpeak()) continue
      try {
        play(utterance)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // One failed utterance (e.g. the engine died) must not stop later ones.
      }
    }
  }

  fun close() {
    if (closed) return
    closed = true
    queue.close()
    // Release anyone waiting on the engine so run() can return promptly.
    pending.values.forEach { it.complete(Unit) }
    runCatching { tts.stop() }
    runCatching { tts.shutdown() }
  }

  private fun configure() {
    tts.setAudioAttributes(router.playbackAttributes)
    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) = Unit
      override fun onDone(utteranceId: String?) = finish(utteranceId)

      @Deprecated("Superseded by onError(String, Int)")
      override fun onError(utteranceId: String?) = finish(utteranceId)
      override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
      override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
    })
  }

  private fun finish(utteranceId: String?) {
    utteranceId?.let { pending.remove(it) }?.complete(Unit)
  }

  private suspend fun play(utterance: Utterance) {
    // Checked before routing so a skipped utterance neither switches the device nor mutes the mic.
    val avail = tts.setLanguage(utterance.locale)
    if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) return

    // A routing failure leaves the output unknown, so assume the speaker (the echo gate errs on muting).
    val output = runCatching { router.routeTo(utterance.audience) }.getOrDefault(ResolvedOutput.SPEAKER)
    val onSpeaker = output == ResolvedOutput.SPEAKER
    if (onSpeaker) speakerBusy = true
    try {
      tts.setSpeechRate(speechRate())
      speakAndAwait(utterance.text)
      if (onSpeaker && !closed) delay(ECHO_TAIL_MS)
    } finally {
      speakerBusy = false
    }
  }

  private suspend fun speakAndAwait(text: String) {
    val id = "conv-${nextId.incrementAndGet()}"
    val done = CompletableDeferred<Unit>()
    // Registered before speak(): a fast engine can report completion before speak() returns.
    pending[id] = done
    try {
      if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) return
      // Timing out means the engine never reported back; cut it off so it cannot talk over the next one.
      if (withTimeoutOrNull(utteranceTimeoutMs(text)) { done.await() } == null) {
        runCatching { tts.stop() }
      }
    } catch (e: CancellationException) {
      runCatching { tts.stop() }
      throw e
    } finally {
      pending.remove(id)
    }
  }
}
