package app.rly3h.yakumo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.rly3h.yakumo.audio.AudioRouters

/**
 * Keeps the process in the foreground while a session records.
 *
 * The capture and translation coroutines live in the session ViewModel, not
 * here — this service exists only so the OS lets the microphone keep feeding
 * them once the app loses visibility (screen off, another app in front). It
 * carries no session state and is safe to stop at any time.
 */
class RecordingService : Service() {

  // Router generation of the session this instance serves. onDestroy arrives asynchronously after
  // stopService, so a newer session's router may exist by then and must not be ended. Contract for callers:
  // call AudioRouters.create before or in the same main-thread turn as RecordingService.start.
  private var routerOwner = NO_ROUTER_OWNER

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      // The notification's Stop button: hand the request to the session, which
      // shuts the engine down and stops this service through its normal path.
      onStopRequested?.invoke()
      return START_NOT_STICKY
    }
    routerOwner = AudioRouters.generation()
    createChannel()
    val type =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
      else 0
    ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    // Not sticky: a session that the OS killed should not silently resume with
    // a live microphone and no UI behind it.
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    // Safety net: if only the service is stopped, the audio mode must not stay in communication.
    AudioRouters.releaseActive(routerOwner)
    super.onDestroy()
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    onStopRequested?.invoke()
    stopSelf()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Shown while a translation session is recording."
        setShowBadge(false)
      }
    )
  }

  private fun buildNotification(): Notification {
    val open = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val stop = PendingIntent.getService(
      this,
      1,
      Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_mic)
      .setContentTitle(getString(R.string.app_name))
      .setContentText("Recording — tap to return")
      .setContentIntent(open)
      .addAction(0, "Stop", stop)
      .setOngoing(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "recording"
    private const val NOTIFICATION_ID = 1
    private const val NO_ROUTER_OWNER = -1L
    private const val ACTION_STOP = "app.rly3h.yakumo.STOP_RECORDING"

    /**
     * Invoked when the notification's Stop action fires. The session ViewModel
     * owns this for as long as it is recording; it is process-wide because a
     * Service cannot reach a ViewModel any other way.
     */
    @Volatile
    var onStopRequested: (() -> Unit)? = null

    /** Must be called while the app is visible — API 34+ rejects it otherwise. */
    fun start(context: Context) {
      ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, RecordingService::class.java))
    }
  }
}
