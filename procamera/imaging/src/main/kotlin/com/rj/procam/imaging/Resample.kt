package com.rj.procam.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** High-quality separable resize of packed ARGB images (Lanczos-3, anti-aliased when shrinking). */
object Resample {

    private class Taps(val start: IntArray, val weights: Array<FloatArray>)

    private fun lanczos(x: Double): Double {
        val a = abs(x)
        if (a < 1e-9) return 1.0
        if (a >= 3.0) return 0.0
        val px = PI * a
        return 3.0 * sin(px) * sin(px / 3.0) / (px * px)
    }

    private fun taps(src: Int, dst: Int): Taps {
        val scale = src.toDouble() / dst
        val fscale = max(1.0, scale) // widen the kernel when shrinking
        val support = 3.0 * fscale
        val start = IntArray(dst)
        val weights = Array(dst) { FloatArray(0) }
        for (i in 0 until dst) {
            val center = (i + 0.5) * scale
            val lo = max(0, floor(center - support).toInt())
            val hi = min(src, ceil(center + support).toInt())
            val wts = FloatArray(max(1, hi - lo))
            var sum = 0.0
            for (j in lo until hi) {
                val wv = lanczos((j + 0.5 - center) / fscale)
                wts[j - lo] = wv.toFloat()
                sum += wv
            }
            if (sum == 0.0) {
                wts[0] = 1f
                sum = 1.0
            }
            for (j in wts.indices) wts[j] = (wts[j] / sum).toFloat()
            start[i] = min(lo, src - 1)
            weights[i] = wts
        }
        return Taps(start, weights)
    }

    private fun pack(r: Float, g: Float, b: Float): Int {
        val ri = clampI((r + 0.5f).toInt(), 0, 255)
        val gi = clampI((g + 0.5f).toInt(), 0, 255)
        val bi = clampI((b + 0.5f).toInt(), 0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    fun resize(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        require(src.size == sw * sh)
        if (sw == dw && sh == dh) return src
        val tx = taps(sw, dw)
        val mid = IntArray(dw * sh)
        Par.rows(sh) { r0, r1 ->
            for (y in r0 until r1) {
                val o = y * sw
                for (x in 0 until dw) {
                    val wts = tx.weights[x]
                    val s0 = tx.start[x]
                    var r = 0f
                    var g = 0f
                    var b = 0f
                    for (k in wts.indices) {
                        val p = src[o + min(sw - 1, s0 + k)]
                        val wv = wts[k]
                        r += wv * ((p shr 16) and 0xFF)
                        g += wv * ((p shr 8) and 0xFF)
                        b += wv * (p and 0xFF)
                    }
                    mid[y * dw + x] = pack(r, g, b)
                }
            }
        }
        val ty = taps(sh, dh)
        val out = IntArray(dw * dh)
        Par.rows(dh) { r0, r1 ->
            for (y in r0 until r1) {
                val wts = ty.weights[y]
                val s0 = ty.start[y]
                for (x in 0 until dw) {
                    var r = 0f
                    var g = 0f
                    var b = 0f
                    for (k in wts.indices) {
                        val p = mid[min(sh - 1, s0 + k) * dw + x]
                        val wv = wts[k]
                        r += wv * ((p shr 16) and 0xFF)
                        g += wv * ((p shr 8) and 0xFF)
                        b += wv * (p and 0xFF)
                    }
                    out[y * dw + x] = pack(r, g, b)
                }
            }
        }
        return out
    }

    /**
     * Keeps a 4x AI upscale honest: wherever the AI result, averaged back down to the
     * captured resolution, differs from what the camera actually recorded, the difference
     * is added back. The network may add fine texture, but it cannot shift colours,
     * brightness or the position of anything the sensor resolved.
     */
    fun backProject(low: IntArray, lw: Int, lh: Int, high: IntArray, scale: Int) {
        val hw = lw * scale
        val hh = lh * scale
        require(low.size == lw * lh && high.size == hw * hh)
        val err = Array(3) { Plane(lw, lh) }
        val inv = 1f / (scale * scale)
        Par.rows(lh) { r0, r1 ->
            for (y in r0 until r1) for (x in 0 until lw) {
                var r = 0
                var g = 0
                var b = 0
                for (yy in 0 until scale) {
                    val o = (y * scale + yy) * hw + x * scale
                    for (xx in 0 until scale) {
                        val p = high[o + xx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                val p = low[y * lw + x]
                val i = y * lw + x
                err[0].d[i] = ((p shr 16) and 0xFF) - r * inv
                err[1].d[i] = ((p shr 8) and 0xFF) - g * inv
                err[2].d[i] = (p and 0xFF) - b * inv
            }
        }
        // Only the smooth part of the error is corrected, so edges stay as sharp as the AI drew them.
        val sm = Array(3) { Filters.gauss5(err[it]) }
        Par.rows(hh) { r0, r1 ->
            for (y in r0 until r1) {
                val ly = (y + 0.5f) / scale - 0.5f
                for (x in 0 until hw) {
                    val lx = (x + 0.5f) / scale - 0.5f
                    val p = high[y * hw + x]
                    val r = ((p shr 16) and 0xFF) + Filters.bilinear(sm[0], lx, ly)
                    val g = ((p shr 8) and 0xFF) + Filters.bilinear(sm[1], lx, ly)
                    val b = (p and 0xFF) + Filters.bilinear(sm[2], lx, ly)
                    high[y * hw + x] = pack(r, g, b)
                }
            }
        }
    }
}
