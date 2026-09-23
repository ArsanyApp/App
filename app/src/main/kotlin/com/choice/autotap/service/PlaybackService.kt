package com.choice.autotap.service

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.choice.autotap.R
import com.choice.autotap.player.PlaybackState
import com.choice.autotap.player.PlaybackStatus
import com.choice.autotap.ui.MainActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service kept alive while a macro runs. Shows progress plus Pause and Stop
 * (emergency stop) actions, and holds a partial wake lock.
 */
class PlaybackService : Service() {

    private val scope = MainScope()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(AutoTapBridge.playback.value), type)
        } catch (e: Exception) {
            // Background FGS start refused (Android 12+). Playback continues without the notification.
            stopSelf()
            return
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChoiceAutoTap:playback").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_MS)
        }

        scope.launch {
            AutoTapBridge.playback.collect { state ->
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                runCatching { nm.notify(NOTIFICATION_ID, build(state)) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AutoTapBridge.service.value?.stopPlayback()
                stopSelf()
            }
            ACTION_PAUSE -> AutoTapBridge.service.value?.togglePause()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun build(state: PlaybackState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            this, 2, Intent(this, PlaybackService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE,
        )
        val paused = state.status == PlaybackStatus.PAUSED
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (state.macroName.isNotEmpty()) "Running: ${state.macroName}" else "Choice Auto Tap")
            .setContentText(state.shortLabel() + "  ·  Volume-down ×3 = stop")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (paused) "Resume" else "Pause", pause)
            .addAction(0, "STOP", stop)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.choice.autotap.STOP"
        private const val ACTION_PAUSE = "com.choice.autotap.PAUSE"
        private const val MAX_WAKE_MS = 24L * 60 * 60 * 1000

        fun start(context: Context) {
            // May be refused on some Android versions when started from the background;
            // playback still works because the accessibility service keeps the process alive.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
