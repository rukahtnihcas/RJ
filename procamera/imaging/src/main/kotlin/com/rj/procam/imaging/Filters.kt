package com.rj.procam.imaging

import kotlin.math.max
import kotlin.math.min

internal object Filters {

    /** Box-average downsample by an integer factor (edge blocks are averaged over what exists). */
    fun downsample(src: Plane, f: Int): Plane {
        if (f <= 1) return Plane(src.w, src.h, src.d.copyOf())
        val w = (src.w + f - 1) / f
        val h = (src.h + f - 1) / f
        val out = Plane(w, h)
        Par.rows(h) { r0, r1 ->
            for (y in r0 until r1) {
                val ya = y * f
                val yb = min(src.h, ya + f)
                for (x in 0 until w) {
                    val xa = x * f
                    val xb = min(src.w, xa + f)
                    var s = 0f
                    for (yy in ya until yb) {
                        val o = yy * src.w
                        for (xx in xa until xb) s += src.d[o + xx]
                    }
                    out.d[y * w + x] = s / ((yb - ya) * (xb - xa))
                }
            }
        }
        return out
    }

    /** Box blur with radius r (window 2r+1), edges handled by shrinking the window. */
    fun box(src: Plane, r: Int): Plane {
        val w = src.w
        val h = src.h
        val tmp = Plane(w, h)
        Par.rows(h) { r0, r1 ->
            val pre = DoubleArray(w + 1)
            for (y in r0 until r1) {
                val o = y * w
                for (x in 0 until w) pre[x + 1] = pre[x] + src.d[o + x]
                for (x in 0 until w) {
                    val a = max(0, x - r)
                    val b = min(w, x + r + 1)
                    tmp.d[o + x] = ((pre[b] - pre[a]) / (b - a)).toFloat()
                }
            }
        }
        val out = Plane(w, h)
        Par.rows(w) { c0, c1 ->
            val pre = DoubleArray(h + 1)
            for (x in c0 until c1) {
                for (y in 0 until h) pre[y + 1] = pre[y] + tmp.d[y * w + x]
                for (y in 0 until h) {
                    val a = max(0, y - r)
                    val b = min(h, y + r + 1)
                    out.d[y * w + x] = ((pre[b] - pre[a]) / (b - a)).toFloat()
                }
            }
        }
        return out
    }

    /** Separable 5-tap binomial blur (sigma ~ 1). */
    fun gauss5(src: Plane): Plane {
        val w = src.w
        val h = src.h
        val tmp = Plane(w, h)
        Par.rows(h) { r0, r1 ->
            for (y in r0 until r1) {
                val o = y * w
                for (x in 0 until w) {
                    val a = src.d[o + max(0, x - 2)]
                    val b = src.d[o + max(0, x - 1)]
                    val c = src.d[o + x]
                    val d = src.d[o + min(w - 1, x + 1)]
                    val e = src.d[o + min(w - 1, x + 2)]
                    tmp.d[o + x] = (a + e + 4f * (b + d) + 6f * c) * (1f / 16f)
                }
            }
        }
        val out = Plane(w, h)
        Par.rows(h) { r0, r1 ->
            for (y in r0 until r1) {
                val o = y * w
                val oa = max(0, y - 2) * w
                val ob = max(0, y - 1) * w
                val od = min(h - 1, y + 1) * w
                val oe = min(h - 1, y + 2) * w
                for (x in 0 until w) {
                    out.d[o + x] = (tmp.d[oa + x] + tmp.d[oe + x] +
                        4f * (tmp.d[ob + x] + tmp.d[od + x]) + 6f * tmp.d[o + x]) * (1f / 16f)
                }
            }
        }
        return out
    }

    /**
     * Guided-filter coefficients: q = a * guide + b approximates src while following the
     * edges of guide. Returns (a, b), already window-averaged.
     */
    fun guidedCoeffs(guide: Plane, src: Plane, r: Int, eps: Float): Pair<Plane, Plane> {
        val n = guide.w * guide.h
        val meanI = box(guide, r)
        val meanP = if (src === guide) meanI else box(src, r)
        val ip = Plane(guide.w, guide.h)
        val ii = Plane(guide.w, guide.h)
        for (i in 0 until n) {
            ip.d[i] = guide.d[i] * src.d[i]
            ii.d[i] = guide.d[i] * guide.d[i]
        }
        val corrIp = box(ip, r)
        val corrIi = box(ii, r)
        val a = Plane(guide.w, guide.h)
        val b = Plane(guide.w, guide.h)
        for (i in 0 until n) {
            val varI = corrIi.d[i] - meanI.d[i] * meanI.d[i]
            val cov = corrIp.d[i] - meanI.d[i] * meanP.d[i]
            val av = cov / (varI + eps)
            a.d[i] = av
            b.d[i] = meanP.d[i] - av * meanI.d[i]
        }
        return Pair(box(a, r), box(b, r))
    }

    fun guided(guide: Plane, src: Plane, r: Int, eps: Float): Plane {
        val (a, b) = guidedCoeffs(guide, src, r, eps)
        val out = Plane(guide.w, guide.h)
        for (i in out.d.indices) out.d[i] = a.d[i] * guide.d[i] + b.d[i]
        return out
    }

    /** Bilinear sample with edge clamping; x, y are pixel-centre coordinates. */
    fun bilinear(p: Plane, x: Float, y: Float): Float {
        val fx = if (x < 0f) 0f else if (x > p.w - 1f) p.w - 1f else x
        val fy = if (y < 0f) 0f else if (y > p.h - 1f) p.h - 1f else y
        val x0 = min(fx.toInt(), max(0, p.w - 2))
        val y0 = min(fy.toInt(), max(0, p.h - 2))
        val x1 = min(p.w - 1, x0 + 1)
        val y1 = min(p.h - 1, y0 + 1)
        val ax = fx - x0
        val ay = fy - y0
        val top = p.d[y0 * p.w + x0] * (1f - ax) + p.d[y0 * p.w + x1] * ax
        val bot = p.d[y1 * p.w + x0] * (1f - ax) + p.d[y1 * p.w + x1] * ax
        return top * (1f - ay) + bot * ay
    }
}
