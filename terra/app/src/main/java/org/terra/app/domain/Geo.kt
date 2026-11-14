package org.terra.app.domain

import org.terra.app.model.Season
import java.time.Instant
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_M = 6_371_000.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial compass bearing from point 1 to point 2, degrees 0–360. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}

/**
 * Poaching protection. Sensitive records are never published at their true position:
 * the point is snapped to the centre of a coarse grid cell (0.2° ≈ 22 km of latitude),
 * so every record in a cell collapses to the same public coordinate and the original
 * cannot be recovered from the published one.
 */
object GeoFuzzer {
    const val CELL_DEG = 0.2
    /** Half the diagonal of a cell at the equator — published as coordinate uncertainty. */
    const val UNCERTAINTY_M = 15_700

    data class Point(val lat: Double, val lon: Double)

    fun obscure(lat: Double, lon: Double): Point = Point(
        (floor(lat / CELL_DEG) + 0.5) * CELL_DEG,
        (floor(lon / CELL_DEG) + 0.5) * CELL_DEG
    )

    fun publicPoint(lat: Double, lon: Double, sensitive: Boolean): Point =
        if (sensitive) obscure(lat, lon) else Point(lat, lon)
}

/** Real time and real seasons are the content engine. */
object Phenology {
    fun season(epochMs: Long, lat: Double, zone: ZoneId = ZoneId.systemDefault()): Season {
        val month = Instant.ofEpochMilli(epochMs).atZone(zone).monthValue
        return season(month, lat)
    }

    fun season(month: Int, lat: Double): Season {
        val north = when (month) {
            3, 4, 5 -> Season.SPRING
            6, 7, 8 -> Season.SUMMER
            9, 10, 11 -> Season.AUTUMN
            else -> Season.WINTER
        }
        if (lat >= 0) return north
        return when (north) {
            Season.SPRING -> Season.AUTUMN
            Season.SUMMER -> Season.WINTER
            Season.AUTUMN -> Season.SPRING
            Season.WINTER -> Season.SUMMER
        }
    }

    /** Epoch millis at which the current season ends (first day of the next season's first month). */
    fun seasonEnd(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val d = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val startMonths = listOf(3, 6, 9, 12)
        val next = startMonths.firstOrNull { it > d.monthValue }
        val end = if (next != null) d.withMonth(next).withDayOfMonth(1)
        else d.plusYears(1).withMonth(3).withDayOfMonth(1)
        return end.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun seasonStart(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val d = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val start = when (d.monthValue) {
            1, 2 -> d.minusYears(1).withMonth(12)
            in 3..5 -> d.withMonth(3)
            in 6..8 -> d.withMonth(6)
            in 9..11 -> d.withMonth(9)
            else -> d.withMonth(12)
        }.withDayOfMonth(1)
        return start.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Local hour is in the dark half of the day (rough, latitude-agnostic). */
    fun isNight(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val h = Instant.ofEpochMilli(epochMs).atZone(zone).hour
        return h >= 20 || h < 5
    }
}
