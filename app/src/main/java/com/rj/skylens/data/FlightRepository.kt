package com.rj.skylens.data

import android.util.Log
import com.rj.skylens.astro.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.sin

/** One aircraft, as last reported by ADS-B. */
class Aircraft(
    val hex: String,
    val callsign: String,
    val registration: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    /** Geometric (GPS) altitude above the ellipsoid in metres, or barometric if missing. */
    val altM: Double,
    val groundSpeedMs: Double,
    val trackDeg: Double,
    val verticalRateMs: Double,
    /** Wall-clock time at which (lat, lon) was valid. */
    val positionTimeMs: Long
) {
    val label: String get() = callsign.ifBlank { registration.ifBlank { hex.uppercase() } }

    /** Dead-reckoned position at [timeMs]. Returns lat, lon (deg), alt (m). */
    fun extrapolate(timeMs: Long): Triple<Double, Double, Double> {
        val dt = ((timeMs - positionTimeMs) / 1000.0).coerceIn(-5.0, 600.0)
        val d = groundSpeedMs * dt
        val tr = trackDeg * DEG
        val r = EARTH_RADIUS_KM * 1000 + altM
        val dLat = d * cos(tr) / r
        val dLon = d * sin(tr) / (r * cos(lat * DEG).coerceAtLeast(0.01))
        val alt = (altM + verticalRateMs * dt).coerceAtLeast(0.0)
        return Triple(lat + dLat * RAD, lon + dLon * RAD, alt)
    }

    /** ENU vector (km, not normalised) from the observer to the aircraft at [timeMs]. */
    fun enuKm(observer: Observer, timeMs: Long): Vec3 {
        val (la, lo, al) = extrapolate(timeMs)
        val ecef = Geo.geodeticToEcef(la, lo, al / 1000.0)
        return observer.ecefToEnu * (ecef - observer.ecef)
    }
}

/**
 * Live aircraft from community ADS-B networks (adsb.lol, with airplanes.live as fallback).
 * Both expose the same "readsb" JSON format and need no API key.
 */
class FlightRepository {
    @Volatile var aircraft: List<Aircraft> = emptyList()
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var source: String = ""
        private set

    private val endpoints = listOf(
        "adsb.lol" to { lat: Double, lon: Double, nm: Int -> "https://api.adsb.lol/v2/point/%.4f/%.4f/%d".format(java.util.Locale.US, lat, lon, nm) },
        "airplanes.live" to { lat: Double, lon: Double, nm: Int -> "https://api.airplanes.live/v2/point/%.4f/%.4f/%d".format(java.util.Locale.US, lat, lon, nm) }
    )

    /** Blocking fetch; call from a background thread. */
    fun refresh(observer: Observer, radiusNm: Int = 100) {
        var lastEx: Exception? = null
        for ((name, urlFor) in endpoints) {
            try {
                val text = get(urlFor(observer.latDeg, observer.lonDeg, radiusNm))
                aircraft = parse(text, System.currentTimeMillis())
                source = name
                lastError = null
                return
            } catch (e: Exception) {
                Log.w(TAG, "Flight fetch from $name failed", e)
                lastEx = e
            }
        }
        lastError = lastEx?.message ?: "unavailable"
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "SkyLens-Android/1.0")
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "FlightRepository"
        private const val FT = 0.3048
        private const val KT = 0.514444

        fun parse(json: String, receivedAtMs: Long): List<Aircraft> {
            val root = JSONObject(json)
            val arr = root.optJSONArray("ac") ?: root.optJSONArray("aircraft") ?: return emptyList()
            val out = ArrayList<Aircraft>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                if (!a.has("lat") || !a.has("lon")) continue
                val baro = a.opt("alt_baro")
                if (baro is String && baro == "ground") continue
                val altFt = when {
                    a.has("alt_geom") -> a.optDouble("alt_geom")
                    baro is Number -> baro.toDouble()
                    else -> continue
                }
                if (altFt.isNaN()) continue
                val seenPos = a.optDouble("seen_pos", 0.0).let { if (it.isNaN()) 0.0 else it }
                out += Aircraft(
                    hex = a.optString("hex"),
                    callsign = a.optString("flight").trim(),
                    registration = a.optString("r"),
                    type = a.optString("t"),
                    lat = a.getDouble("lat"),
                    lon = a.getDouble("lon"),
                    altM = altFt * FT,
                    groundSpeedMs = a.optDouble("gs", 0.0).let { if (it.isNaN()) 0.0 else it } * KT,
                    trackDeg = a.optDouble("track", a.optDouble("true_heading", 0.0)).let { if (it.isNaN()) 0.0 else it },
                    verticalRateMs = a.optDouble("geom_rate", a.optDouble("baro_rate", 0.0)).let { if (it.isNaN()) 0.0 else it } * FT / 60.0,
                    positionTimeMs = receivedAtMs - (seenPos * 1000).toLong() // server reports age, so use local clock
                )
            }
            return out
        }
    }
}
