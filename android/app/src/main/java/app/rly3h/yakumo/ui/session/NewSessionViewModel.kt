package app.rly3h.yakumo.ui.session

import android.app.Application
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rly3h.yakumo.audio.AudioRouters
import app.rly3h.yakumo.data.AsrMode
import app.rly3h.yakumo.data.LoggedUtterance
import app.rly3h.yakumo.data.Models
import app.rly3h.yakumo.data.OnlineProvider
import app.rly3h.yakumo.data.SessionLog
import app.rly3h.yakumo.data.SessionMode
import app.rly3h.yakumo.data.SessionStore
import app.rly3h.yakumo.RecordingService
import app.rly3h.yakumo.data.Settings
import app.rly3h.yakumo.translate.ConversationTranslator
import app.rly3h.yakumo.translate.GeminiTranslator
import app.rly3h.yakumo.translate.LiveTurn
import app.rly3h.yakumo.translate.OfflineConversationTranslator
import app.rly3h.yakumo.translate.OfflineTranslator
import app.rly3h.yakumo.translate.OnlineConversationTranslator
import app.rly3h.yakumo.translate.OpenAiTranslator
import app.rly3h.yakumo.translate.SpeechTranslator
import app.rly3h.yakumo.translate.TranslatorCallbacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uniffi.translatecore.mtSupported

private const val IDLE_STATUS = "Tap the mic and speak (EN or JA)."
private const val CONVERSATION_IDLE_STATUS = "Tap the mic, then use Speaking to choose who talks."

// A Conversation mode saved on a newer device (or restored from a backup) must not
// leave an API < 31 device on a mode it cannot start.
private fun availableMode(stored: SessionMode) =
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) SessionMode.LISTEN else stored

private fun idleStatusFor(mode: SessionMode) =
  if (mode == SessionMode.CONVERSATION) CONVERSATION_IDLE_STATUS else IDLE_STATUS

/**
 * Owns a live session: its turns, the running engine, and the TTS handle.
 *
 * This lives in a ViewModel rather than the composable because the engine
 * outlives the screen — the capture coroutine runs on [viewModelScope], so
 * opening the drawer, rotating, or stepping into Settings no longer tears the
 * session down mid-turn. The screen scopes it to the Activity for that reason;
 * a fresh session is an explicit action ([reset]), not a side effect of
 * navigation.
 */
internal class NewSessionViewModel(app: Application) : AndroidViewModel(app) {
  private val settings = Settings(app)

  private var startedAt = System.currentTimeMillis()
  private var sessionId = SessionStore.newId(startedAt)

  val turns = mutableStateListOf<LiveTurn>() // chronological: newest last
  var status by mutableStateOf(idleStatusFor(availableMode(settings.sessionMode)))
    private set
  var partial by mutableStateOf("") // live transcript (streaming mode)
    private set
  var recording by mutableStateOf(false)
    private set

  var autoSpeak by mutableStateOf(settings.autoSpeak)
    private set
  var online by mutableStateOf(settings.onlineEnabled)
    private set

  // The user's own language is fixed in Settings; the partner's is chosen on the
  // screen, null meaning auto-detect. Re-read on screen entry (see [refresh]) so
  // a change made in Settings lands on the next idle session.
  var mine by mutableStateOf(settings.conversation().mine)
    private set
  var partner by mutableStateOf(settings.conversation().partner)
    private set

  // Conversation mode: its own partner (never auto) and the Speaking direction control.
  var mode by mutableStateOf(availableMode(settings.sessionMode))
    private set
  var conversationPartner by mutableStateOf(settings.conversationPartner(mine))
    private set
  var speaking by mutableStateOf(false)
    private set

  private var tts: TextToSpeech? = null
  private var translator: SpeechTranslator? = null

  init {
    tts = TextToSpeech(app) { }
  }

  /** Picks up language settings changed elsewhere. Ignored mid-session. */
  fun refresh() {
    if (recording) return
    val conversation = settings.conversation()
    mine = conversation.mine
    partner = conversation.partner
    conversationPartner = settings.conversationPartner(mine)
  }

  /**
   * Ignored mid-session. Turns of the other mode are cleared first so one saved log
   * never mixes Listen and Conversation (which also keeps [mode] valid for [persist]).
   */
  fun onModeChange(value: SessionMode) {
    if (recording || value == mode) return
    if (turns.isNotEmpty()) reset()
    mode = value
    settings.sessionMode = value
    status = idleStatusFor(value)
  }

  fun onConversationPartnerChange(value: LanguageOption) {
    conversationPartner = value
    settings.conversationPartnerFlores = value.flores
  }

  fun onSpeakingChange(value: Boolean) {
    speaking = value
    (translator as? ConversationTranslator)?.setSpeaking(value)
  }

  fun onPartnerChange(value: LanguageOption?) {
    partner = value
    settings.partnerFlores = value?.flores
  }

  fun onAutoSpeakChange(value: Boolean) {
    autoSpeak = value
    settings.autoSpeak = value
  }

  fun onOnlineChange(value: Boolean) {
    online = value
    settings.onlineEnabled = value
  }

  /**
   * Drops to offline because the network or the key went away. Deliberately does
   * not persist: a tunnel shouldn't rewrite the user's saved engine preference.
   */
  fun forceOffline() {
    online = false
  }

  /** Drops the accumulated turns and starts logging under a fresh session id. */
  fun reset() {
    if (recording) return
    turns.clear()
    partial = ""
    startedAt = System.currentTimeMillis()
    sessionId = SessionStore.newId(startedAt)
    status = idleStatusFor(mode)
  }

  /**
   * Starts capture. [canGoOnline] carries the screen's live network/key check;
   * the engine choice is snapshotted for the whole session, so toggling mid-run
   * is not supported (mirrors the ASR mode and MT model capture-once rule).
   */
  fun start(canGoOnline: Boolean) {
    if (recording) return
    if (mode == SessionMode.CONVERSATION) {
      // Conversation routing needs the API 31 communication-device calls.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) startConversation(canGoOnline)
      else status = "Conversation needs Android 12 or later."
      return
    }
    val app = getApplication<Application>()
    val useOnline = online && canGoOnline
    val asrMode = settings.asrMode
    val mtModel = settings.mtModel
    if (!useOnline) {
      if (!mtSupported()) {
        status = "Offline translation isn't supported on this device's CPU. Use Online mode."
        return
      }
      if (settings.requiredModelIds().any { !Models.isPresent(app, it) }) {
        status = "Models missing — download them in Settings → Models."
        return
      }
    }
    // Direction is always auto-detected per utterance; the partner choice only
    // resolves which language sits opposite the user.
    val pair = Conversation(mine, partner).toPair()
    val engine: SpeechTranslator = when {
      useOnline && settings.onlineProvider == OnlineProvider.GEMINI ->
        GeminiTranslator(app, settings, pair, InputMode.AUTO)
      useOnline -> OpenAiTranslator(app, settings, pair, InputMode.AUTO)
      else -> OfflineTranslator(app, settings, pair, InputMode.AUTO, asrMode, mtModel)
    }
    translator = engine
    recording = true
    // Started while the screen is visible, which is what API 34+ requires of a
    // microphone-typed foreground service.
    RecordingService.onStopRequested = ::stop
    RecordingService.start(app)
    status = when {
      useOnline -> "Connecting…"
      asrMode == AsrMode.STREAMING -> "Listening (streaming)…"
      else -> "Listening… speak, then tap Stop."
    }
    engine.start(viewModelScope, callbacks)
  }

  /**
   * Conversation counterpart of the Listen branch of [start]. Kept separate so the
   * Listen path stays exactly as it was.
   */
  @RequiresApi(Build.VERSION_CODES.S)
  private fun startConversation(canGoOnline: Boolean) {
    val app = getApplication<Application>()
    val partner = conversationPartner
    val useOnline = online && canGoOnline
    val asrMode = settings.asrMode
    val mtModel = settings.mtModel
    if (!useOnline) {
      // The on-device pipeline only knows EN and JA; other languages must never reach it.
      if (!conversationOfflineCapable(mine, partner)) {
        status = "${partner.label} needs Online mode."
        return
      }
      if (!mtSupported()) {
        status = "Offline translation isn't supported on this device's CPU. Use Online mode."
        return
      }
      if (settings.requiredModelIds().any { !Models.isPresent(app, it) }) {
        status = "Models missing — download them in Settings → Models."
        return
      }
    }
    // Create the router before the service starts: the service records the router
    // generation in onStartCommand to scope its onDestroy safety net to this session.
    val router = AudioRouters.create(app)
    val engine: ConversationTranslator = if (useOnline) {
      OnlineConversationTranslator(app, settings, settings.onlineProvider, mine, partner, router)
    } else {
      OfflineConversationTranslator(app, settings, mine, partner, asrMode, mtModel, router)
    }
    translator = engine
    speaking = false // every session starts on the partner's side; Speaking is pressed to talk
    recording = true
    RecordingService.onStopRequested = ::stop
    RecordingService.start(app)
    status = when {
      useOnline -> "Connecting…"
      asrMode == AsrMode.STREAMING -> "Listening (streaming)…"
      else -> "Listening… use Speaking to talk."
    }
    engine.start(viewModelScope, conversationCallbacks(engine))
  }

  /**
   * Conversation engines report status and completion from Dispatchers.IO. Those two
   * touch plain (non-snapshot) fields and the service, so they hop to the main thread
   * and are dropped if the engine is no longer current. Turn and partial updates only
   * write snapshot state and pass straight through.
   */
  private fun conversationCallbacks(engine: ConversationTranslator) =
    object : TranslatorCallbacks by callbacks {
      override fun onStatus(text: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
          if (translator === engine) status = text
        }
      }

      override fun onFinished(error: String?) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
          if (translator !== engine) return@launch
          speaking = false
          callbacks.onFinished(error)
        }
      }
    }

  fun stop() {
    if (!recording) return
    translator?.stop()
    status = "Finishing…"
  }

  override fun onCleared() {
    translator?.stop()
    translator = null
    releaseService()
    tts?.let { it.stop(); it.shutdown() }
    tts = null
  }

  private fun releaseService() {
    RecordingService.onStopRequested = null
    RecordingService.stop(getApplication())
  }

  private fun persist() {
    val done = turns.filter { it.translation != null } // already chronological
    if (done.isEmpty()) return
    SessionStore.save(
      getApplication(),
      SessionLog(
        id = sessionId,
        startedAt = startedAt,
        utterances = done.map {
          LoggedUtterance(
            it.transcript, it.srcLang, it.tgtLang, it.detected, it.translation!!,
            speaker = it.speaker?.name,
          )
        },
        mode = mode.name,
      ),
    )
  }

  private fun speak(text: String, tgt: String) {
    if (!settings.autoSpeak) return
    val engine = tts ?: return
    val avail = engine.setLanguage(localeFromFlores(tgt))
    if (avail != TextToSpeech.LANG_MISSING_DATA && avail != TextToSpeech.LANG_NOT_SUPPORTED) {
      engine.setSpeechRate(settings.speechRate)
      engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "turn")
    }
  }

  // Compose snapshot state is thread-safe to mutate, so engines may invoke these
  // from their own background threads.
  private val callbacks = object : TranslatorCallbacks {
    override fun onTurnStart(turn: LiveTurn) { turns.add(turn) }

    override fun onTurnUpdate(id: Long, transcript: String?, translation: String?) {
      val idx = turns.indexOfFirst { it.id == id }
      if (idx < 0) return
      turns[idx] = turns[idx].copy(
        transcript = transcript ?: turns[idx].transcript,
        translation = translation ?: turns[idx].translation,
      )
    }

    override fun onPartial(text: String) { partial = text }
    override fun onStatus(text: String) { status = text }
    override fun onSpeak(text: String, tgtFlores: String) { speak(text, tgtFlores) }
    override fun onPersist() { persist() }

    override fun onFinished(error: String?) {
      recording = false
      partial = ""
      translator = null
      releaseService()
      status = error?.let { "Error: $it" }
        ?: "Stopped. (${turns.size} turn${if (turns.size == 1) "" else "s"})"
    }
  }
}
