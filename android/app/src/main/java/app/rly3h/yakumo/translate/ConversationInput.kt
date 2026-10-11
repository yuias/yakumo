package app.rly3h.yakumo.translate

import app.rly3h.yakumo.audio.Audience
import app.rly3h.yakumo.ui.session.LanguageOption

/**
 * What to do with one captured chunk in Conversation mode.
 *
 * @param active direction this chunk belongs to
 * @param muted replace the chunk with silence (echo guard)
 * @param switchedFrom non-null only on the first chunk after a Speaking toggle
 */
internal data class InputDecision(
  val active: Speaker,
  val muted: Boolean,
  val switchedFrom: Speaker?,
)

/** Not thread-safe; owned by the single capture thread. */
internal class ConversationInputGate {
  private var last: Speaker? = null

  /**
   * Speaking ON means the user talks (ME); otherwise the mic is treated as the
   * partner. Only the partner direction is muted while the speaker is busy: with
   * no earphones even the partner's translation plays on the speaker, so any
   * speaker playback must block that direction, while an explicit Speaking press
   * is never overridden.
   */
  fun decide(speaking: Boolean, speakerBusy: Boolean): InputDecision {
    val active = if (speaking) Speaker.ME else Speaker.PARTNER
    val prev = last
    last = active
    return InputDecision(
      active = active,
      muted = active == Speaker.PARTNER && speakerBusy,
      switchedFrom = if (prev != null && prev != active) prev else null,
    )
  }
}

/** (source, target) for a conversation turn: ME = mine -> partner, PARTNER = partner -> mine. */
internal fun conversationDirection(
  speaker: Speaker,
  mine: LanguageOption,
  partner: LanguageOption,
): Pair<LanguageOption, LanguageOption> =
  if (speaker == Speaker.ME) mine to partner else partner to mine

/** A translation is heard by the other party. */
internal fun audienceFor(speaker: Speaker): Audience =
  if (speaker == Speaker.ME) Audience.PARTNER else Audience.ME

/**
 * Turn id for the UI list. The two online sessions each number turns from 0, so
 * the id is interleaved (ME odd, PARTNER even) to keep the list keys unique.
 */
internal fun directionalTurnId(speaker: Speaker, id: Long): Long =
  id * 2 + if (speaker == Speaker.ME) 1 else 0

/**
 * Wraps the callbacks of one inner (single-direction) engine for a Conversation
 * session. It tags turns with [speaker] and remaps ids; status and finish are
 * intercepted because the outer session merges the two directions itself.
 *
 * @param status receives this direction's status line instead of the outer onStatus
 * @param onInnerFinished called instead of the outer onFinished
 */
internal class DirectionalCallbacks(
  private val speaker: Speaker,
  private val outer: TranslatorCallbacks,
  private val status: (Speaker, String) -> Unit,
  private val onInnerFinished: (Speaker, String?) -> Unit,
) : TranslatorCallbacks {
  override fun onTurnStart(turn: LiveTurn) =
    outer.onTurnStart(turn.copy(id = directionalTurnId(speaker, turn.id), speaker = speaker))

  override fun onTurnUpdate(id: Long, transcript: String?, translation: String?) =
    outer.onTurnUpdate(directionalTurnId(speaker, id), transcript, translation)

  override fun onPartial(text: String) = outer.onPartial(text)

  override fun onStatus(text: String) = status(speaker, text)

  override fun onSpeak(text: String, tgtFlores: String) = outer.onSpeak(text, tgtFlores)

  override fun onPersist() = outer.onPersist()

  override fun onFinished(error: String?) = onInnerFinished(speaker, error)
}
