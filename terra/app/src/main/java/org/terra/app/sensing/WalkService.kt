package org.terra.app.sensing

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.terra.app.MainActivity
import org.terra.app.R
import org.terra.app.TerraApp

/**
 * Walk mode. Keeps location alive with the screen off so the phone can stay in a pocket and
 * simply pulse when the walker crosses into a habitat. Started and stopped only by the player.
 */
class WalkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Walk mode", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, WalkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_leaf).setContentTitle("Walk mode")
            .setContentText("Eyes up. TERRA will pulse when you enter a habitat.")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "End walk", stop).build()).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTE_ID, note)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        (application as TerraApp).location.start()
        _running.value = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        super.onDestroy()
    }

    companion object {
        private const val NOTE_ID = 8
        private const val CHANNEL = "walk"
        private const val ACTION_STOP = "org.terra.app.STOP_WALK"
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) = context.startForegroundService(Intent(context, WalkService::class.java))
        fun stop(context: Context) { context.startService(Intent(context, WalkService::class.java).setAction(ACTION_STOP)) }
    }
}
