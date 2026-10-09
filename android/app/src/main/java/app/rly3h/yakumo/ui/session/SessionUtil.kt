package app.rly3h.yakumo.ui.session

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.Locale

// Script heuristic: kana/kanji -> Japanese. Fallback when ASR gives no language.
internal fun isJapanese(text: String): Boolean =
  text.any { it in '぀'..'ヿ' || it in '一'..'鿿' }

/**
 * A language the translation pair can be set to. The single source of truth for
 * everything language-specific; supporting one more language is one more row.
 *
 * @param flores FLORES-200 code; the MT core maps it to a language name
 * @param asrTag substring of the SenseVoice tag ("<|en|>" contains "en")
 * @param label  full name shown in the picker
 * @param short  two-letter badge shown on a turn card
 * @param locale OS TTS locale used to read the translation aloud
 */
internal data class LanguageOption(
  val flores: String,
  val asrTag: String,
  val label: String,
  val short: String,
  val locale: Locale,
)

// Currently EN<->JA only; both directions of every pair must be ASR-recognizable
// (SenseVoice) and ideally TTS-speakable (OS). Add a row to widen the set.
internal val LANGUAGES: List<LanguageOption> = listOf(
  LanguageOption("eng_Latn", "en", "English", "EN", Locale.ENGLISH),
  LanguageOption("jpn_Jpan", "ja", "Japanese", "JA", Locale.JAPANESE),
)

internal fun languageByFlores(flores: String): LanguageOption =
  LANGUAGES.firstOrNull { it.flores == flores } ?: LANGUAGES.first()

/** The two languages of a conversation; auto-swap translates between them. */
internal data class LanguagePair(val a: LanguageOption, val b: LanguageOption)

/** How the spoken side of the pair is chosen for an utterance. */
internal enum class InputMode { AUTO, FORCE_A, FORCE_B }

/**
 * A conversation framed from the user's seat: [mine] is fixed (set once in
 * Settings), [partner] is the other speaker. A null partner means "auto-detect
 * the partner's language" — the common case for live, two-way conversation.
 *
 * This is the screen/settings-level model; it collapses to a [LanguagePair] +
 * [InputMode] at the translator boundary via [toPair] so the engines stay
 * unaware of the my/partner framing.
 */
internal data class Conversation(val mine: LanguageOption, val partner: LanguageOption?)

/** Languages the partner could be: every supported language except the user's own. */
internal fun partnerOptions(mine: LanguageOption): List<LanguageOption> =
  LANGUAGES.filter { it != mine }

/**
 * Collapse the my/partner view into the translator's [LanguagePair]. The partner
 * is `a` and the user is `b` deliberately: the online engine pins its target to
 * `b`, so the user's own language is always the side translated *into* (e.g. the
 * primary EN→JA "listen to the partner" flow). Direction stays [InputMode.AUTO];
 * per-utterance detection picks the spoken side. An auto (null) partner resolves
 * to the single other supported language — detection still runs.
 */
internal fun Conversation.toPair(): LanguagePair {
  val them = partner ?: partnerOptions(mine).firstOrNull() ?: mine
  return LanguagePair(a = them, b = mine)
}

/**
 * Decide (source, target) for one utterance in the bidirectional pair.
 * AUTO detects which side was spoken (ASR tag first, kana/kanji script as a
 * fallback) and translates to the other; FORCE_* overrides that detection.
 * Unrecognized input defaults to a -> b.
 */
internal fun resolveDirection(
  pair: LanguagePair,
  mode: InputMode,
  asrTag: String,
  transcript: String,
): Pair<LanguageOption, LanguageOption> = when (mode) {
  InputMode.FORCE_A -> pair.a to pair.b
  InputMode.FORCE_B -> pair.b to pair.a
  InputMode.AUTO -> if (detectSide(pair, asrTag, transcript) == pair.b) pair.b to pair.a else pair.a to pair.b
}

private fun detectSide(pair: LanguagePair, asrTag: String, transcript: String): LanguageOption {
  // Trust the ASR tag when present, but only if it names a side of this pair.
  if (asrTag.isNotBlank()) {
    listOf(pair.a, pair.b).firstOrNull { asrTag.contains(it.asrTag) }?.let { return it }
  }
  // No usable tag (the streaming recognizer reports no language): fall back to a
  // script check, which can only distinguish Japanese.
  val jp = listOf(pair.a, pair.b).firstOrNull { it.flores == "jpn_Jpan" }
  return if (jp != null && isJapanese(transcript)) jp else pair.a
}

// FLORES code -> Locale for the OS TTS engine.
internal fun localeFromFlores(code: String): Locale = languageByFlores(code).locale

internal fun labelForFlores(code: String): String = languageByFlores(code).short

// FLORES code -> online translation-target language code. OpenAI Realtime and
// Gemini Live both take the same short codes ("ja"/"en"). Maps the few languages
// we support; unknown codes fall back to the English target.
internal fun floresToLiveLang(code: String): String = when (code) {
  "jpn_Jpan" -> "ja"
  "eng_Latn" -> "en"
  else -> "en"
}

// --- Continuous capture ---
// The offline paths capture 16 kHz mono in 100 ms chunks; the online paths use
// 40 ms chunks (OpenAI Realtime wants 24 kHz, Gemini Live 16 kHz). Segmentation
// happens downstream (Silero on the Rust side, sherpa's endpointer, or the
// online model), not here.
internal const val SAMPLE_RATE_16K = 16000
internal const val SAMPLE_RATE_24K = 24000

// Offline chunks feed VAD/ASR, which gain nothing from smaller frames. Online chunks
// are as small as is still cheap per WebSocket frame, to cut first-audio latency.
internal const val OFFLINE_CHUNK_MS = 100
internal const val ONLINE_CHUNK_MS = 40

/**
 * User-tunable Silero VAD knobs (persisted in Settings). `threshold` is the
 * speech-probability cutoff (0..1; lower = more sensitive); the durations are in
 * milliseconds here for the sliders and converted to seconds at the FFI boundary.
 */
data class VadParams(
  val threshold: Float = 0.5f, // speech probability; above = voiced
  val minSilenceMs: Int = 500, // trailing silence that ends a segment
  val minSpeechMs: Int = 250, // ignore blips shorter than this
  val maxSpeechMs: Int = 15000, // force-cut very long utterances
)

// Sensible, processing-appropriate bounds for the Settings sliders.
object VadBounds {
  val threshold = 0.1f..0.9f
  val minSilenceMs = 100f..1500f
  val minSpeechMs = 50f..1000f
  val maxSpeechMs = 5000f..30000f
}

/**
 * Endpoint rules for the streaming recognizer (seconds). These map to sherpa's
 * rule1/2/3 and are baked into the recognizer at creation. `rule2` (trailing
 * silence after speech) is the main "end of turn" knob.
 */
data class EndpointParams(
  val rule1: Float = 2.4f, // trailing silence before any speech is decoded
  val rule2: Float = 1.2f, // trailing silence after speech -> ends a turn
  val rule3: Float = 20.0f, // max utterance length, force-cut
)

object EndpointBounds {
  val rule1 = 0.5f..5.0f
  val rule2 = 0.3f..3.0f
  val rule3 = 5.0f..30.0f
}

/** Slider bounds for the online engines' idle-gap turn splitter (milliseconds). */
object OnlineBounds {
  val idleGapMs = 500f..3000f
}

/** True if AudioRecord can capture at [sampleRate] mono PCM16 on this device. */
internal fun supportsCaptureRate(sampleRate: Int): Boolean {
  val n = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
  return n != AudioRecord.ERROR && n != AudioRecord.ERROR_BAD_VALUE && n > 0
}

/**
 * Nearest-neighbor-free linear upsample of 16 kHz PCM16 (LE bytes) to 24 kHz.
 * Cold fallback used only when a device can't open a 24 kHz AudioRecord; the OS
 * resampler (native 24 kHz capture) is preferred for the 3:2 non-integer ratio.
 */
internal fun linearResample16to24(src: ByteArray): ByteArray {
  val inN = src.size / 2
  if (inN == 0) return ByteArray(0)
  val outN = (inN * 3) / 2 // 16k -> 24k
  val out = ByteArray(outN * 2)
  fun sample(i: Int): Int {
    val j = i.coerceIn(0, inN - 1) * 2
    return (src[j].toInt() and 0xFF) or (src[j + 1].toInt() shl 8)
  }
  for (o in 0 until outN) {
    val pos = o * (inN - 1).toFloat() / (outN - 1).coerceAtLeast(1)
    val i0 = pos.toInt()
    val frac = pos - i0
    val v = (sample(i0) * (1 - frac) + sample(i0 + 1) * frac).toInt()
    out[o * 2] = (v and 0xFF).toByte()
    out[o * 2 + 1] = ((v shr 8) and 0xFF).toByte()
  }
  return out
}

/**
 * Continuous capture emitting fixed [chunkMs] PCM16 chunks until `running` goes
 * false. Used by the offline segmented path (chunks fed to Silero VAD) and the
 * streaming recognizer (its own endpointer), both at [OFFLINE_CHUNK_MS], and by
 * the online Realtime path at [ONLINE_CHUNK_MS].
 * [audioSource] is VOICE_COMMUNICATION online (hardware AEC against the played
 * translation) and VOICE_RECOGNITION offline. Blocking — run on IO.
 */
internal fun rawChunkCaptureLoop(
  running: java.util.concurrent.atomic.AtomicBoolean,
  sampleRate: Int = SAMPLE_RATE_16K,
  audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
  chunkMs: Int = OFFLINE_CHUNK_MS,
  emit: (ByteArray) -> Unit,
) {
  val minBuf = AudioRecord.getMinBufferSize(
    sampleRate,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
  )
  val record = AudioRecord(
    audioSource,
    sampleRate,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    maxOf(minBuf, sampleRate * 2),
  )
  // PCM16 is little-endian on every Android ABI, so the byte read is the wire format.
  val buf = ByteArray(sampleRate * chunkMs / 1000 * 2)
  try {
    record.startRecording()
    while (running.get()) {
      val n = record.read(buf, 0, buf.size)
      val len = n and 1.inv() // never emit half a sample
      // Copy: consumers queue chunks while the next read reuses buf.
      if (len > 0) emit(buf.copyOf(len))
    }
  } finally {
    record.stop()
    record.release()
  }
}
