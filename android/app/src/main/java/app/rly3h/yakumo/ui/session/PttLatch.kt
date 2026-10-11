package app.rly3h.yakumo.ui.session

/**
 * Push-to-talk state for the Speaking button, supporting both gestures: a short
 * tap latches it on (the next tap turns it off), while a press held for at least
 * [holdThresholdMs] is momentary and turns off on release.
 *
 * Not thread-safe; driven from the UI thread.
 */
internal class PttLatch(private val holdThresholdMs: Long = 400) {
  var on: Boolean = false
    private set

  private var pressedAt = 0L

  // Whether the current press started from off (it may be a momentary hold) rather
  // than on (a tap on a latched button, which turns it off on release).
  private var pressStartedOff = false

  fun press(nowMs: Long) {
    if (!on) {
      on = true
      pressedAt = nowMs
      pressStartedOff = true
    } else {
      pressStartedOff = false
    }
  }

  fun release(nowMs: Long) {
    if (!on) return
    if (pressStartedOff) {
      if (nowMs - pressedAt >= holdThresholdMs) on = false
    } else {
      on = false
    }
    pressStartedOff = false
  }

  fun reset() {
    on = false
    pressStartedOff = false
  }
}
