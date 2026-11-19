package com.rj.app.show

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.rj.app.R
import com.rj.app.RjApp
import com.rj.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Keeps the show alive (and GPS + mic usable) while the screen is off or another app is in front. */
class RjService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null
    private val engine get() = (application as RjApp).engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground("Warming up…")
                engine.start(intent.getStringExtra(EXTRA_DESTINATION))
                watchEngine()
            }
            ACTION_TALK -> engine.talk()
            ACTION_SKIP -> engine.skip()
            ACTION_STOP -> engine.stop()
        }
        return START_NOT_STICKY
    }

    private fun watchEngine() {
        if (watch?.isActive == true) return
        watch = scope.launch {
            // drop(1): ignore the state that was current before the show started.
            engine.state.drop(1).collect { s ->
                if (!s.live) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val text = s.nowPlaying?.takeIf { s.status == OnAir.MUSIC }?.let { "♪ $it" } ?: s.status.label
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s.carName, text))
                }
            }
        }
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "On air", NotificationManager.IMPORTANCE_LOW))
        val n = notification(engine.state.value.carName, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && granted(Manifest.permission.RECORD_AUDIO)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, n, types)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun notification(title: String, text: String): Notification {
        fun action(label: String, act: String, code: Int) = Notification.Action.Builder(
            null as android.graphics.drawable.Icon?, label, PendingIntent.getService(this, code, Intent(this, RjService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ).build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title.ifBlank { "RJ" })
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action("Talk", ACTION_TALK, 1))
            .addAction(action("Next", ACTION_SKIP, 2))
            .addAction(action("End drive", ACTION_STOP, 3))
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.rj.app.START"
        const val ACTION_TALK = "com.rj.app.TALK"
        const val ACTION_SKIP = "com.rj.app.SKIP"
        const val ACTION_STOP = "com.rj.app.STOP"
        const val EXTRA_DESTINATION = "destination"
        private const val CHANNEL = "on_air"
        private const val NOTIFICATION_ID = 7

        fun send(context: Context, action: String, destination: String? = null) {
            val i = Intent(context, RjService::class.java).setAction(action)
            destination?.let { i.putExtra(EXTRA_DESTINATION, it) }
            if (action == ACTION_START) ContextCompat.startForegroundService(context, i) else context.startService(i)
        }
    }
}
