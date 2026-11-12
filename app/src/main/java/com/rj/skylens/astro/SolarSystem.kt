package com.rj.skylens.astro

import kotlin.math.*

enum class Body(val label: String, val absMag: Double, val radiusKm: Double, val color: Int) {
    SUN("Sun", -26.74, 696_000.0, 0xFFFFE680.toInt()),
    MOON("Moon", -12.7, 1737.4, 0xFFEFEFE6.toInt()),
    MERCURY("Mercury", -0.42, 2439.7, 0xFFC8B8A8.toInt()),
    VENUS("Venus", -4.40, 6051.8, 0xFFFFF5D0.toInt()),
    MARS("Mars", -1.52, 3389.5, 0xFFFF8A5C.toInt()),
    JUPITER("Jupiter", -9.40, 69_911.0, 0xFFF3D9B1.toInt()),
    SATURN("Saturn", -8.88, 58_232.0, 0xFFF0DC9A.toInt()),
    URANUS("Uranus", -7.19, 25_362.0, 0xFFB5E8EE.toInt()),
    NEPTUNE("Neptune", -6.87, 24_622.0, 0xFF7FA6FF.toInt()),
    PLUTO("Pluto", -1.0, 1188.3, 0xFFD0C0B0.toInt());
}

/** Result for one solar-system body as seen from the observer. */
data class BodyPosition(
    val body: Body,
    /** Unit vector in local ENU. */
    val enu: Vec3,
    val distanceKm: Double,
    val magnitude: Double,
    /** Illuminated fraction 0..1 (for Moon & planets). */
    val illuminated: Double,
    /** Angular radius in degrees. */
    val angularRadiusDeg: Double,
    /** Distance from Sun in AU (planets only, 0 otherwise). */
    val sunDistanceAu: Double = 0.0
)

object SolarSystem {
    const val AU_KM = 149_597_870.7

    /** JPL "Approximate positions of the planets" (Standish), valid 1800-2050. */
    private class Elements(
        val a: Double, val e: Double, val i: Double, val l: Double, val peri: Double, val node: Double,
        val da: Double, val de: Double, val di: Double, val dl: Double, val dperi: Double, val dnode: Double
    )

    private val elements = mapOf(
        Body.MERCURY to Elements(0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593,
            0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081),
        Body.VENUS to Elements(0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255,
            0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418),
        Body.SUN to Elements(1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0,
            0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0), // Earth-Moon barycentre
        Body.MARS to Elements(1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891,
            0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343),
        Body.JUPITER to Elements(5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909,
            -0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106),
        Body.SATURN to Elements(9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448,
            -0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794),
        Body.URANUS to Elements(19.18916464, 0.04725744, 0.77263783, 313.23810451, 170.95427630, 74.01692503,
            -0.00196176, -0.00004397, -0.00242939, 428.48202785, 0.40805281, 0.04240589),
        Body.NEPTUNE to Elements(30.06992276, 0.00859048, 1.77004347, -55.12002969, 44.96476227, 131.78422574,
            0.00026291, 0.00005105, 0.00035372, 218.45945325, -0.32241464, -0.05086530),
        Body.PLUTO to Elements(39.48211675, 0.24882730, 17.14001206, 238.92903833, 224.06891629, 110.30393684,
            -0.00031596, 0.00005170, 0.00004818, 145.20780515, -0.04062942, -0.01183482)
    )

    /** Heliocentric ecliptic J2000 position in AU. */
    private fun heliocentric(el: Elements, t: Double): Vec3 {
        val a = el.a + el.da * t
        val e = el.e + el.de * t
        val i = (el.i + el.di * t) * DEG
        val l = el.l + el.dl * t
        val peri = el.peri + el.dperi * t
        val node = (el.node + el.dnode * t) * DEG
        val w = peri * DEG - node
        var m = ((l - peri) % 360.0) * DEG
        if (m > PI) m -= 2 * PI
        if (m < -PI) m += 2 * PI
        val ea = solveKepler(m, e)
        val xp = a * (cos(ea) - e)
        val yp = a * sqrt(1 - e * e) * sin(ea)
        val cw = cos(w); val sw = sin(w); val cn = cos(node); val sn = sin(node); val ci = cos(i); val si = sin(i)
        return Vec3(
            (cw * cn - sw * sn * ci) * xp + (-sw * cn - cw * sn * ci) * yp,
            (cw * sn + sw * cn * ci) * xp + (-sw * sn + cw * cn * ci) * yp,
            (sw * si) * xp + (cw * si) * yp
        )
    }

    fun solveKepler(m: Double, e: Double): Double {
        var ea = m + e * sin(m)
        repeat(12) {
            val d = (ea - e * sin(ea) - m) / (1 - e * cos(ea))
            ea -= d
            if (abs(d) < 1e-12) return ea
        }
        return ea
    }

    /** Geocentric J2000 equatorial vector of the Sun in AU. */
    fun sunJ2000(jd: Double): Vec3 {
        val t = Time.centuries(jd)
        val earth = heliocentric(elements.getValue(Body.SUN), t)
        return Frames.ECL_TO_EQ * (earth * -1.0)
    }

    fun compute(frame: SkyFrame): List<BodyPosition> {
        val t = Time.centuries(frame.jd)
        val earth = heliocentric(elements.getValue(Body.SUN), t)
        val out = ArrayList<BodyPosition>(10)

        // Sun
        val sunGeo = earth * -1.0
        val sunDist = sunGeo.norm()
        out += BodyPosition(
            Body.SUN, (frame.j2000ToEnu * (Frames.ECL_TO_EQ * sunGeo)).unit(), sunDist * AU_KM,
            Body.SUN.absMag, 1.0, asin(Body.SUN.radiusKm / (sunDist * AU_KM)) * RAD
        )

        for (body in listOf(Body.MERCURY, Body.VENUS, Body.MARS, Body.JUPITER, Body.SATURN, Body.URANUS, Body.NEPTUNE, Body.PLUTO)) {
            val helio = heliocentric(elements.getValue(body), t)
            // light-time correction (one iteration is plenty here)
            var geo = helio - earth
            val lt = geo.norm() * 499.004784 / 86400.0 / 36525.0
            geo = heliocentric(elements.getValue(body), t - lt) - earth
            val r = helio.norm(); val delta = geo.norm()
            val cosPhase = ((r * r + delta * delta - sunDist * sunDist) / (2 * r * delta)).coerceIn(-1.0, 1.0)
            val phaseDeg = acos(cosPhase) * RAD
            val mag = body.absMag + 5 * log10(r * delta) + phaseCorrection(body, phaseDeg)
            out += BodyPosition(
                body, (frame.j2000ToEnu * (Frames.ECL_TO_EQ * geo)).unit(), delta * AU_KM, mag,
                (1 + cosPhase) / 2, asin(body.radiusKm / (delta * AU_KM)) * RAD, r
            )
        }

        out += moon(frame, sunGeo)
        return out
    }

    private fun phaseCorrection(body: Body, phase: Double): Double = when (body) {
        Body.MERCURY -> 0.0380 * phase - 0.000273 * phase * phase + 0.000002 * phase * phase * phase
        Body.VENUS -> 0.0009 * phase + 0.000239 * phase * phase - 0.00000065 * phase * phase * phase
        Body.MARS -> 0.016 * phase
        Body.JUPITER -> 0.005 * phase
        Body.SATURN -> 0.0 // ignores ring tilt
        else -> 0.0
    }

    /**
     * Geocentric Moon (equatorial of date, km) using Paul Schlyter's method with the
     * main perturbation terms; accuracy ~ a few arcminutes.
     */
    fun moonGeocentricOfDate(jd: Double): Vec3 {
        val d = jd - 2451543.5
        val n = (125.1228 - 0.0529538083 * d) * DEG
        val i = 5.1454 * DEG
        val w = (318.0634 + 0.1643573223 * d) * DEG
        val a = 60.2666
        val e = 0.054900
        val m = norm2pi((115.3654 + 13.0649929509 * d) * DEG)
        val ea = solveKepler(m, e)
        val xv = a * (cos(ea) - e)
        val yv = a * sqrt(1 - e * e) * sin(ea)
        val v = atan2(yv, xv)
        var r = sqrt(xv * xv + yv * yv)
        val xe = r * (cos(n) * cos(v + w) - sin(n) * sin(v + w) * cos(i))
        val ye = r * (sin(n) * cos(v + w) + cos(n) * sin(v + w) * cos(i))
        val ze = r * sin(v + w) * sin(i)
        var lon = atan2(ye, xe) * RAD
        var lat = atan2(ze, sqrt(xe * xe + ye * ye)) * RAD

        val ws = 282.9404 + 4.70935e-5 * d
        val ms = (356.0470 + 0.9856002585 * d)
        val ls = ws + ms
        val lm = (n + w + m) * RAD
        val mm = m * RAD
        val dd = lm - ls
        val f = lm - n * RAD
        fun s(x: Double) = sin(x * DEG)
        fun c(x: Double) = cos(x * DEG)
        lon += -1.274 * s(mm - 2 * dd) + 0.658 * s(2 * dd) - 0.186 * s(ms) - 0.059 * s(2 * mm - 2 * dd) -
            0.057 * s(mm - 2 * dd + ms) + 0.053 * s(mm + 2 * dd) + 0.046 * s(2 * dd - ms) + 0.041 * s(mm - ms) -
            0.035 * s(dd) - 0.031 * s(mm + ms) - 0.015 * s(2 * f - 2 * dd) + 0.011 * s(mm - 4 * dd)
        lat += -0.173 * s(f - 2 * dd) - 0.055 * s(mm - f - 2 * dd) - 0.046 * s(mm + f - 2 * dd) +
            0.033 * s(f + 2 * dd) + 0.017 * s(2 * mm + f)
        r += -0.58 * c(mm - 2 * dd) - 0.46 * c(2 * dd)

        val ecl = (23.4393 - 3.563e-7 * d) * DEG
        val eclVec = Vec3.fromSpherical(lon * DEG, lat * DEG) * (r * EARTH_RADIUS_KM)
        return Mat3.rotX(-ecl) * eclVec
    }

    private fun moon(frame: SkyFrame, sunGeoEclAu: Vec3): BodyPosition {
        val geo = moonGeocentricOfDate(frame.jd)
        val topo = geo - frame.observerEquatorialKm
        val dist = topo.norm()
        val sunDate = frame.j2000ToDate * (Frames.ECL_TO_EQ * sunGeoEclAu)
        val cosElong = geo.unit().dot(sunDate.unit())
        // phase angle ≈ 180° - elongation (good to ~0.1° for the Moon)
        val phaseAngle = PI - acos(cosElong.coerceIn(-1.0, 1.0))
        val illum = (1 + cos(phaseAngle)) / 2
        val pa = phaseAngle * RAD
        val mag = -12.73 + 0.026 * abs(pa) + 4e-9 * pa.pow(4)
        return BodyPosition(
            Body.MOON, (frame.dateToEnu * topo).unit(), dist, mag, illum,
            asin(Body.MOON.radiusKm / dist) * RAD
        )
    }

    /** Age-style description of lunar phase. */
    fun moonPhaseName(illum: Double, waxing: Boolean): String = when {
        illum < 0.03 -> "New Moon"
        illum > 0.97 -> "Full Moon"
        illum in 0.47..0.53 -> if (waxing) "First Quarter" else "Last Quarter"
        illum < 0.5 -> if (waxing) "Waxing Crescent" else "Waning Crescent"
        else -> if (waxing) "Waxing Gibbous" else "Waning Gibbous"
    }

    /** Waxing = elongation from the Sun is growing (new -> full). */
    fun moonWaxing(jd: Double): Boolean {
        val now = moonGeocentricOfDate(jd).unit()
        val later = moonGeocentricOfDate(jd + 0.5).unit()
        val sun = (Frames.precession(jd) * sunJ2000(jd)).unit()
        return later.dot(sun) < now.dot(sun)
    }

    private fun norm2pi(x: Double): Double { var r = x % (2 * PI); if (r < 0) r += 2 * PI; return r }
}
