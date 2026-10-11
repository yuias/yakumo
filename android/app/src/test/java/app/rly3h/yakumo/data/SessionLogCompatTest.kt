package app.rly3h.yakumo.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionLogCompatTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun legacyJsonWithoutModeOrSpeakerDecodes() {
    val legacy = """
      {"id":"1700000000000","startedAt":1700000000000,"utterances":[
        {"transcript":"hello","srcLang":"eng_Latn","tgtLang":"jpn_Jpan","detected":"en","translation":"konnichiwa"},
        {"transcript":"thanks","srcLang":"eng_Latn","tgtLang":"jpn_Jpan"}
      ]}
    """.trimIndent()
    val log = json.decodeFromString<SessionLog>(legacy)
    assertEquals("LISTEN", log.mode)
    assertEquals(2, log.utterances.size)
    log.utterances.forEach { assertNull(it.speaker) }
  }

  @Test
  fun conversationFieldsRoundTrip() {
    val log = SessionLog(
      id = "1",
      startedAt = 1,
      utterances = listOf(LoggedUtterance("a", "jpn_Jpan", "fra_Latn", speaker = "ME")),
      mode = "CONVERSATION",
    )
    val decoded = json.decodeFromString<SessionLog>(json.encodeToString(log))
    assertEquals("CONVERSATION", decoded.mode)
    assertEquals("ME", decoded.utterances.single().speaker)
  }
}
