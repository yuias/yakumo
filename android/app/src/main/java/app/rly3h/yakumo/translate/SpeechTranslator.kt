package app.rly3h.yakumo.translate

import kotlinx.coroutines.CoroutineScope

/** Who spoke a turn in Conversation mode; null in Listen mode. */
enum class Speaker { ME, PARTNER }

/**
 * One conversation turn as shown in the session UI: the recognized source text
 * and (once ready) its translation. `translation == null` means "still in
 * flight" — the row renders the transcript immediately and fills the translation
 * in later. Shared by the offline and online engines so the screen is unchanged.
 *
 * @param srcLang/tgtLang FLORES-200 codes (the UI maps them to badges/locales)
 * @param detected the raw ASR language tag, empty when the engine doesn't report one
 * @param speaker who spoke the turn in Conversation mode; null keeps Listen rows unchanged
 */
data class LiveTurn(
  val id: Long,
  val transcript: String,
  val srcLang: String,
  val tgtLang: String,
  val detected: String,
  val translation: String? = null,
  val speaker: Speaker? = null,
)

/**
 * The seam between an engine and the session screen. Each method maps 1:1 onto a
 * single piece of screen state, so an engine drives the UI without knowing about
 * Compose. Implementations may invoke these from background threads; the screen's
 * adapter is responsible for thread-safe state updates.
 */
interface TranslatorCallbacks {
  /** A new turn began; append it (transcript shown, translation pending). */
  fun onTurnStart(turn: LiveTurn)

  /** Patch an existing turn in place (streaming transcript and/or translation). */
  fun onTurnUpdate(id: Long, transcript: String? = null, translation: String? = null)

  /** Live, not-yet-finalized transcript (streaming recognizers); "" clears it. */
  fun onPartial(text: String)

  /** Human-readable status line under the mic. */
  fun onStatus(text: String)

  /** Speak [text] in [tgtFlores] via the OS TTS (offline path only). */
  fun onSpeak(text: String, tgtFlores: String)

  /** Persist the session after a turn completes. */
  fun onPersist()

  /** The engine stopped; [error] is non-null if it ended abnormally. */
  fun onFinished(error: String?)
}

/**
 * A pluggable speech-translation engine. `start` is non-blocking and spawns its
 * work on [scope]; `stop` requests a clean shutdown (idempotent).
 */
interface SpeechTranslator {
  fun start(scope: CoroutineScope, callbacks: TranslatorCallbacks)
  fun stop()
}

/** Conversation-mode engine: direction is set explicitly by the Speaking control. */
internal interface ConversationTranslator : SpeechTranslator {
  /** true = the user is speaking (mine -> partner). Safe to call from the main thread at any time. */
  fun setSpeaking(speaking: Boolean)
}

/** An online engine that can take mic audio from a shared capture instead of its own AudioRecord. */
internal interface FeedableTranslator : SpeechTranslator {
  /** Sample rate [feed] expects (OpenAI 24000, Gemini 16000). */
  val inputSampleRate: Int

  /** PCM16 LE mono at [inputSampleRate]; buffered until the session is ready, like the own capture. */
  fun feed(chunk: ByteArray)
}
