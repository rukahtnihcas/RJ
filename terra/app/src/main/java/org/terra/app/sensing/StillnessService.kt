package org.terra.app.sensing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.terra.app.MainActivity
import org.terra.app.R
import org.terra.app.TerraApp
import org.terra.app.domain.AcousticMatcher
import org.terra.app.model.Detection
import org.terra.app.model.Region
import org.terra.app.model.StillnessSession

data class StillnessStatus(
    val running: Boolean = false,
    val elapsedSec: Int = 0,
    val totalSec: Int = StillnessService.DEFAULT_SECONDS,
    val heard: Int = 0,
    val quiet: Boolean = true,
    val finishedSessionId: Long? = null
)

/**
 * Stillness Mode. Runs with the screen locked: listens in short windows, compares what it hears
 * with the calls of local species, and stores only the resulting candidate names — never audio.
 */
class StillnessService : LifecycleService() {
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { job?.cancel(); return START_NOT_STICKY }
        if (job?.isActive == true) return START_NOT_STICKY
        val seconds = intent?.getIntExtra(EXTRA_SECONDS, DEFAULT_SECONDS) ?: DEFAULT_SECONDS
        val note = notification("Listening quietly. Lock your screen and be still.")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTE_ID, note)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "terra:stillness").apply { acquire((seconds + 30) * 1000L) }
        job = lifecycleScope.launch { sit(seconds) }
        return START_NOT_STICKY
    }

    private suspend fun sit(seconds: Int) {
        val app = application as TerraApp
        val here = app.location.home
        val region = Region.of(here.lat, here.lon)
        val candidates = app.catalog.inRegion(region).filter { it.acoustic != null }
        val started = System.currentTimeMillis()
        var session = StillnessSession(startedAt = started, durationSec = 0, lat = here.lat, lon = here.lon)
        session = session.copy(id = app.repo.startSession(session))
        _status.value = StillnessStatus(running = true, totalSec = seconds)
        var windows = 0
        var quietWindows = 0
        var heard = 0
        val lastHeard = HashMap<String, Long>()
        try {
            while (lifecycleScope.isActive) {
                val elapsed = ((System.currentTimeMillis() - started) / 1000).toInt()
                if (elapsed >= seconds) break
                val pcm = Listener.record(WINDOW_SEC) ?: break
                val f = AcousticMatcher.analyze(AcousticMatcher.toFloat(pcm), Listener.SAMPLE_RATE)
                windows++
                val quiet = f == null || f.levelDb < QUIET_DB
                if (quiet) quietWindows++
                // Only a sound that clearly stands out of a calm background is worth a candidate.
                if (f != null && f.contrastDb >= 9f && f.levelDb < LOUD_DB) {
                    val best = AcousticMatcher.rank(f, candidates, min = 0.62f, limit = 1).firstOrNull()
                    val now = System.currentTimeMillis()
                    if (best != null && now - (lastHeard[best.species.id] ?: 0) > 90_000) {
                        lastHeard[best.species.id] = now
                        app.repo.addDetection(Detection(sessionId = session.id, timestamp = now, speciesId = best.species.id, score = best.score))
                        heard++
                    }
                }
                _status.value = StillnessStatus(true, ((System.currentTimeMillis() - started) / 1000).toInt().coerceAtMost(seconds), seconds, heard, quiet)
            }
        } finally {
            val elapsed = ((System.currentTimeMillis() - started) / 1000).toInt().coerceAtMost(seconds)
            val done = elapsed >= seconds
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                app.repo.updateSession(session.copy(durationSec = elapsed, completed = done,
                    quietFraction = if (windows == 0) 0f else quietWindows.toFloat() / windows))
            }
            if (done) app.cues.pulse(3)
            _status.value = StillnessStatus(running = false, elapsedSec = elapsed, totalSec = seconds, heard = heard, finishedSessionId = session.id)
            wakeLock?.takeIf { it.isHeld }?.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Stillness sits", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, StillnessService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_leaf).setContentTitle("Stillness").setContentText(text)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "End sit", stop).build())
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_SECONDS = 600
        const val WINDOW_SEC = 3
        const val QUIET_DB = -38f
        const val LOUD_DB = -12f
        private const val NOTE_ID = 7
        private const val CHANNEL = "stillness"
        private const val ACTION_STOP = "org.terra.app.STOP_STILLNESS"
        private const val EXTRA_SECONDS = "seconds"
        private val _status = MutableStateFlow(StillnessStatus())
        val status: StateFlow<StillnessStatus> = _status

        fun start(context: Context, seconds: Int = DEFAULT_SECONDS) {
            _status.value = StillnessStatus(running = true, totalSec = seconds)
            context.startForegroundService(Intent(context, StillnessService::class.java).putExtra(EXTRA_SECONDS, seconds))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StillnessService::class.java).setAction(ACTION_STOP))
        }

        fun acknowledge() { _status.value = StillnessStatus() }
    }
}
