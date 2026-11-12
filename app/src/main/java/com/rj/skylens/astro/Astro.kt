package com.rj.skylens.astro

import kotlin.math.*

const val DEG = PI / 180.0
const val RAD = 180.0 / PI
const val EARTH_RADIUS_KM = 6378.137

/** Simple 3-vector. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun norm() = sqrt(x * x + y * y + z * z)
    fun unit(): Vec3 { val n = norm(); return if (n == 0.0) this else Vec3(x / n, y / n, z / n) }

    companion object {
        /** Unit vector from spherical coordinates (radians). */
        fun fromSpherical(lon: Double, lat: Double) =
            Vec3(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat))
    }
}

/** Row-major 3x3 matrix. */
class Mat3(val m: DoubleArray) {
    init { require(m.size == 9) }
    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z
    )

    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += m[i * 3 + k] * o.m[k * 3 + j]
            r[i * 3 + j] = s
        }
        return Mat3(r)
    }

    fun transpose() = Mat3(doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))

    companion object {
        val IDENTITY = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        fun rotX(a: Double) = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(a), sin(a), 0.0, -sin(a), cos(a)))
        fun rotZ(a: Double) = Mat3(doubleArrayOf(cos(a), sin(a), 0.0, -sin(a), cos(a), 0.0, 0.0, 0.0, 1.0))
    }
}

/** Horizontal coordinates in degrees; azimuth from true north through east. */
data class AltAz(val alt: Double, val az: Double) {
    companion object {
        fun fromEnu(v: Vec3): AltAz {
            val u = v.unit()
            var az = atan2(u.x, u.y) * RAD
            if (az < 0) az += 360.0
            return AltAz(asin(u.z.coerceIn(-1.0, 1.0)) * RAD, az)
        }
    }

    fun toEnu(): Vec3 {
        val a = alt * DEG; val z = az * DEG
        return Vec3(cos(a) * sin(z), cos(a) * cos(z), sin(a))
    }
}

/** Observer on the WGS84 ellipsoid. Angles in degrees, height in metres. */
data class Observer(val latDeg: Double, val lonDeg: Double, val heightM: Double = 0.0) {
    /** Earth-fixed position in km. */
    val ecef: Vec3 by lazy { Geo.geodeticToEcef(latDeg, lonDeg, heightM / 1000.0) }

    /** Rotation that takes an ECEF difference vector into local East/North/Up. */
    val ecefToEnu: Mat3 by lazy {
        val lat = latDeg * DEG; val lon = lonDeg * DEG
        Mat3(doubleArrayOf(
            -sin(lon), cos(lon), 0.0,
            -sin(lat) * cos(lon), -sin(lat) * sin(lon), cos(lat),
            cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat)
        ))
    }
}

object Geo {
    private const val F = 1.0 / 298.257223563
    private const val E2 = F * (2 - F)

    fun geodeticToEcef(latDeg: Double, lonDeg: Double, hKm: Double): Vec3 {
        val lat = latDeg * DEG; val lon = lonDeg * DEG
        val n = EARTH_RADIUS_KM / sqrt(1 - E2 * sin(lat) * sin(lat))
        return Vec3(
            (n + hKm) * cos(lat) * cos(lon),
            (n + hKm) * cos(lat) * sin(lon),
            (n * (1 - E2) + hKm) * sin(lat)
        )
    }
}

object Time {
    fun julianDate(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2440587.5

    /** Julian centuries since J2000.0. */
    fun centuries(jd: Double) = (jd - 2451545.0) / 36525.0

    /** Greenwich mean sidereal time in radians. */
    fun gmst(jd: Double): Double {
        val t = centuries(jd)
        var g = 280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * t * t - t * t * t / 38710000.0
        g %= 360.0
        if (g < 0) g += 360.0
        return g * DEG
    }
}

/**
 * Frames used by the renderer:
 *  - J2000 equatorial (catalogue stars, deep-sky, planets)
 *  - equatorial of date (after precession)
 *  - local ENU (east, north, up)
 */
object Frames {
    const val J2000_OBLIQUITY = 23.4392911 * DEG

    /** Ecliptic J2000 -> equatorial J2000. */
    val ECL_TO_EQ: Mat3 = Mat3.rotX(-J2000_OBLIQUITY)

    /** IAU 1976 precession matrix from J2000 to mean equator/equinox of date. */
    fun precession(jd: Double): Mat3 {
        val t = Time.centuries(jd)
        val as2r = DEG / 3600.0
        val zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * as2r
        val z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * as2r
        val theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * as2r
        // P = Rz(-z) * Ry(theta) * Rz(-zeta)
        val cz = cos(z); val sz = sin(z); val ct = cos(theta); val st = sin(theta)
        val cze = cos(zeta); val sze = sin(zeta)
        return Mat3(doubleArrayOf(
            cz * ct * cze - sz * sze, -cz * ct * sze - sz * cze, -cz * st,
            sz * ct * cze + cz * sze, -sz * ct * sze + cz * cze, -sz * st,
            st * cze, -st * sze, ct
        ))
    }

    /** Equatorial-of-date -> ENU for the given local sidereal time and latitude (radians). */
    fun equatorialToEnu(lst: Double, lat: Double): Mat3 {
        val sl = sin(lst); val cl = cos(lst); val sp = sin(lat); val cp = cos(lat)
        return Mat3(doubleArrayOf(
            -sl, cl, 0.0,
            -sp * cl, -sp * sl, cp,
            cp * cl, cp * sl, sp
        ))
    }

    /** Standard atmospheric refraction (degrees) for an apparent/true altitude (degrees). */
    fun refraction(altDeg: Double): Double {
        if (altDeg < -2.0) return 0.0
        val a = max(altDeg, -1.9)
        return 1.02 / tan((a + 10.3 / (a + 5.11)) * DEG) / 60.0
    }
}

/** Per-instant transform bundle for one observer. */
class SkyFrame(val jd: Double, val observer: Observer) {
    val gmst = Time.gmst(jd)
    val lst = gmst + observer.lonDeg * DEG
    val dateToEnu: Mat3 = Frames.equatorialToEnu(lst, observer.latDeg * DEG)
    val j2000ToDate: Mat3 = Frames.precession(jd)
    val j2000ToEnu: Mat3 = dateToEnu * j2000ToDate

    /** Observer position in the (true-of-date ≈ TEME) equatorial frame, km. */
    val observerEquatorialKm: Vec3 = Mat3.rotZ(-gmst) * observer.ecef

    fun raDecJ2000ToEnu(raHours: Double, decDeg: Double): Vec3 =
        j2000ToEnu * Vec3.fromSpherical(raHours * 15.0 * DEG, decDeg * DEG)
}
