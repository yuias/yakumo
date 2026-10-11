package app.rly3h.yakumo.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsPlaybackLogicTest {
  @Test
  fun emptyTextGetsTheBaseTimeout() {
    assertEquals(2000L, utteranceTimeoutMs(""))
  }

  @Test
  fun timeoutGrowsWithTextLength() {
    assertEquals(3500L, utteranceTimeoutMs("a".repeat(10)))
  }

  @Test
  fun timeoutIsCapped() {
    assertEquals(60000L, utteranceTimeoutMs("a".repeat(1000)))
  }
}
