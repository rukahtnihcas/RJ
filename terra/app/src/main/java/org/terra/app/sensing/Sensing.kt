package org.terra.app.sensing

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.terra.app.domain.AcousticMatcher
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.sin

data class Fix(val lat: Double, val lon: Double, val accuracyM: Float?, val time: Long, val bearing: Float? = null)

fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Location from the platform itself — no Play Services needed, so it works on any Android device. */
class LocationTracker(private val context: Context) {
    private val prefs = context.getSharedPreferences("terra", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix
    private var listening = false

    /** Last place we knew, or a rough guess from the time zone, so region and season always resolve. */
    val home: Fix
        get() = _fix.value ?: if (prefs.contains("lat")) Fix(prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble(), null, 0)
        else Fix(40.0, TimeZone.getDefault().rawOffset / 3_600_000.0 * 15, null, 0)

    private val listener = LocationListener { l -> accept(l) }

    private fun accept(l: Location) {
        val current = _fix.value
        // Prefer fresh fixes; ignore a much vaguer one that arrives right after a sharp one.
        if (current != null && l.time - current.time < 10_000 && l.hasAccuracy() && (current.accuracyM ?: 9999f) * 3 < l.accuracy) return
        _fix.value = Fix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, l.time, if (l.hasBearing()) l.bearing else null)
        prefs.edit().putFloat("lat", l.latitude.toFloat()).putFloat("lon", l.longitude.toFloat()).apply()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (listening || !(context.granted(Manifest.permission.ACCESS_FINE_LOCATION) || context.granted(Manifest.permission.ACCESS_COARSE_LOCATION))) return
        listening = true
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) runCatching {
            if (manager.allProviders.contains(p)) {
                manager.getLastKnownLocation(p)?.let { accept(it) }
                manager.requestLocationUpdates(p, 4000L, 5f, listener, context.mainLooper)
            }
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        runCatching { manager.removeUpdates(listener) }
    }
}

object Listener {
    const val SAMPLE_RATE = 22050

    /**
     * Records [seconds] of mono audio into memory. [onSpectrum] receives a live power spectrum
     * for display. Audio is analysed and then discarded — never written to disk.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(seconds: Int, onSpectrum: (FloatArray) -> Unit = {}): ShortArray? = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return@withContext null
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.UNPROCESSED.takeIf { Build.VERSION.SDK_INT >= 24 } ?: MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SAMPLE_RATE))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?: runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SAMPLE_RATE))
            }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return@withContext null
        val out = ShortArray(SAMPLE_RATE * seconds)
        var filled = 0
        try {
            rec.startRecording()
            var lastShown = 0
            while (isActive && filled < out.size) {
                val n = rec.read(out, filled, minOf(2048, out.size - filled))
                if (n <= 0) break
                filled += n
                if (filled - lastShown >= 2048 && filled >= AcousticMatcher.FRAME) {
                    lastShown = filled
                    val frame = FloatArray(AcousticMatcher.FRAME) { out[filled - AcousticMatcher.FRAME + it] / 32768f }
                    onSpectrum(AcousticMatcher.spectrum(frame, 0))
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (filled < out.size) out.copyOf(filled) else out
    }
}

object Vision {
    /** Generic on-device image labels (e.g. "Bird", "Flower", "Insect") with confidences. */
    suspend fun labels(context: Context, uri: Uri): List<Pair<String, Float>> = suspendCancellableCoroutine { cont ->
        runCatching {
            val image = InputImage.fromFilePath(context, uri)
            val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.3f).build())
            labeler.process(image)
                .addOnSuccessListener { r -> labeler.close(); if (cont.isActive) cont.resume(r.map { it.text to it.confidence }) }
                .addOnFailureListener { labeler.close(); if (cont.isActive) cont.resume(emptyList()) }
        }.onFailure { if (cont.isActive) cont.resume(emptyList()) }
    }
}

/** Eyes-up cues: a soft pulse and a panned chime, so the phone can stay in a pocket. */
class Cues(private val context: Context) {
    private val prefs = context.getSharedPreferences("terra", Context.MODE_PRIVATE)
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()
    var chimes: Boolean
        get() = prefs.getBoolean("chimes", true)
        set(v) = prefs.edit().putBoolean("chimes", v).apply()

    fun pulse(times: Int = 2) {
        if (!haptics) return
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val pattern = LongArray(times * 2) { if (it % 2 == 0) (if (it == 0) 0L else 140L) else 60L }
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    /**
     * Two soft rising notes. [pan] runs from -1 (the habitat lies to your left) to 1 (right),
     * relative to your direction of travel; 0 when unknown.
     */
    fun chime(pan: Float = 0f) {
        if (!chimes) return
        runCatching {
            val rate = 22050
            val n = rate * 6 / 10
            val left = ((1 - pan) / 2).coerceIn(0.15f, 1f)
            val right = ((1 + pan) / 2).coerceIn(0.15f, 1f)
            val pcm = ShortArray(n * 2)
            for (i in 0 until n) {
                val t = i.toDouble() / rate
                val f = if (i < n / 2) 659.25 else 880.0
                val local = if (i < n / 2) i else i - n / 2
                val env = sin(PI * local / (n / 2.0)).let { it * it }
                val s = sin(2 * PI * f * t) * env * 0.25 * Short.MAX_VALUE
                pcm[2 * i] = (s * left).toInt().toShort()
                pcm[2 * i + 1] = (s * right).toInt().toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(n - 1)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        }
    }
}
