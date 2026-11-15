package com.rj.procam.imaging

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Look settings for the finishing stage. */
class Look(
    /** Stops the burst was deliberately under-exposed by (to protect highlights). */
    val underExposureEv: Float = 0f,
    /** 0 = no local tone mapping, 1 = full HDR look. */
    val hdrStrength: Float = 1f,
    val sharpen: Float = 0.7f,
    val saturation: Float = 1.08f,
)

object Finish {

    /** Unsharp mask with coring, so real edges are crisped and residual noise is not. */
    fun sharpen(y: Plane, amount: Float, noiseSigma: Float) {
        if (amount <= 0f) return
        val blur = Filters.gauss5(y)
        val t2 = (1.5f * noiseSigma).let { it * it } + 1e-8f
        Par.rows(y.h) { r0, r1 ->
            for (i in r0 * y.w until r1 * y.w) {
                val d = y.d[i] - blur.d[i]
                val d2 = d * d
                var add = amount * d * (d2 / (d2 + t2))
                if (add > 0.10f) add = 0.10f else if (add < -0.10f) add = -0.10f
                y.d[i] = clamp01(y.d[i] + add)
            }
        }
    }

    /** Smooths chroma noise while following luma edges. */
    fun denoiseChroma(m: Merged, strength: Float): Merged {
        if (strength <= 0f) return m
        val f = max(1, m.y.w / m.u.w)
        var guide = Filters.downsample(m.y, f)
        if (guide.w != m.u.w || guide.h != m.u.h) {
            val g = Plane(m.u.w, m.u.h)
            for (yy in 0 until g.h) for (xx in 0 until g.w) {
                g.d[yy * g.w + xx] = guide.d[min(guide.h - 1, yy) * guide.w + min(guide.w - 1, xx)]
            }
            guide = g
        }
        val eps = 0.0004f + 0.003f * strength
        val u = Filters.guided(guide, m.u, 3, eps)
        val v = Filters.guided(guide, m.v, 3, eps)
        return Merged(m.y, u, v)
    }

    /**
     * Local tone mapping. A smooth, edge-aware "base" brightness is computed at low
     * resolution; shadows of the base are lifted and its highlights rolled off, while the
     * fine detail riding on top of it keeps (or gains) contrast. This is what lets a frame
     * exposed for the highlights end up with readable shadows without looking flat.
     */
    fun toneMap(m: Merged, look: Look): Merged {
        val yP = m.y
        val w = yP.w
        val h = yP.h
        val f = max(1, (max(w, h) / 480f).roundToInt())
        val low = Filters.downsample(yP, f)
        val r = max(2, max(low.w, low.h) / 20)
        val (ca, cb) = Filters.guidedCoeffs(low, low, r, 0.01f)

        // Brightness statistics of the base layer.
        val hist = IntArray(256)
        val baseLow = Plane(low.w, low.h)
        for (i in low.d.indices) {
            val b = clamp01(ca.d[i] * low.d[i] + cb.d[i])
            baseLow.d[i] = b
            hist[(b * 255f).toInt()]++
        }
        fun percentile(p: Float): Float {
            val target = (low.d.size * p).toInt()
            var c = 0
            for (i in 0 until 256) {
                c += hist[i]
                if (c > target) return (i + 0.5f) / 255f
            }
            return 1f
        }
        val s = look.hdrStrength.coerceIn(0f, 1f)
        // Gain that undoes the deliberate under-exposure (values are gamma encoded).
        var gain = 2f.pow(look.underExposureEv / 2.2f)
        // Extra lift when the shadows would still be very dark.
        val p10 = percentile(0.10f)
        val lifted = gain * p10 / (1f + (gain - 1f) * p10)
        if (lifted < 0.14f) gain *= min(1f + 0.7f * s, (0.14f / max(lifted, 0.01f)).pow(0.6f))
        gain = gain.coerceIn(1f, 3.2f)
        val g1 = gain - 1f
        val clarity = 1f + 0.25f * s
        val curve = 0.10f * s // mid-tone contrast

        fun finishTone(v: Float): Float {
            val c = clamp01(v)
            return clamp01(c - curve * sin(2.0 * PI * c).toFloat() / (2f * PI.toFloat()))
        }

        val outY = Plane(w, h)
        Par.rows(h) { r0, r1 ->
            val ra = FloatArray(low.w)
            val rb = FloatArray(low.w)
            for (y in r0 until r1) {
                val ly = ((y + 0.5f) / f - 0.5f).coerceIn(0f, low.h - 1f)
                val y0 = min(ly.toInt(), max(0, low.h - 2))
                val y1 = min(low.h - 1, y0 + 1)
                val ay = ly - y0
                for (x in 0 until low.w) {
                    ra[x] = ca.d[y0 * low.w + x] * (1f - ay) + ca.d[y1 * low.w + x] * ay
                    rb[x] = cb.d[y0 * low.w + x] * (1f - ay) + cb.d[y1 * low.w + x] * ay
                }
                for (x in 0 until w) {
                    val lx = ((x + 0.5f) / f - 0.5f).coerceIn(0f, low.w - 1f)
                    val x0 = min(lx.toInt(), max(0, low.w - 2))
                    val x1 = min(low.w - 1, x0 + 1)
                    val ax = lx - x0
                    val a = ra[x0] * (1f - ax) + ra[x1] * ax
                    val b = rb[x0] * (1f - ax) + rb[x1] * ax
                    val v = yP.d[y * w + x]
                    val base = clamp01(a * v + b)
                    val denom = 1f + g1 * base
                    val g = gain / denom
                    val local = (1f - s) * v + s * base // s = 0 turns this into a plain global curve
                    val mapped = gain * local / (1f + g1 * local) + (v - local) * g * clarity
                    outY.d[y * w + x] = finishTone(mapped)
                }
            }
        }

        // Chroma follows the luma gain so colours keep their saturation.
        val outU = Plane(m.u.w, m.u.h)
        val outV = Plane(m.v.w, m.v.h)
        val sx = baseLow.w.toFloat() / m.u.w
        val sy = baseLow.h.toFloat() / m.u.h
        Par.rows(m.u.h) { r0, r1 ->
            for (y in r0 until r1) for (x in 0 until m.u.w) {
                val base = Filters.bilinear(baseLow, (x + 0.5f) * sx - 0.5f, (y + 0.5f) * sy - 0.5f)
                val g = (gain / (1f + g1 * base)).pow(0.9f) * look.saturation
                val i = y * m.u.w + x
                outU.d[i] = clamp01(0.5f + (m.u.d[i] - 0.5f) * g)
                outV.d[i] = clamp01(0.5f + (m.v.d[i] - 0.5f) * g)
            }
        }
        return Merged(outY, outU, outV)
    }

    /** YUV (BT.601 full range, as cameras deliver) to packed ARGB. Chroma is upsampled bilinearly. */
    fun toArgb(m: Merged): IntArray {
        val w = m.y.w
        val h = m.y.h
        val cw = m.u.w
        val ch = m.u.h
        val out = IntArray(w * h)
        val sx = cw.toFloat() / w
        val sy = ch.toFloat() / h
        Par.rows(h) { r0, r1 ->
            val ru = FloatArray(cw)
            val rv = FloatArray(cw)
            for (y in r0 until r1) {
                val cy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, ch - 1f)
                val y0 = min(cy.toInt(), max(0, ch - 2))
                val y1 = min(ch - 1, y0 + 1)
                val ay = cy - y0
                for (x in 0 until cw) {
                    ru[x] = m.u.d[y0 * cw + x] * (1f - ay) + m.u.d[y1 * cw + x] * ay
                    rv[x] = m.v.d[y0 * cw + x] * (1f - ay) + m.v.d[y1 * cw + x] * ay
                }
                for (x in 0 until w) {
                    val cx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, cw - 1f)
                    val x0 = min(cx.toInt(), max(0, cw - 2))
                    val x1 = min(cw - 1, x0 + 1)
                    val ax = cx - x0
                    val u = ru[x0] * (1f - ax) + ru[x1] * ax - 0.5f
                    val v = rv[x0] * (1f - ax) + rv[x1] * ax - 0.5f
                    val yy = m.y.d[y * w + x]
                    val r = to8(yy + 1.402f * v)
                    val g = to8(yy - 0.344136f * u - 0.714136f * v)
                    val b = to8(yy + 1.772f * u)
                    out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return out
    }

    private fun to8(v: Float): Int {
        val i = (v * 255f + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }
}
