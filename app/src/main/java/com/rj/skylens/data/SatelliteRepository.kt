package com.rj.skylens.data

import android.content.Context
import android.util.Log
import com.rj.skylens.astro.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class SatGroup(val key: String, val label: String) {
    STATIONS("stations", "Space stations"),
    VISUAL("visual", "Brightest satellites"),
    STARLINK("starlink", "Starlink"),
    LAST30("last-30-days", "Recent launches"),
    WEATHER("weather", "Weather satellites"),
    SCIENCE("science", "Science (Hubble etc.)")
}

/** Live position of one satellite, for one moment. */
class SatPosition(
    val id: Int,
    val name: String,
    val look: SatLook,
    /** ENU direction one second later – used to animate between computations. */
    val enuNext: Vec3,
    val isStation: Boolean
)

class SatelliteRepository(private val context: Context) {
    @Volatile var satellites: List<Pair<Sgp4, Boolean>> = emptyList()
        private set
    @Volatile var lastError: String? = null
        private set

    private val cacheDir = File(context.filesDir, "tle").apply { mkdirs() }

    /** Loads cached TLEs and refreshes stale groups from CelesTrak. Blocking: call off the main thread. */
    fun load(groups: Set<SatGroup>, forceRefresh: Boolean = false) {
        val byId = LinkedHashMap<Int, Pair<Sgp4, Boolean>>()
        val errors = ArrayList<String>()
        for (g in groups) {
            val file = File(cacheDir, "${g.key}.tle")
            val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS
            if (stale || forceRefresh) {
                try {
                    val text = download("https://celestrak.org/NORAD/elements/gp.php?GROUP=${g.key}&FORMAT=tle")
                    if (Tle.parseMany(text).isNotEmpty()) file.writeText(text)
                } catch (e: Exception) {
                    Log.w(TAG, "TLE download failed for ${g.key}", e)
                    errors += "${g.label}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            if (file.exists()) {
                for (tle in Tle.parseMany(file.readText())) {
                    if (byId.containsKey(tle.noradId)) continue
                    val prop = runCatching { Sgp4(tle) }.getOrNull() ?: continue
                    if (prop.isDeepSpace) continue
                    byId[tle.noradId] = prop to (g == SatGroup.STATIONS)
                }
            }
        }
        satellites = byId.values.toList()
        lastError = if (errors.isEmpty()) null else errors.joinToString("; ")
    }

    /** Computes look angles for every loaded satellite above [minAltDeg]. */
    fun compute(frame: SkyFrame, minAltDeg: Double = -5.0): List<SatPosition> {
        val sunTeme = (frame.j2000ToDate * SolarSystem.sunJ2000(frame.jd)).unit()
        val nextFrame = SkyFrame(frame.jd + 1.0 / 86400.0, frame.observer)
        val minUp = kotlin.math.sin(minAltDeg * DEG)
        val out = ArrayList<SatPosition>()
        for ((prop, station) in satellites) {
            val st = prop.propagateJd(frame.jd) ?: continue
            val look = SatGeometry.look(st, frame, sunTeme)
            if (look.enu.z < minUp) continue
            val next = prop.propagateJd(nextFrame.jd)?.let { SatGeometry.look(it, nextFrame, null).enu } ?: look.enu
            out += SatPosition(prop.tle.noradId, prop.tle.name, look, next, station)
        }
        return out
    }

    /** Sky track of one satellite, from [fromMin] to [toMin] minutes relative to now, as ENU unit vectors. */
    fun track(id: Int, frame: SkyFrame, fromMin: Double = -2.0, toMin: Double = 10.0, stepSec: Double = 10.0): List<Vec3> {
        val prop = satellites.firstOrNull { it.first.tle.noradId == id }?.first ?: return emptyList()
        val out = ArrayList<Vec3>()
        var t = fromMin * 60
        while (t <= toMin * 60) {
            val f = SkyFrame(frame.jd + t / 86400.0, frame.observer)
            prop.propagateJd(f.jd)?.let { out += SatGeometry.look(it, f, null).enu }
            t += stepSec
        }
        return out
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "SkyLens-Android/1.0")
        try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "SatelliteRepository"
        /** CelesTrak updates GP data a few times a day and asks clients not to poll more often. */
        private const val MAX_AGE_MS = 6L * 3600 * 1000
    }
}
