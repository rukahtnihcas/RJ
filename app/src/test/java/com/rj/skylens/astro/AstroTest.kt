package com.rj.skylens.astro

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Reference values come from PyEphem 4.x (planets, Moon, stars; refraction off) and the
 * python-sgp4 package (Vallado's reference SGP4) for the same inputs.
 */
class AstroTest {
    private val frame = SkyFrame(
        Time.julianDate(Instant.parse("2026-11-12T22:00:00Z").toEpochMilli()),
        Observer(51.5, -0.12, 20.0)
    )

    private fun assertAltAz(expAlt: Double, expAz: Double, v: Vec3, tol: Double) {
        val aa = AltAz.fromEnu(v)
        assertEquals("alt", expAlt, aa.alt, tol)
        var d = aa.az - expAz
        if (d > 180) d -= 360
        if (d < -180) d += 360
        assertEquals("az", 0.0, d, tol)
    }

    @Test fun stars() {
        assertAltAz(-6.806, 108.419, frame.raDecJ2000ToEnu(6.75248, -16.7161), 0.02) // Sirius
        assertAltAz(22.690, 304.444, frame.raDecJ2000ToEnu(18.61564, 38.7837), 0.02) // Vega
    }

    @Test fun solarSystem() {
        val expected = mapOf(
            Body.SUN to (-50.537 to 318.672),
            Body.MOON to (-34.802 to 267.357),
            Body.MERCURY to (-48.806 to 342.682),
            Body.VENUS to (-49.165 to 2.216),
            Body.MARS to (-8.248 to 52.666),
            Body.JUPITER to (-9.910 to 52.757),
            Body.SATURN to (38.314 to 196.605),
            Body.URANUS to (46.210 to 119.054),
            Body.NEPTUNE to (35.357 to 204.357)
        )
        val got = SolarSystem.compute(frame).associateBy { it.body }
        for ((body, ref) in expected) assertAltAz(ref.first, ref.second, got.getValue(body).enu, 0.15)
        assertEquals(0.12, got.getValue(Body.MOON).illuminated, 0.01)
    }

    @Test fun sgp4MatchesReference() {
        val tle = Tle(
            "ISS",
            "1 25544U 98067A   26315.50000000  .00016717  00000-0  30270-3 0  9999",
            "2 25544  51.6400 208.9163 0006317  69.9862  25.2906 15.49560000 12348"
        )
        val sgp4 = Sgp4(tle)
        val ref = mapOf(
            0.0 to doubleArrayOf(2578.514430, -3367.698197, 5296.114519, 6.470973578, 4.075846903, -0.554796922),
            360.0 to doubleArrayOf(-2289.875555, -4884.554003, 4118.543525, 6.658168806, 0.096218034, 3.802729207),
            1440.0 to doubleArrayOf(-2502.156889, 3472.694369, -5289.789609, -6.679880898, -3.638657166, 0.773813742)
        )
        for ((t, e) in ref) {
            val s = sgp4.propagate(t)!!
            assertEquals(e[0], s.r.x, 1e-4); assertEquals(e[1], s.r.y, 1e-4); assertEquals(e[2], s.r.z, 1e-4)
            assertEquals(e[3], s.v.x, 1e-7); assertEquals(e[4], s.v.y, 1e-7); assertEquals(e[5], s.v.z, 1e-7)
        }
        val look = SatGeometry.look(sgp4.propagateJd(frame.jd)!!, frame, null)
        assertAltAz(-25.360, 313.158, look.enu, 0.05)
        assertEquals(6347.4, look.rangeKm, 2.0)
    }

    @Test fun tleExponentParsing() {
        assertEquals(0.30270e-3, Tle.parseExp(" 30270-3"), 1e-12)
        assertEquals(-0.11606e-4, Tle.parseExp("-11606-4"), 1e-12)
        assertEquals(0.0, Tle.parseExp(" 00000-0"), 1e-12)
    }
}
