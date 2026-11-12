package com.rj.skylens.astro

import kotlin.math.*

/** Two-line element set. */
class Tle(val name: String, val line1: String, val line2: String) {
    val noradId: Int = line1.substring(2, 7).trim().toInt()
    val epochJd: Double
    val bstar: Double
    val inclination: Double      // rad
    val raan: Double             // rad
    val eccentricity: Double
    val argPerigee: Double       // rad
    val meanAnomaly: Double      // rad
    val meanMotion: Double       // rad/min (Kozai)

    init {
        val yy = line1.substring(18, 20).trim().toInt()
        val year = if (yy < 57) 2000 + yy else 1900 + yy
        val day = line1.substring(20, 32).trim().toDouble()
        epochJd = jdOfYearStart(year) + day - 1.0
        bstar = parseExp(line1.substring(53, 61))
        inclination = line2.substring(8, 16).trim().toDouble() * DEG
        raan = line2.substring(17, 25).trim().toDouble() * DEG
        eccentricity = ("0." + line2.substring(26, 33).trim()).toDouble()
        argPerigee = line2.substring(34, 42).trim().toDouble() * DEG
        meanAnomaly = line2.substring(43, 51).trim().toDouble() * DEG
        meanMotion = line2.substring(52, 63).trim().toDouble() * 2 * PI / 1440.0
    }

    /** Orbital period in minutes. */
    val periodMinutes: Double get() = 2 * PI / meanMotion

    companion object {
        private fun jdOfYearStart(year: Int): Double {
            // JD of Jan 0.0 + 1 = Jan 1.0, Gregorian calendar
            val y = year - 1
            val a = y / 100
            val b = 2 - a + a / 4
            return floor(365.25 * (y + 4716)) + floor(30.6001 * 14) + 1 + b - 1524.5 // Jan 1.0
        }

        /** Parses the TLE "assumed decimal point" exponent format, e.g. " 12345-3" -> 0.12345e-3. */
        fun parseExp(s: String): Double {
            val t = s.trim()
            if (t.isEmpty()) return 0.0
            var sign = 1.0
            var body = t
            if (body[0] == '-') { sign = -1.0; body = body.substring(1) } else if (body[0] == '+') body = body.substring(1)
            val idx = body.indexOfLast { it == '-' || it == '+' }
            return if (idx > 0) {
                val mant = ("0." + body.substring(0, idx)).toDouble()
                val exp = body.substring(idx).toInt()
                sign * mant * 10.0.pow(exp)
            } else sign * ("0.$body").toDouble()
        }

        fun parseMany(text: String): List<Tle> {
            val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val out = ArrayList<Tle>()
            var i = 0
            while (i < lines.size) {
                if (i + 2 < lines.size + 0 && !lines[i].startsWith("1 ") && lines.getOrNull(i + 1)?.startsWith("1 ") == true &&
                    lines.getOrNull(i + 2)?.startsWith("2 ") == true) {
                    runCatching { Tle(lines[i].trim(), lines[i + 1], lines[i + 2]) }.getOrNull()?.let { out += it }
                    i += 3
                } else if (lines[i].startsWith("1 ") && lines.getOrNull(i + 1)?.startsWith("2 ") == true) {
                    runCatching { Tle("NORAD " + lines[i].substring(2, 7).trim(), lines[i], lines[i + 1]) }.getOrNull()?.let { out += it }
                    i += 2
                } else i++
            }
            return out
        }
    }
}

/** TEME state vector in km and km/s. */
class StateVector(val r: Vec3, val v: Vec3)

/**
 * SGP4 propagator (near-earth branch, WGS-72 constants) following Vallado et al.,
 * "Revisiting Spacetrack Report #3" (2006). Deep-space objects (period >= 225 min)
 * are rejected; they are not needed for naked-eye satellite spotting.
 */
class Sgp4(val tle: Tle) {
    private val xke: Double
    private val j2 = 0.001082616
    private val j3 = -0.00000253881
    private val j4 = -0.00000165597
    private val j3oj2 = j3 / j2
    private val re = 6378.135
    private val vkmpersec: Double

    private val ecco = tle.eccentricity
    private val inclo = tle.inclination
    private val nodeo = tle.raan
    private val argpo = tle.argPerigee
    private val mo = tle.meanAnomaly
    private val bstar = tle.bstar

    private val no: Double
    private val isimp: Boolean
    private val aycof: Double
    private val con41: Double
    private val cc1: Double
    private val cc4: Double
    private val cc5: Double
    private val d2: Double
    private val d3: Double
    private val d4: Double
    private val delmo: Double
    private val eta: Double
    private val argpdot: Double
    private val omgcof: Double
    private val sinmao: Double
    private val t2cof: Double
    private val t3cof: Double
    private val t4cof: Double
    private val t5cof: Double
    private val x1mth2: Double
    private val x7thm1: Double
    private val mdot: Double
    private val nodedot: Double
    private val xlcof: Double
    private val xmcof: Double
    private val nodecf: Double

    val isDeepSpace: Boolean

    init {
        val mu = 398600.8
        xke = 60.0 / sqrt(re * re * re / mu)
        vkmpersec = re * xke / 60.0
        val x2o3 = 2.0 / 3.0

        val eccsq = ecco * ecco
        val omeosq = 1.0 - eccsq
        val rteosq = sqrt(omeosq)
        val cosio = cos(inclo)
        val cosio2 = cosio * cosio
        val ak = (xke / tle.meanMotion).pow(x2o3)
        val d1 = 0.75 * j2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0))
        del = d1 / (adel * adel)
        no = tle.meanMotion / (1.0 + del)
        val ao = (xke / no).pow(x2o3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1.0 - ecco)

        isDeepSpace = 2 * PI / no >= 225.0

        val ss = 78.0 / re + 1.0
        val qzms2t = ((120.0 - 78.0) / re).pow(4)
        isimp = rp < 220.0 / re + 1.0
        var sfour = ss
        var qzms24 = qzms2t
        val perige = (rp - 1.0) * re
        if (perige < 156.0) {
            sfour = perige - 78.0
            if (perige < 98.0) sfour = 20.0
            qzms24 = ((120.0 - sfour) / re).pow(4)
            sfour = sfour / re + 1.0
        }
        val pinvsq = 1.0 / posq
        val tsi = 1.0 / (ao - sfour)
        eta = ao * ecco * tsi
        val etasq = eta * eta
        val eeta = ecco * eta
        val psisq = abs(1.0 - etasq)
        val coef = qzms24 * tsi.pow(4)
        val coef1 = coef / psisq.pow(3.5)
        val cc2 = coef1 * no * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
            0.375 * j2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)))
        cc1 = bstar * cc2
        val cc3 = if (ecco > 1.0e-4) -2.0 * coef * tsi * j3oj2 * no * sinio / ecco else 0.0
        x1mth2 = 1.0 - cosio2
        cc4 = 2.0 * no * coef1 * ao * omeosq * (eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) -
            j2 * tsi / (ao * psisq) * (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
            0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)))
        cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
        val cosio4 = cosio2 * cosio2
        val temp1 = 1.5 * j2 * pinvsq * no
        val temp2 = 0.5 * temp1 * j2 * pinvsq
        val temp3 = -0.46875 * j4 * pinvsq * pinvsq * no
        mdot = no + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
            temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
        val xhdot1 = -temp1 * cosio
        nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
        omgcof = bstar * cc3 * cos(argpo)
        xmcof = if (ecco > 1.0e-4) -x2o3 * coef * bstar / eeta else 0.0
        nodecf = 3.5 * omeosq * xhdot1 * cc1
        t2cof = 1.5 * cc1
        xlcof = if (abs(cosio + 1.0) > 1.5e-12) -0.25 * j3oj2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
        else -0.25 * j3oj2 * sinio * (3.0 + 5.0 * cosio) / 1.5e-12
        aycof = -0.5 * j3oj2 * sinio
        delmo = (1.0 + eta * cos(mo)).pow(3)
        sinmao = sin(mo)
        x7thm1 = 7.0 * cosio2 - 1.0
        if (!isimp) {
            val cc1sq = cc1 * cc1
            d2 = 4.0 * ao * tsi * cc1sq
            val temp = d2 * tsi * cc1 / 3.0
            d3 = (17.0 * ao + sfour) * temp
            d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
            t3cof = d2 + 2.0 * cc1sq
            t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
            t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq))
        } else {
            d2 = 0.0; d3 = 0.0; d4 = 0.0; t3cof = 0.0; t4cof = 0.0; t5cof = 0.0
        }
    }

    /** Propagates to [tsince] minutes from the TLE epoch. Returns null on decay / error. */
    fun propagate(tsince: Double): StateVector? {
        val twopi = 2 * PI
        val x2o3 = 2.0 / 3.0
        val t = tsince
        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf
        var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1.0 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2
        if (!isimp) {
            val delomg = omgcof * t
            val delm = xmcof * ((1.0 + eta * cos(xmdf)).pow(3) - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * t
            val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof)
        }
        val am = (xke / no).pow(x2o3) * tempa * tempa
        val nm = xke / am.pow(1.5)
        var em = ecco - tempe
        if (em >= 1.0 || em < -0.001 || am < 0.95) return null
        if (em < 1.0e-6) em = 1.0e-6
        mm += no * templ
        var xlm = mm + argpm + nodem
        nodem %= twopi
        argpm %= twopi
        xlm %= twopi
        mm = (xlm - argpm - nodem) % twopi

        val sinip = sin(inclo)
        val cosip = cos(inclo)
        val axnl = em * cos(argpm)
        var temp = 1.0 / (am * (1.0 - em * em))
        val aynl = em * sin(argpm) + temp * aycof
        val xl = mm + argpm + nodem + temp * xlcof * axnl

        val u = (xl - nodem) % twopi
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0
        var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 += tem5
            ktr++
        }
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        if (pl < 0.0) return null
        val rl = am * (1.0 - ecose)
        val rdotl = sqrt(am) * esine / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * j2 * temp
        val temp2 = temp1 * temp

        val mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodem + 1.5 * temp2 * cosip * sin2u
        val xinc = inclo + 1.5 * temp2 * cosip * sinip * cos2u
        val mvt = rdotl - nm * temp1 * x1mth2 * sin2u / xke
        val rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / xke

        val sinsu = sin(su); val cossu = cos(su)
        val snod = sin(xnode); val cnod = cos(xnode)
        val sini = sin(xinc); val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu
        val vy = xmy * cossu - snod * sinsu
        val vz = sini * cossu

        if (mrt < 1.0) return null // decayed
        return StateVector(
            Vec3(mrt * ux * re, mrt * uy * re, mrt * uz * re),
            Vec3((mvt * ux + rvdot * vx) * vkmpersec, (mvt * uy + rvdot * vy) * vkmpersec, (mvt * uz + rvdot * vz) * vkmpersec)
        )
    }

    fun propagateJd(jd: Double): StateVector? = propagate((jd - tle.epochJd) * 1440.0)
}

/** Topocentric look angles for a satellite. */
data class SatLook(
    val enu: Vec3,          // unit vector
    val rangeKm: Double,
    val altitudeKm: Double, // above Earth's surface
    val speedKmS: Double,
    val sunlit: Boolean
)

object SatGeometry {
    fun look(state: StateVector, frame: SkyFrame, sunDirTeme: Vec3?): SatLook {
        val ecef = Mat3.rotZ(frame.gmst) * state.r
        val diff = ecef - frame.observer.ecef
        val enu = frame.observer.ecefToEnu * diff
        val range = enu.norm()
        val sunlit = sunDirTeme?.let { isSunlit(state.r, it) } ?: true
        return SatLook(enu * (1.0 / range), range, state.r.norm() - EARTH_RADIUS_KM, state.v.norm(), sunlit)
    }

    /** Cylindrical Earth-shadow test. */
    fun isSunlit(r: Vec3, sunUnit: Vec3): Boolean {
        val proj = r.dot(sunUnit)
        if (proj > 0) return true
        val perp = r - sunUnit * proj
        return perp.norm() > EARTH_RADIUS_KM
    }
}
