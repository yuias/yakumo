package app.rly3h.yakumo.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import androidx.annotation.RequiresApi

/** Who a translated utterance is for. ME -> earphones, PARTNER -> built-in speaker. */
enum class Audience { ME, PARTNER }

/** Where audio actually went; SPEAKER means the mic will hear it (echo gate input). */
enum class ResolvedOutput { EARPHONE, SPEAKER }

/** [split] is true when the two audiences get different outputs; [note] is a status line (e.g. no earphones). */
data class RoutingInfo(val split: Boolean, val note: String?)

/**
 * Output routing for Conversation mode. Kept behind an interface because the
 * routing assumptions are unverified on real hardware and the implementation
 * may need to be swapped without touching the engines.
 */
interface AudioRouter {
  /** Enter the routing mode for a Conversation session. Idempotent. */
  fun begin(): RoutingInfo

  /** Route the next utterance; blocks for the device-switch settle time. Re-reads devices each call. */
  fun routeTo(audience: Audience): ResolvedOutput

  /** Attributes every Conversation playback (AudioTrack and TTS) must use so it follows routing. */
  val playbackAttributes: AudioAttributes

  /** Built-in mic to pin capture to, or null. */
  fun builtinMic(): AudioDeviceInfo?

  /** Restore MODE_NORMAL and clear the communication device. Idempotent; safe from any thread. */
  fun end()
}

/**
 * Tracks the one live router. Each registration bumps a generation so a late
 * safety-net release can tell whether the router is still the one it was
 * started for.
 */
internal class RouterRegistry<T : Any> {
  private var active: T? = null
  private var generation = 0L

  /** Registers [router] as active and returns the router it replaced, if any. */
  @Synchronized
  fun register(router: T): T? = active.also {
    active = router
    generation++
  }

  @Synchronized
  fun generation(): Long = generation

  /** Compare-and-clear: a router that was already replaced must not unregister its successor. */
  @Synchronized
  fun clear(router: T) {
    if (active === router) active = null
  }

  /** Takes the active router only if it was registered at [owner]'s generation. */
  @Synchronized
  fun takeIfGeneration(owner: Long): T? =
    if (generation == owner) active.also { active = null } else null
}

object AudioRouters {
  private val registry = RouterRegistry<AudioRouter>()

  /**
   * Conversation mode is API 31+ only. Ends any still-registered router first
   * (a previous session whose cleanup failed), then registers the new one.
   * A router deregisters itself in end().
   */
  @RequiresApi(31)
  fun create(context: Context): AudioRouter {
    val router = CommunicationDeviceRouter(context.applicationContext, onEnded = registry::clear)
    registry.register(router)?.end()
    return router
  }

  /** Generation of the latest router; a service captures it to scope its own safety net. */
  fun generation(): Long = registry.generation()

  /**
   * Safety net for when only the service is stopped: ends the active router
   * only if it is still the one registered at [owner], never a newer session's.
   */
  fun releaseActive(owner: Long) {
    registry.takeIfGeneration(owner)?.end()
  }
}

// Order matters: a wired or USB headset is the least lossy path, Bluetooth SCO the narrowest.
private val EARPHONE_PRIORITY = listOf(
  AudioDeviceInfo.TYPE_WIRED_HEADSET,
  AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
  AudioDeviceInfo.TYPE_USB_HEADSET,
  AudioDeviceInfo.TYPE_BLE_HEADSET,
  AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
  AudioDeviceInfo.TYPE_HEARING_AID,
)

/** Preferred earphone among communication-device types, by priority; null if none. */
internal fun pickEarphoneType(types: List<Int>): Int? =
  EARPHONE_PRIORITY.firstOrNull { it in types }
