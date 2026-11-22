package com.rj.app.context

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.rj.core.DriveState
import java.time.Duration
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Tracks the drive from GPS: distance, speed, slow traffic, and the locality we're passing through.
 */
class DriveTracker(private val context: Context) : LocationListener {
    private val lm = context.getSystemService(LocationManager::class.java)

    @Volatile var lastLocation: Location? = null
        private set
    @Volatile var lastAddress: Address? = null
        private set

    private var startedAt: ZonedDateTime = ZonedDateTime.now()
    private var distanceM = 0.0
    private var speedKph = 0.0
    private var crawlingSince: ZonedDateTime? = null
    private var lastGeocodeAt: ZonedDateTime? = null
    private var lastGeocodeLoc: Location? = null
    var destination: String? = null

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        startedAt = ZonedDateTime.now(); distanceM = 0.0; speedKph = 0.0; crawlingSince = null
        lastLocation = null
        if (!hasPermission()) return
        runCatching {
            lastLocation = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            .forEach { lm.requestLocationUpdates(it, 2_000L, 5f, this, Looper.getMainLooper()) }
    }

    fun stop() {
        runCatching { lm.removeUpdates(this) }
    }

    override fun onLocationChanged(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 60f) return
        val prev = lastLocation
        // Prefer GPS fixes; ignore a network fix arriving between GPS fixes.
        if (prev != null && prev.provider == LocationManager.GPS_PROVIDER &&
            loc.provider != LocationManager.GPS_PROVIDER && loc.time - prev.time < 10_000
        ) return

        if (prev != null) {
            val d = prev.distanceTo(loc).toDouble()
            val dt = (loc.time - prev.time) / 1000.0
            if (dt > 0 && d / dt < 70) distanceM += d // drop teleporting fixes (> 250 km/h)
            speedKph = if (loc.hasSpeed()) loc.speed * 3.6 else if (dt > 0) d / dt * 3.6 else speedKph
        } else if (loc.hasSpeed()) {
            speedKph = loc.speed * 3.6
        }
        val now = ZonedDateTime.now()
        crawlingSince = if (speedKph < 8) crawlingSince ?: now else null
        lastLocation = loc
    }

    @Deprecated("Required for API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    /** Reverse-geocode at most every 2 minutes / 1 km. Blocking: call off the main thread. */
    @Suppress("DEPRECATION")
    fun refreshAddress(now: ZonedDateTime = ZonedDateTime.now()) {
        val loc = lastLocation ?: return
        val recent = lastGeocodeAt?.let { Duration.between(it, now) < Duration.ofMinutes(2) } ?: false
        val near = lastGeocodeLoc?.let { it.distanceTo(loc) < 1000 } ?: false
        if (recent || (near && lastAddress != null)) return
        if (!Geocoder.isPresent()) return
        runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
        }.getOrNull()?.let { lastAddress = it }
        lastGeocodeAt = now
        lastGeocodeLoc = loc
    }

    fun locality(): String? = lastAddress?.let { a ->
        listOfNotNull(a.subLocality, a.locality ?: a.subAdminArea).distinct().joinToString(", ").ifBlank { null }
    }

    fun state(now: ZonedDateTime = ZonedDateTime.now()): DriveState {
        val elapsed = Duration.between(startedAt, now)
        val hours = elapsed.seconds / 3600.0
        // Only count crawling once we've actually set off (not sitting in the driveway).
        val crawl = crawlingSince?.takeIf { distanceM > 300 }?.let { Duration.between(it, now) } ?: Duration.ZERO
        return DriveState(
            startedAt = startedAt,
            elapsed = elapsed,
            distanceKm = distanceM / 1000.0,
            speedKph = speedKph,
            averageSpeedKph = if (hours > 0.01) distanceM / 1000.0 / hours else 0.0,
            crawlingFor = crawl,
            locality = locality(),
            destination = destination?.ifBlank { null },
        )
    }
}
