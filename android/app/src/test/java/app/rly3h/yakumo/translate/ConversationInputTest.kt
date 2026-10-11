package app.rly3h.yakumo.translate

import app.rly3h.yakumo.audio.Audience
import app.rly3h.yakumo.ui.session.languageByFlores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationInputTest {
  private val ja = languageByFlores("jpn_Jpan")
  private val fr = languageByFlores("fra_Latn")

  @Test
  fun gateSequenceFollowsSpeakingAndEchoRules() {
    val gate = ConversationInputGate()
    assertEquals(InputDecision(Speaker.PARTNER, muted = false, switchedFrom = null), gate.decide(false, false))
    assertEquals(InputDecision(Speaker.ME, muted = false, switchedFrom = Speaker.PARTNER), gate.decide(true, false))
    // Speaking is never muted, even while the speaker plays.
    assertEquals(InputDecision(Speaker.ME, muted = false, switchedFrom = null), gate.decide(true, true))
    assertEquals(InputDecision(Speaker.PARTNER, muted = true, switchedFrom = Speaker.ME), gate.decide(false, true))
  }

  @Test
  fun directionFollowsSpeaker() {
    assertEquals(ja to fr, conversationDirection(Speaker.ME, ja, fr))
    assertEquals(fr to ja, conversationDirection(Speaker.PARTNER, ja, fr))
  }

  @Test
  fun translationIsHeardByTheOtherParty() {
    assertEquals(Audience.PARTNER, audienceFor(Speaker.ME))
    assertEquals(Audience.ME, audienceFor(Speaker.PARTNER))
  }

  private class Recorder : TranslatorCallbacks {
    val starts = mutableListOf<LiveTurn>()
    val updates = mutableListOf<Triple<Long, String?, String?>>()
    val partials = mutableListOf<String>()
    val statuses = mutableListOf<String>()
    val spoken = mutableListOf<Pair<String, String>>()
    var persists = 0
    val finished = mutableListOf<String?>()

    override fun onTurnStart(turn: LiveTurn) { starts += turn }
    override fun onTurnUpdate(id: Long, transcript: String?, translation: String?) {
      updates += Triple(id, transcript, translation)
    }
    override fun onPartial(text: String) { partials += text }
    override fun onStatus(text: String) { statuses += text }
    override fun onSpeak(text: String, tgtFlores: String) { spoken += text to tgtFlores }
    override fun onPersist() { persists++ }
    override fun onFinished(error: String?) { finished += error }
  }

  private fun turn(id: Long) = LiveTurn(id, "t", "jpn_Jpan", "fra_Latn", "")

  @Test
  fun turnIdsAreRemappedAndTaggedPerSpeaker() {
    val outer = Recorder()
    val me = DirectionalCallbacks(Speaker.ME, outer, { _, _ -> }, { _, _ -> })
    val partner = DirectionalCallbacks(Speaker.PARTNER, outer, { _, _ -> }, { _, _ -> })

    me.onTurnStart(turn(0))
    partner.onTurnStart(turn(0))
    me.onTurnUpdate(0, "a", "b")
    partner.onTurnUpdate(3, translation = "c")

    assertEquals(listOf(1L to Speaker.ME, 0L to Speaker.PARTNER), outer.starts.map { it.id to it.speaker })
    assertEquals(listOf(Triple(1L, "a", "b"), Triple(6L, null, "c")), outer.updates)
  }

  @Test
  fun statusAndFinishAreInterceptedNotForwarded() {
    val outer = Recorder()
    val statuses = mutableListOf<Pair<Speaker, String>>()
    val finishes = mutableListOf<Pair<Speaker, String?>>()
    val me = DirectionalCallbacks(Speaker.ME, outer, { s, t -> statuses += s to t }, { s, e -> finishes += s to e })

    me.onStatus("x")
    me.onFinished("boom")

    assertEquals(listOf(Speaker.ME to "x"), statuses)
    assertEquals(listOf(Speaker.ME to "boom"), finishes)
    assertEquals(emptyList<String>(), outer.statuses)
    assertEquals(emptyList<String?>(), outer.finished)
  }

  @Test
  fun otherCallbacksPassThrough() {
    val outer = Recorder()
    val cb = DirectionalCallbacks(Speaker.PARTNER, outer, { _, _ -> }, { _, _ -> })

    cb.onPartial("p")
    cb.onSpeak("hello", "eng_Latn")
    cb.onPersist()

    assertEquals(listOf("p"), outer.partials)
    assertEquals(listOf("hello" to "eng_Latn"), outer.spoken)
    assertEquals(1, outer.persists)
    assertNull(outer.starts.firstOrNull())
  }
}
