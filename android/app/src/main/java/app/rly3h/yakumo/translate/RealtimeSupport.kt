package app.rly3h.yakumo.translate

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import app.rly3h.yakumo.ui.session.SAMPLE_RATE_24K
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel

/**
 * Shared plumbing for the online (WebSocket) speech-translation engines. The
 * OpenAI Realtime and Gemini Live protocols differ in framing, auth, and capture
 * rate, but they stream the same two things back — incremental source/target
 * transcript and translated audio — and render them the same way. These helpers
 * hold that protocol-agnostic half so each engine only implements its wire format.
 */

/**
 * Assembles streaming source/target transcript deltas into one [LiveTurn] row,
 * driving [TranslatorCallbacks] exactly like the offline engine does. A turn opens
 * on the first delta (either side), accumulates text in place, and closes on
 * [finalize] (the engine's end-of-turn signal or an idle gap).
 *
 * Thread-safe: deltas arrive on the WebSocket thread while an idle watchdog may
 * finalize concurrently, so all turn state is guarded by [lock].
 */
internal class RealtimeTurnAssembler(
  private val srcFlores: String,
  private val tgtFlores: String,
  private val cb: TranslatorCallbacks,
  // When the engine emits no explicit end-of-turn signal (Gemini Live streams
  // continuously), close a turn once its translation reaches sentence-final
  // punctuation so the log breaks into sentences instead of one endless row.
  private val splitOnSentenceEnd: Boolean = false,
) {
  private val nextId = AtomicLong(0L)
  private val lock = Any()
  private var currentTurnId: Long? = null
  private var srcAcc = StringBuilder()
  private var tgtAcc = StringBuilder()
  // Best-effort ASR language tag; only applied when a turn is first opened.
  private var detected = ""

  // Wall-clock of the last delta, for the idle-gap watchdog. nanoTime/1e6 avoids
  // wall-clock jumps; only deltas (not the absolute value) matter.
  @Volatile var lastDeltaAt = 0L
    private set

  fun sourceDelta(d: String, detectedLang: String = "") {
    if (d.isEmpty()) return
    synchronized(lock) {
      lastDeltaAt = nowMs()
      if (detectedLang.isNotBlank()) detected = detectedLang
      val id = ensureTurn()
      srcAcc.append(d)
      cb.onTurnUpdate(id, transcript = srcAcc.toString())
    }
  }

  fun targetDelta(d: String) {
    if (d.isEmpty()) return
    var finalizeAfter = false
    synchronized(lock) {
      lastDeltaAt = nowMs()
      val id = ensureTurn()
      tgtAcc.append(d)
      cb.onTurnUpdate(id, translation = tgtAcc.toString())
      finalizeAfter = splitOnSentenceEnd && endsSentence(tgtAcc)
    }
    if (finalizeAfter) finalize()
  }

  /** Close the open turn (if any) and ask the screen to persist it. */
  fun finalize() {
    synchronized(lock) {
      val id = currentTurnId ?: return
      cb.onTurnUpdate(id, transcript = srcAcc.toString(), translation = tgtAcc.toString())
      currentTurnId = null
    }
    cb.onPersist()
  }

  /** Finalize when a turn has been open and silent longer than [gapMs]. */
  fun finalizeIfIdle(gapMs: Long) {
    val open = synchronized(lock) { currentTurnId != null }
    if (open && nowMs() - lastDeltaAt > gapMs) finalize()
  }

  // Caller holds [lock].
  private fun ensureTurn(): Long {
    currentTurnId?.let { return it }
    val id = nextId.getAndIncrement()
    currentTurnId = id
    srcAcc = StringBuilder()
    tgtAcc = StringBuilder()
    cb.onTurnStart(LiveTurn(id, transcript = "", srcLang = srcFlores, tgtLang = tgtFlores, detected = detected))
    return id
  }

  // True when the buffer's last non-space char ends a sentence (JA and Latin marks).
  private fun endsSentence(sb: StringBuilder): Boolean {
    for (i in sb.length - 1 downTo 0) {
      val c = sb[i]
      if (c.isWhitespace()) continue
      return c in SENTENCE_END
    }
    return false
  }

  private fun nowMs(): Long = System.nanoTime() / 1_000_000

  private companion object {
    val SENTENCE_END = setOf('。', '．', '！', '？', '!', '?', '…', '.')
  }
}

/** Decodes a base64 audio delta; null for an empty or malformed payload so callers can drop it. */
internal fun decodePcmBase64(b64: String): ByteArray? {
  if (b64.isEmpty()) return null
  return runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()
}

/**
 * Streams translated audio (24 kHz PCM16, the output rate for both engines) to an
 * [AudioTrack]. Deltas are decoded on the WebSocket thread and drained by a single
 * blocking-write consumer ([playbackLoop]) so a slow write never stalls the socket.
 *
 * @param autoSpeak read per-chunk so muting mid-session takes effect immediately.
 */
internal class RealtimeAudioSink(private val autoSpeak: () -> Boolean) {
  private val channel = Channel<ByteArray>(Channel.UNLIMITED)

  /** Blocking consumer; run on its own IO coroutine until [close]. */
  suspend fun playbackLoop() {
    val track = buildAudioTrack()
    track.play()
    try {
      for (pcm in channel) {
        if (autoSpeak()) track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
      }
    } catch (_: Throwable) {
      // Channel closed on teardown; fall through to release.
    } finally {
      runCatching { track.stop() }
      runCatching { track.release() }
    }
  }

  fun enqueueBase64(b64: String) {
    val pcm = decodePcmBase64(b64) ?: return
    channel.trySend(pcm)
  }

  fun close() {
    runCatching { channel.close() }
  }

  private fun buildAudioTrack(): AudioTrack {
    val minBuf = AudioTrack.getMinBufferSize(
      SAMPLE_RATE_24K,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
    return AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build(),
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(SAMPLE_RATE_24K)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build(),
      )
      .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE_24K)) // ~0.5 s of headroom
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
  }
}
