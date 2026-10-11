package app.rly3h.yakumo.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmPlaybackLogicTest {
  @Test
  fun bothQueuesEmptyPicksNothing() {
    assertNull(pickNextAudience(null, null))
  }

  @Test
  fun onlyOneQueueNonEmptyPicksIt() {
    assertEquals(Audience.ME, pickNextAudience(100, null))
    assertEquals(Audience.PARTNER, pickNextAudience(null, 5))
  }

  @Test
  fun olderHeadChunkWins() {
    assertEquals(Audience.PARTNER, pickNextAudience(100, 50))
    assertEquals(Audience.ME, pickNextAudience(50, 100))
  }

  @Test
  fun segmentEndsAtIdleThreshold() {
    assertFalse(shouldEndSegment(249))
    assertTrue(shouldEndSegment(250))
  }

  @Test
  fun drainIsDoneOnceHeadReachesWrittenFrames() {
    assertFalse(isDrained(playedFrames = 0, writtenFrames = 100))
    assertFalse(isDrained(playedFrames = 99, writtenFrames = 100))
    assertTrue(isDrained(playedFrames = 100, writtenFrames = 100))
    assertTrue(isDrained(playedFrames = 101, writtenFrames = 100))
  }
}
