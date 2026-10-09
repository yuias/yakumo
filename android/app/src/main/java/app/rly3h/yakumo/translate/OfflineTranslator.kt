package app.rly3h.yakumo.translate

import android.content.Context
import app.rly3h.yakumo.data.AsrMode
import app.rly3h.yakumo.data.Models
import app.rly3h.yakumo.data.MtModel
import app.rly3h.yakumo.data.Settings
import app.rly3h.yakumo.ui.session.InputMode
import app.rly3h.yakumo.ui.session.LanguagePair
import app.rly3h.yakumo.ui.session.rawChunkCaptureLoop
import app.rly3h.yakumo.ui.session.resolveDirection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.translatecore.TranslationSink
import uniffi.translatecore.asrRecognize
import uniffi.translatecore.asrStreamAccept
import uniffi.translatecore.asrStreamLoad
import uniffi.translatecore.asrStreamReset
import uniffi.translatecore.mtLoad
import uniffi.translatecore.mtTranslateStreaming
import uniffi.translatecore.vadAccept
import uniffi.translatecore.vadFlush
import uniffi.translatecore.vadLoad
import uniffi.translatecore.vadReset

/**
 * The on-device pipeline: continuous capture → (Silero VAD segments | streaming
 * recognizer) → SenseVoice/Nemotron ASR → GGUF translation (llama.cpp) → OS TTS.
 * Screen state is driven through [TranslatorCallbacks]. [asrMode] and [mtModel]
 * are snapshotted by the caller at session start.
 */
internal class OfflineTranslator(
  private val context: Context,
  private val settings: Settings,
  private val pair: LanguagePair,
  private val inputMode: InputMode,
  private val asrMode: AsrMode,
  private val mtModel: MtModel,
) : SpeechTranslator {
  private val asrDir = Models.dir(context, "asr")
  private val streamDir = Models.dir(context, "asr_stream")
  private val mtPath = modelFile(context, mtModel.modelId).absolutePath
  private val vadDir = Models.dir(context, "vad")

  private val running = AtomicBoolean(false)
  private val nextId = AtomicLong(0L)

  // Background model load. Launched on the caller's scope rather than as a child of
  // the session coroutine so a slow native load never delays onFinished; stop()
  // cancels it. The native call itself can't be interrupted, only its result dropped.
  @Volatile private var mtLoadJob: Job? = null

  override fun start(scope: CoroutineScope, callbacks: TranslatorCallbacks) {
    running.set(true)
    scope.launch {
      try {
        withContext(Dispatchers.IO) { releaseUnusedEngines(asrMode) }
        // Overlaps the weight load with the first utterance. Translation takes the
        // same engine mutex, so it is correct even if this hasn't finished yet.
        mtLoadJob = scope.launch(Dispatchers.IO) {
          try {
            mtLoad(mtPath)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Throwable) {
            withContext(Dispatchers.Main) { callbacks.onStatus("Error: ${e.message}") }
          }
        }
        if (!running.get()) mtLoadJob?.cancel() // stop() raced the launch
        when (asrMode) {
          AsrMode.STREAMING -> runStreaming(callbacks)
          AsrMode.SEGMENTED -> runSegmented(callbacks)
        }
        callbacks.onFinished(null)
      } catch (e: Throwable) {
        callbacks.onFinished(e.message)
      }
    }
  }

  override fun stop() {
    running.set(false)
    mtLoadJob?.cancel()
  }

  // Final transcript -> turn (shown at once) -> translation (patched in) ->
  // spoken. `lang` is the ASR language tag, empty for the streaming recognizer
  // (one stream carries both speakers, so the script heuristic picks the direction).
  private suspend fun finalizeTurn(transcript: String, lang: String, cb: TranslatorCallbacks) {
    val (srcOpt, tgtOpt) = resolveDirection(pair, inputMode, lang, transcript)
    val src = srcOpt.flores
    val tgt = tgtOpt.flores

    val id = nextId.getAndIncrement()
    withContext(Dispatchers.Main) {
      cb.onTurnStart(LiveTurn(id, transcript, src, tgt, lang, translation = null))
    }
    // Patch the same row as the model decodes, so the translation streams in word by
    // word. Compose snapshot state is safe to write from this background thread.
    val sink = object : TranslationSink {
      override fun onPartial(text: String) {
        cb.onTurnUpdate(id, translation = text)
      }
    }
    val translation = try {
      withContext(Dispatchers.IO) { mtTranslateStreaming(mtPath, transcript, src, tgt, sink) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      // Keep capturing: a failed translation (unsupported pair, missing file) only
      // costs this turn's translation, not the session.
      withContext(Dispatchers.Main) {
        // Clear the "…" placeholder so the row doesn't look like it's still translating.
        cb.onTurnUpdate(id, translation = "")
        cb.onStatus("Translation error: ${e.message}")
      }
      return
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

  // Streaming (Nemotron) path: feed raw mic chunks into the online recognizer,
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
