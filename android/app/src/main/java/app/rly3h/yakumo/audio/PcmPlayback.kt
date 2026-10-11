package app.rly3h.yakumo.audio

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import app.rly3h.yakumo.ui.session.SAMPLE_RATE_24K
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Read by the capture thread: true while (or shortly after) audio plays on the built-in speaker. */
interface EchoGate {
  val speakerBusy: Boolean
}

/** A segment ends once its queue has stayed empty this long. */
private const val SEGMENT_IDLE_MS = 250L

/** Reverb allowance after the last speaker sample, so the mic does not hear the tail as speech. */
internal const val ECHO_TAIL_MS = 300L

/** Extra time granted to the drain wait on top of the audio's own duration. */
private const val DRAIN_SLACK_MS = 500L

private const val POLL_MS = 10L

/** The audience whose oldest queued chunk arrived first; null when both queues are empty. */
internal fun pickNextAudience(oldestMeAt: Long?, oldestPartnerAt: Long?): Audience? = when {
  oldestMeAt == null && oldestPartnerAt == null -> null
  oldestPartnerAt == null -> Audience.ME
  oldestMeAt == null -> Audience.PARTNER
  oldestPartnerAt < oldestMeAt -> Audience.PARTNER
  else -> Audience.ME
}

internal fun shouldEndSegment(emptyForMs: Long): Boolean = emptyForMs >= SEGMENT_IDLE_MS

/** True once the playback head has reached every frame that was written. */
internal fun isDrained(playedFrames: Long, writtenFrames: Long): Boolean = playedFrames >= writtenFrames

/**
 * Plays translated 24 kHz PCM16 mono audio from the two online sessions one
 * segment at a time, so each segment can be routed to its own output device.
 * One consumer ([run]) owns routing and the [AudioTrack]; [enqueue] never blocks.
 *
 * @param autoSpeak read per chunk so muting mid-session takes effect immediately.
 */
internal class PcmPlayback(
  private val router: AudioRouter,
  private val autoSpeak: () -> Boolean,
) : EchoGate {
  private class Chunk(val at: Long, val pcm: ByteArray)

  private val meQueue = ConcurrentLinkedQueue<Chunk>()
  private val partnerQueue = ConcurrentLinkedQueue<Chunk>()
  private val wake = Channel<Unit>(Channel.CONFLATED)

  @Volatile private var closed = false

  @Volatile
  override var speakerBusy = false
    private set

  /** Called from WebSocket threads. */
  fun enqueue(audience: Audience, pcm24k: ByteArray) {
    if (closed || pcm24k.isEmpty()) return
    queueOf(audience).add(Chunk(nowMs(), pcm24k))
    wake.trySend(Unit)
  }

  /** Single consumer; returns after [close]. */
  suspend fun run() = withContext(Dispatchers.IO) {
    while (!closed) {
      val audience = pickNextAudience(meQueue.peek()?.at, partnerQueue.peek()?.at)
      if (audience == null) {
        if (wake.receiveCatching().isClosed) break
        continue
      }
      if (!autoSpeak()) {
        queueOf(audience).clear()
        continue
      }
      try {
        playSegment(audience)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // A failed segment (e.g. the track could not be created) must not kill later ones.
        queueOf(audience).clear()
      }
    }
  }

  fun close() {
    closed = true
    wake.close()
    meQueue.clear()
    partnerQueue.clear()
  }

  private suspend fun playSegment(audience: Audience) {
    // A routing failure leaves the output unknown, so assume the speaker (the echo gate errs on muting).
    val output = runCatching { router.routeTo(audience) }.getOrDefault(ResolvedOutput.SPEAKER)
    val onSpeaker = output == ResolvedOutput.SPEAKER
    if (onSpeaker) speakerBusy = true
    try {
      // A fresh track per segment so it is opened on the device chosen just above, instead of
      // relying on a running track to follow a communication-device change.
      val track = buildAudioTrack()
      try {
        track.play()
        val frames = writeSegment(track, queueOf(audience))
        // Wait while still playing: stop() resets playbackHeadPosition to 0 on a non-offloaded track.
        awaitDrain(track, frames)
        track.stop()
      } finally {
        runCatching { track.release() }
      }
      if (onSpeaker && !closed) delay(ECHO_TAIL_MS)
    } finally {
      speakerBusy = false
    }
  }

  /** Writes queued chunks until the queue has been idle long enough; returns frames written. */
  private suspend fun writeSegment(track: AudioTrack, queue: ConcurrentLinkedQueue<Chunk>): Long {
    var frames = 0L
    var emptySince = -1L
    while (!closed) {
      val chunk = queue.poll()
      if (chunk == null) {
        val now = nowMs()
        if (emptySince < 0) emptySince = now
        if (shouldEndSegment(now - emptySince)) break
        delay(POLL_MS)
        continue
      }
      // Dropped (muted) chunks must not reset the idle timer, or muting mid-segment would keep
      // the segment, and speakerBusy, alive until the stream stops.
      if (!autoSpeak()) continue
      emptySince = -1L
      val written = track.write(chunk.pcm, 0, chunk.pcm.size, AudioTrack.WRITE_BLOCKING)
      if (written < 0) break
      frames += written / BYTES_PER_FRAME
    }
    return frames
  }

  private suspend fun awaitDrain(track: AudioTrack, frames: Long) {
    if (frames == 0L) return
    val deadline = nowMs() + frames * 1000 / SAMPLE_RATE_24K + DRAIN_SLACK_MS
    // The deadline caps the wait if the track stalls or never starts.
    while (!closed && !isDrained(playedFrames(track), frames) && nowMs() < deadline) {
      delay(POLL_MS)
    }
  }

  // playbackHeadPosition is a signed Int that is unsigned in meaning; a fresh track per segment
  // keeps it far from wrapping.
  private fun playedFrames(track: AudioTrack): Long = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

  private fun buildAudioTrack(): AudioTrack {
    val minBuf = AudioTrack.getMinBufferSize(
      SAMPLE_RATE_24K,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
    val track = AudioTrack.Builder()
      .setAudioAttributes(router.playbackAttributes)
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
    // A segment shorter than the default start threshold would otherwise never start playing;
    // lowering the threshold (API 31+) avoids padding the stream with silence. Older APIs start on play().
    if (Build.VERSION.SDK_INT >= 31) {
      runCatching { track.startThresholdInFrames = START_THRESHOLD_FRAMES }
    }
    return track
  }

  private fun queueOf(audience: Audience) = if (audience == Audience.ME) meQueue else partnerQueue

  private fun nowMs(): Long = System.nanoTime() / 1_000_000

  private companion object {
    const val BYTES_PER_FRAME = 2

    /** 20 ms at 24 kHz. */
    const val START_THRESHOLD_FRAMES = SAMPLE_RATE_24K / 50
  }
}
