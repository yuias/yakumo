package app.rly3h.yakumo.translate

import android.content.Context
import app.rly3h.yakumo.data.Models
import app.rly3h.yakumo.data.Settings
import app.rly3h.yakumo.ui.session.InputMode
import app.rly3h.yakumo.ui.session.LanguagePair
import app.rly3h.yakumo.ui.session.rawChunkCaptureLoop
import app.rly3h.yakumo.ui.session.resolveDirection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.translatecore.TranslationSink
import uniffi.translatecore.asrRecognize
import uniffi.translatecore.asrStreamAccept
import uniffi.translatecore.asrStreamLoad
import uniffi.translatecore.asrStreamReset
import uniffi.translatecore.translateTextStreaming
import uniffi.translatecore.vadAccept
import uniffi.translatecore.vadFlush
import uniffi.translatecore.vadLoad
import uniffi.translatecore.vadReset

private const val ORT_DYLIB = "libonnxruntime.so"

/**
 * The on-device pipeline: continuous capture → (Silero VAD segments | streaming
 * recognizer) → SenseVoice/nemotron ASR → NLLB translation → OS TTS. Lifted
 * verbatim from the old NewSessionScreen so offline behavior is unchanged; the
 * only difference is that screen state is now driven through [TranslatorCallbacks].
 */
internal class OfflineTranslator(
  private val context: Context,
  private val settings: Settings,
  private val pair: LanguagePair,
  private val inputMode: InputMode,
  private val streamingAsr: Boolean,
) : SpeechTranslator {
  private val asrDir = Models.dir(context, "asr")
  private val streamDir = Models.dir(context, "asr_stream")
  private val nllbDir = Models.dir(context, "nllb")
  private val vadDir = Models.dir(context, "vad")

  private val running = AtomicBoolean(false)
  private val nextId = AtomicLong(0L)

  override fun start(scope: CoroutineScope, callbacks: TranslatorCallbacks) {
    running.set(true)
    scope.launch {
      try {
        if (streamingAsr) runStreaming(callbacks) else runSegmented(callbacks)
        callbacks.onFinished(null)
      } catch (e: Throwable) {
        callbacks.onFinished(e.message)
      }
    }
  }

  override fun stop() {
    running.set(false)
  }

  // Final transcript -> turn (shown at once) -> translation (patched in) ->
  // spoken. `lang` is the ASR language tag, empty for the English-only streaming
  // recognizer (then the script heuristic picks the direction).
  private suspend fun finalizeTurn(transcript: String, lang: String, cb: TranslatorCallbacks) {
    val (srcOpt, tgtOpt) = resolveDirection(pair, inputMode, lang, transcript)
    val src = srcOpt.flores
    val tgt = tgtOpt.flores

    val id = nextId.getAndIncrement()
    withContext(Dispatchers.Main) {
      cb.onTurnStart(LiveTurn(id, transcript, src, tgt, lang, translation = null))
    }
    // Patch the same row as NLLB decodes, so the translation streams in word by
    // word. Compose snapshot state is safe to write from this background thread.
    val sink = object : TranslationSink {
      override fun onPartial(text: String) {
        cb.onTurnUpdate(id, translation = text)
      }
    }
    val translation = withContext(Dispatchers.IO) {
      Models.ensure(context, "nllb")
      translateTextStreaming(nllbDir.absolutePath, transcript, src, tgt, ORT_DYLIB, sink)
    }
    withContext(Dispatchers.Main) {
      cb.onTurnUpdate(id, translation = translation)
      cb.onPersist()
    }
    cb.onSpeak(translation, tgt)
  }

  // Offline (SenseVoice) path: one VAD-cut segment -> transcript -> finalize.
  private suspend fun processSegment(pcm: ByteArray, cb: TranslatorCallbacks) {
    val asr = withContext(Dispatchers.IO) {
      Models.ensure(context, "asr")
      asrRecognize(asrDir.absolutePath, pcm, 16000)
    }
    val transcript = asr.text.trim()
    if (transcript.isEmpty()) return // drop non-speech segments
    finalizeTurn(transcript, asr.lang, cb)
  }

  // Capture and processing are decoupled by a channel so recording keeps running
  // while each finished segment is transcribed/translated. Raw ~100 ms chunks are
  // fed to the resident Silero VAD, which emits a clean PCM segment per detected
  // utterance; on stop we flush whatever was mid-sentence.
  private suspend fun runSegmented(cb: TranslatorCallbacks) = coroutineScope {
    val vad = settings.vadParams() // latest knobs at the start of this session
    val vadPath = vadDir.absolutePath
    withContext(Dispatchers.IO) {
      Models.ensure(context, "vad")
      vadLoad(vadPath, vad.threshold, vad.minSilenceMs / 1000f, vad.minSpeechMs / 1000f, vad.maxSpeechMs / 1000f)
      vadReset(vadPath)
    }
    val channel = Channel<ByteArray>(Channel.UNLIMITED)
    val capture = launch(Dispatchers.IO) {
      try {
        rawChunkCaptureLoop(running) { chunk ->
          try {
            for (seg in vadAccept(vadPath, chunk, 16000)) channel.trySend(seg)
          } catch (_: Throwable) { /* drop a bad chunk; keep recording */ }
        }
      } finally {
        // Emit any utterance still in progress when recording stopped.
        runCatching { for (seg in vadFlush(vadPath)) channel.trySend(seg) }
        channel.close()
      }
    }
    val consumer = launch(Dispatchers.IO) {
      for (seg in channel) {
        try {
          processSegment(seg, cb)
        } catch (e: Throwable) {
          withContext(Dispatchers.Main) { cb.onStatus("Error: ${e.message}") }
        }
      }
    }
    capture.join()
    consumer.join()
  }

  // Streaming (nemotron-en) path: feed raw mic chunks into the online recognizer,
  // show its partial transcript live, and finalize each utterance on its endpoint.
  private suspend fun runStreaming(cb: TranslatorCallbacks) = coroutineScope {
    val ep = settings.endpointParams() // latest knobs at the start of this session
    withContext(Dispatchers.IO) {
      asrStreamLoad(streamDir.absolutePath, ep.rule1, ep.rule2, ep.rule3, "auto")
      asrStreamReset(streamDir.absolutePath)
    }
    val channel = Channel<ByteArray>(Channel.UNLIMITED)
    // Endpointed utterances hand off here so translation (blocking, ~1.3s) runs on
    // its own worker instead of inside the ASR loop. Otherwise the loop would stop
    // draining mic chunks while translating, and the live partial for the next
    // utterance would only appear in one burst once translation finished.
    val finalized = Channel<String>(Channel.UNLIMITED)
    val capture = launch(Dispatchers.IO) {
      try { rawChunkCaptureLoop(running) { channel.trySend(it) } } finally { channel.close() }
    }
    // Single worker: sequential so turns commit in spoken order.
    val translator = launch(Dispatchers.IO) {
      for (t in finalized) finalizeTurn(t, "", cb)
    }
    val consumer = launch(Dispatchers.IO) {
      var current = ""
      for (chunk in channel) {
        val r = try {
          asrStreamAccept(streamDir.absolutePath, chunk, 16000)
        } catch (e: Throwable) {
          withContext(Dispatchers.Main) { cb.onStatus("Error: ${e.message}") }
          continue
        }
        current = r.text
        withContext(Dispatchers.Main) { cb.onPartial(r.text) }
        if (r.endpoint) {
          val t = r.text.trim()
          current = ""
          withContext(Dispatchers.Main) { cb.onPartial("") }
          if (t.isNotEmpty()) finalized.trySend(t)
        }
      }
      // Stopped mid-utterance: flush whatever was decoded so far.
      val tail = current.trim()
      withContext(Dispatchers.Main) { cb.onPartial("") }
      if (tail.isNotEmpty()) finalized.trySend(tail)
      finalized.close()
      withContext(Dispatchers.IO) { asrStreamReset(streamDir.absolutePath) }
    }
    capture.join()
    consumer.join()
    translator.join() // drain any translations still in flight after Stop
  }
}
