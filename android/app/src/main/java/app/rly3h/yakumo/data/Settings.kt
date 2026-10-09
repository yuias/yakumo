package app.rly3h.yakumo.data

import android.content.Context
import app.rly3h.yakumo.ui.session.Conversation
import app.rly3h.yakumo.ui.session.EndpointParams
import app.rly3h.yakumo.ui.session.VadParams
import app.rly3h.yakumo.ui.session.languageByFlores

/** The online speech-translation backend the user picks for Online mode. */
enum class OnlineProvider { OPENAI, GEMINI }

/** Exclusive offline recognizer choice: VAD-cut SenseVoice segments or the live Nemotron stream. */
enum class AsrMode { SEGMENTED, STREAMING }

/** Offline translation model; [modelId] is the models.json entry holding its GGUF. */
enum class MtModel(val modelId: String) { LFM2("mt_lfm2"), HYMT2("mt_hymt2") }

/** Stored mode wins; otherwise the legacy streamingAsr boolean; otherwise SEGMENTED. */
internal fun migrateAsrMode(stored: String?, legacyStreaming: Boolean?): AsrMode =
  stored?.let { name -> AsrMode.entries.firstOrNull { it.name == name } }
    ?: if (legacyStreaming == true) AsrMode.STREAMING else AsrMode.SEGMENTED

/** Models the current selection needs, in download order. */
internal fun requiredModelIds(mode: AsrMode, mt: MtModel): List<String> = when (mode) {
  AsrMode.SEGMENTED -> listOf("vad", "asr", mt.modelId)
  AsrMode.STREAMING -> listOf("asr_stream", mt.modelId)
}

/** Lightweight user settings backed by SharedPreferences (no extra deps). */
class Settings(context: Context) {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  var speechRate: Float
    get() = prefs.getFloat(KEY_RATE, 1.0f)
    set(v) = prefs.edit().putFloat(KEY_RATE, v).apply()

  var autoSpeak: Boolean
    get() = prefs.getBoolean(KEY_AUTO_SPEAK, true)
    set(v) = prefs.edit().putBoolean(KEY_AUTO_SPEAK, v).apply()

  // Reads the legacy streamingAsr boolean only while no explicit mode has been saved.
  // Writing the mode drops the legacy key so it can't resurface later.
  var asrMode: AsrMode
    get() = migrateAsrMode(
      prefs.getString(KEY_ASR_MODE, null),
      if (prefs.contains(KEY_STREAMING_ASR)) prefs.getBoolean(KEY_STREAMING_ASR, false) else null,
    )
    set(v) = prefs.edit().putString(KEY_ASR_MODE, v.name).remove(KEY_STREAMING_ASR).apply()

  /** Offline translation model; LFM2 by default, and an unknown stored value also falls back to it. */
  var mtModel: MtModel
    get() = MtModel.entries.firstOrNull { it.name == prefs.getString(KEY_MT_MODEL, null) } ?: MtModel.LFM2
    set(v) = prefs.edit().putString(KEY_MT_MODEL, v.name).apply()

  fun requiredModelIds(): List<String> = requiredModelIds(asrMode, mtModel)

  // --- Online mode ---
  // Last-used engine toggle (mic-side switch). Offline by default; only honored
  // when an API key is set and the network is up (checked at the call site).
  var onlineEnabled: Boolean
    get() = prefs.getBoolean(KEY_ONLINE, false)
    set(v) = prefs.edit().putBoolean(KEY_ONLINE, v).apply()

  /**
   * Silence (ms) after the last streamed delta that closes an open online turn.
   * Shared by both online engines; the main fallback splitter for Gemini (which
   * sends no end-of-turn signal) and the primary one for any stretch without
   * sentence punctuation. Defaults to [DEFAULT_ONLINE_IDLE_GAP_MS].
   */
  var onlineIdleGapMs: Int
    get() = prefs.getInt(KEY_ONLINE_IDLE_GAP, DEFAULT_ONLINE_IDLE_GAP_MS)
    set(v) = prefs.edit().putInt(KEY_ONLINE_IDLE_GAP, v).apply()

  /** Which backend Online mode uses. OpenAI by default for backward compatibility. */
  var onlineProvider: OnlineProvider
    get() = runCatching { OnlineProvider.valueOf(prefs.getString(KEY_PROVIDER, OnlineProvider.OPENAI.name)!!) }
      .getOrDefault(OnlineProvider.OPENAI)
    set(v) = prefs.edit().putString(KEY_PROVIDER, v.name).apply()

  // Keys are stored only as Tink ciphertext; the plaintext never touches prefs.
  // Each provider has its own slot (the OpenAI key and the Google key are distinct).
  private fun cipherPrefKey(provider: OnlineProvider): String = when (provider) {
    OnlineProvider.OPENAI -> KEY_API_KEY_CIPHER
    OnlineProvider.GEMINI -> KEY_GEMINI_KEY_CIPHER
  }

  private fun apiKeyCipher(provider: OnlineProvider): String? = prefs.getString(cipherPrefKey(provider), null)

  private fun setApiKeyCipher(provider: OnlineProvider, cipher: String?) {
    val key = cipherPrefKey(provider)
    prefs.edit().apply { if (cipher == null) remove(key) else putString(key, cipher) }.apply()
  }

  fun hasApiKey(provider: OnlineProvider): Boolean = !apiKeyCipher(provider).isNullOrBlank()

  fun setApiKey(context: Context, provider: OnlineProvider, plaintext: String) {
    setApiKeyCipher(provider, SecureKeyStore.encrypt(context, plaintext))
  }

  /** Decrypted key for [provider], or null if unset/undecryptable (treated as "no key"). */
  fun apiKey(context: Context, provider: OnlineProvider): String? =
    apiKeyCipher(provider)?.let { SecureKeyStore.decrypt(context, it) }

  fun clearApiKey(provider: OnlineProvider) {
    setApiKeyCipher(provider, null)
  }

  // --- Conversation languages (FLORES codes) ---
  // The user's own language is fixed here; the partner's language is chosen per
  // session on the New Session screen (null = auto-detect).

  /** The user's own language. Defaults to the device locale, falling back to English. */
  var myLangFlores: String
    get() = prefs.getString(KEY_MY_LANG, defaultMyLangFlores)!!
    set(v) = prefs.edit().putString(KEY_MY_LANG, v).apply()

  /** The partner's language, or null to auto-detect it (the default). */
  var partnerFlores: String?
    get() = prefs.getString(KEY_PARTNER, null)
    set(v) = prefs.edit().apply { if (v == null) remove(KEY_PARTNER) else putString(KEY_PARTNER, v) }.apply()

  /**
   * Current conversation view. A partner equal to the user's own language is
   * treated as auto-detect, so a stale pin can't collapse the pair.
   */
  internal fun conversation(): Conversation {
    val mine = languageByFlores(myLangFlores)
    val them = partnerFlores?.let { languageByFlores(it) }?.takeIf { it != mine }
    return Conversation(mine, them)
  }

  // FLORES default keyed off the device language (Japanese device -> Japanese user).
  private val defaultMyLangFlores: String =
    if (java.util.Locale.getDefault().language == "ja") "jpn_Jpan" else "eng_Latn"

  // --- Silero VAD knobs (defaults come from VadParams) ---
  private val def = VadParams()

  var vadThreshold: Float
    get() = prefs.getFloat(KEY_VAD_THRESH, def.threshold)
    set(v) = prefs.edit().putFloat(KEY_VAD_THRESH, v).apply()

  var vadMinSilenceMs: Int
    get() = prefs.getInt(KEY_VAD_SILENCE, def.minSilenceMs)
    set(v) = prefs.edit().putInt(KEY_VAD_SILENCE, v).apply()

  var vadMinSpeechMs: Int
    get() = prefs.getInt(KEY_VAD_MIN_SPEECH, def.minSpeechMs)
    set(v) = prefs.edit().putInt(KEY_VAD_MIN_SPEECH, v).apply()

  var vadMaxSpeechMs: Int
    get() = prefs.getInt(KEY_VAD_MAX_SPEECH, def.maxSpeechMs)
    set(v) = prefs.edit().putInt(KEY_VAD_MAX_SPEECH, v).apply()

  fun vadParams(): VadParams =
    VadParams(
      threshold = vadThreshold,
      minSilenceMs = vadMinSilenceMs,
      minSpeechMs = vadMinSpeechMs,
      maxSpeechMs = vadMaxSpeechMs,
    )

  /** Restores the VAD knobs to their defaults. */
  fun resetVad() {
    vadThreshold = def.threshold
    vadMinSilenceMs = def.minSilenceMs
    vadMinSpeechMs = def.minSpeechMs
    vadMaxSpeechMs = def.maxSpeechMs
  }

  // --- Streaming endpoint rules (defaults come from EndpointParams) ---
  private val epDef = EndpointParams()

  var endpointRule1: Float
    get() = prefs.getFloat(KEY_EP_RULE1, epDef.rule1)
    set(v) = prefs.edit().putFloat(KEY_EP_RULE1, v).apply()

  var endpointRule2: Float
    get() = prefs.getFloat(KEY_EP_RULE2, epDef.rule2)
    set(v) = prefs.edit().putFloat(KEY_EP_RULE2, v).apply()

  var endpointRule3: Float
    get() = prefs.getFloat(KEY_EP_RULE3, epDef.rule3)
    set(v) = prefs.edit().putFloat(KEY_EP_RULE3, v).apply()

  fun endpointParams(): EndpointParams =
    EndpointParams(rule1 = endpointRule1, rule2 = endpointRule2, rule3 = endpointRule3)

  /** Restores the endpoint rules to their defaults. */
  fun resetEndpoint() {
    endpointRule1 = epDef.rule1
    endpointRule2 = epDef.rule2
    endpointRule3 = epDef.rule3
  }

  private companion object {
    const val KEY_RATE = "speechRate"
    const val KEY_AUTO_SPEAK = "autoSpeak"
    const val KEY_STREAMING_ASR = "streamingAsr" // legacy; superseded by KEY_ASR_MODE
    const val KEY_ASR_MODE = "asrMode"
    const val KEY_MT_MODEL = "mtModel"
    const val KEY_ONLINE = "onlineEnabled"
    const val KEY_PROVIDER = "onlineProvider"
    const val KEY_ONLINE_IDLE_GAP = "onlineIdleGapMs"
    const val DEFAULT_ONLINE_IDLE_GAP_MS = 1200
    const val KEY_API_KEY_CIPHER = "apiKeyCipher" // OpenAI (legacy key name kept for compat)
    const val KEY_GEMINI_KEY_CIPHER = "geminiApiKeyCipher"
    const val KEY_MY_LANG = "myLangFlores"
    const val KEY_PARTNER = "partnerFlores"
    // Silero-era keys (distinct from the old RMS knobs so stale values don't leak).
    const val KEY_VAD_THRESH = "vadProbThreshold"
    const val KEY_VAD_SILENCE = "vadMinSilenceMs"
    const val KEY_VAD_MIN_SPEECH = "vadMinSpeechMs"
    const val KEY_VAD_MAX_SPEECH = "vadMaxSpeechMs"
    const val KEY_EP_RULE1 = "endpointRule1"
    const val KEY_EP_RULE2 = "endpointRule2"
    const val KEY_EP_RULE3 = "endpointRule3"
  }
}
