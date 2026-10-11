package app.rly3h.yakumo.ui.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttLatchTest {
  @Test
  fun shortTapLatchesOn() {
    val latch = PttLatch()
    latch.press(0)
    latch.release(100)
    assertTrue(latch.on)
  }

  @Test
  fun secondTapTurnsOff() {
    val latch = PttLatch()
    latch.press(0)
    latch.release(100)
    latch.press(200)
    latch.release(250)
    assertFalse(latch.on)
  }

  @Test
  fun longHoldIsMomentary() {
    val latch = PttLatch()
    latch.press(0)
    latch.release(500)
    assertFalse(latch.on)
  }

  @Test
  fun onWhileHeld() {
    val latch = PttLatch()
    latch.press(0)
    assertTrue(latch.on)
  }

  @Test
  fun resetTurnsOff() {
    val latch = PttLatch()
    latch.press(0)
    latch.release(100)
    latch.reset()
    assertFalse(latch.on)
  }
}
