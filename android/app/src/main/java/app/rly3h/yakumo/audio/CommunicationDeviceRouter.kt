package app.rly3h.yakumo.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Switches output per utterance with MODE_IN_COMMUNICATION plus
 * setCommunicationDevice. Unverified on hardware: see the routing risks in the
 * design notes before relying on the settle time or on mode restoration.
 */
@RequiresApi(31)
internal class CommunicationDeviceRouter(
  context: Context,
  private val onEnded: (AudioRouter) -> Unit = {},
) : AudioRouter {
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private val ended = AtomicBoolean(false)
  private val routeLock = Any()

  override val playbackAttributes: AudioAttributes =
    AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
      .build()

  // The ended check and the mode change share routeLock with end(), so a concurrent end()
  // cannot be followed by a late MODE_IN_COMMUNICATION that nothing would restore.
  override fun begin(): RoutingInfo = synchronized(routeLock) {
    if (ended.get()) return RoutingInfo(split = false, note = null)
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    if (findEarphone() != null) {
      RoutingInfo(split = true, note = null)
    } else {
      RoutingInfo(split = false, note = "No earphones: both directions play on the speaker.")
    }
  }

  override fun routeTo(audience: Audience): ResolvedOutput = synchronized(routeLock) {
    if (ended.get()) return ResolvedOutput.SPEAKER
    // An explicit device is always set. Clearing and trusting the default could land on the
    // earpiece when no earphones are present, and the echo gate would not know where it played.
    val earphone = if (audience == Audience.ME) findEarphone() else null
    val target = earphone ?: findBuiltinSpeaker()
    val applied = target != null && switchTo(target)
    // A failed switch leaves the output unknown, so report SPEAKER: the echo gate errs on muting.
    if (applied && earphone != null) ResolvedOutput.EARPHONE else ResolvedOutput.SPEAKER
  }

  override fun builtinMic(): AudioDeviceInfo? =
    audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
      .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

  override fun end() {
    if (!ended.compareAndSet(false, true)) return
    // Deregister before taking routeLock so no lock is held while calling into the registry.
    onEnded(this)
    synchronized(routeLock) {
      audioManager.clearCommunicationDevice()
      audioManager.mode = AudioManager.MODE_NORMAL
    }
  }

  private fun switchTo(device: AudioDeviceInfo): Boolean {
    // Ask the OS rather than caching: it or another app may have changed the device since.
    if (audioManager.communicationDevice?.id == device.id) return true
    // setCommunicationDevice throws for a device that vanished or is not a communication device.
    if (!runCatching { audioManager.setCommunicationDevice(device) }.getOrDefault(false)) return false
    // Without a pause the head of the next utterance can play on the old device.
    Thread.sleep(ROUTE_SETTLE_MS)
    return true
  }

  private fun findEarphone(): AudioDeviceInfo? {
    val devices = audioManager.availableCommunicationDevices
    val type = pickEarphoneType(devices.map { it.type }) ?: return null
    return devices.firstOrNull { it.type == type }
  }

  private fun findBuiltinSpeaker(): AudioDeviceInfo? =
    audioManager.availableCommunicationDevices
      .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

  private companion object {
    // To be tuned on a device.
    const val ROUTE_SETTLE_MS = 200L
  }
}
