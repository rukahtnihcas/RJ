package com.rj.procam.imaging

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Per-tile motion of one burst frame relative to the reference frame:
 * alt(x + dx, y + dy) shows the same scene point as ref(x, y).
 * Vectors live at tile centres and are interpolated bilinearly in between.
 */
class Flow(val tile: Int, val tw: Int, val th: Int) {
    val dx = FloatArray(tw * th)
    val dy = FloatArray(tw * th)

    /** Mean squared luma difference (8-bit units) left after alignment. */
    val resid = FloatArray(tw * th)

    /** Mean reference luma of the tile (8-bit units). */
    val mean = FloatArray(tw * th)

    /** Mean squared gradient of the reference tile. */
    val grad = FloatArray(tw * th)

    /** 0..1 confidence that the tile shows the same content as the reference. */
    val weight = FloatArray(tw * th)
}

object Align {
    const val TILE = 32
    private const val HALF_TILE = 16 // tile size on every pyramid level
    private const val COARSE_RADIUS = 4

    /** Luma pyramid starting at half resolution, each level half the previous. */
    internal fun pyramid(f: YuvFrame): List<Plane> {
        val levels = ArrayList<Plane>()
        val w = f.w / 2
        val h = f.h / 2
        val l0 = Plane(w, h)
        Par.rows(h) { r0, r1 ->
            for (y in r0 until r1) {
                val o0 = 2 * y * f.w
                val o1 = o0 + f.w
                for (x in 0 until w) {
                    val s = (f.y[o0 + 2 * x].toInt() and 0xFF) + (f.y[o0 + 2 * x + 1].toInt() and 0xFF) +
                        (f.y[o1 + 2 * x].toInt() and 0xFF) + (f.y[o1 + 2 * x + 1].toInt() and 0xFF)
                    l0.d[y * w + x] = s * 0.25f
                }
            }
        }
        levels.add(l0)
        while (levels.size < 4 && min(levels.last().w, levels.last().h) >= 4 * HALF_TILE) {
            levels.add(Filters.downsample(levels.last(), 2))
        }
        return levels
    }

    private fun sad(ref: Plane, alt: Plane, x0: Int, y0: Int, n: Int, ox: Int, oy: Int): Float {
        var s = 0f
        val x1 = min(ref.w, x0 + n)
        val y1 = min(ref.h, y0 + n)
        for (y in y0 until y1) {
            val ay = clampI(y + oy, 0, alt.h - 1) * alt.w
            val ry = y * ref.w
            for (x in x0 until x1) {
                val ax = clampI(x + ox, 0, alt.w - 1)
                s += abs(ref.d[ry + x] - alt.d[ay + ax])
            }
        }
        return s
    }

    /**
     * Coarse-to-fine block matching on the pyramids, then Lucas-Kanade sub-pixel
     * refinement on the full-resolution luma.
     */
    fun align(ref: YuvFrame, refPyr: List<Plane>, alt: YuvFrame): Flow {
        val altPyr = pyramid(alt)
        val nLevels = min(refPyr.size, altPyr.size)
        var prevDx = IntArray(0)
        var prevDy = IntArray(0)
        var prevTw = 0
        var prevTh = 0
        for (lv in nLevels - 1 downTo 0) {
            val r = refPyr[lv]
            val a = altPyr[lv]
            val tw = (r.w + HALF_TILE - 1) / HALF_TILE
            val th = (r.h + HALF_TILE - 1) / HALF_TILE
            val ndx = IntArray(tw * th)
            val ndy = IntArray(tw * th)
            val coarse = lv == nLevels - 1
            val pdx = prevDx
            val pdy = prevDy
            val ptw = prevTw
            val pth = prevTh
            Par.rows(th) { t0, t1 ->
                for (ty in t0 until t1) for (tx in 0 until tw) {
                    var bx = 0
                    var by = 0
                    if (!coarse) {
                        val px = min(ptw - 1, tx / 2)
                        val py = min(pth - 1, ty / 2)
                        bx = pdx[py * ptw + px] * 2
                        by = pdy[py * ptw + px] * 2
                    }
                    val rad = if (coarse) COARSE_RADIUS else 1
                    var best = Float.MAX_VALUE
                    var bestX = bx
                    var bestY = by
                    for (oy in -rad..rad) for (ox in -rad..rad) {
                        // Tiny bias towards the predicted vector so flat tiles don't wander.
                        val c = sad(r, a, tx * HALF_TILE, ty * HALF_TILE, HALF_TILE, bx + ox, by + oy) +
                            0.5f * (abs(ox) + abs(oy))
                        if (c < best) {
                            best = c
                            bestX = bx + ox
                            bestY = by + oy
                        }
                    }
                    ndx[ty * tw + tx] = bestX
                    ndy[ty * tw + tx] = bestY
                }
            }
            // Hand shake moves the whole frame together. A tile that prefers some other vector
            // without a clearly better match (flat areas, repeating patterns) takes the
            // frame-wide median instead; genuinely moving subjects keep their own vector.
            val mx = median(ndx)
            val my = median(ndy)
            Par.rows(th) { t0, t1 ->
                for (ty in t0 until t1) for (tx in 0 until tw) {
                    val i = ty * tw + tx
                    if (ndx[i] == mx && ndy[i] == my) continue
                    val own = sad(r, a, tx * HALF_TILE, ty * HALF_TILE, HALF_TILE, ndx[i], ndy[i])
                    val med = sad(r, a, tx * HALF_TILE, ty * HALF_TILE, HALF_TILE, mx, my)
                    if (med <= own * 1.2f + HALF_TILE * HALF_TILE * 0.5f) {
                        ndx[i] = mx
                        ndy[i] = my
                    }
                }
            }
            prevDx = ndx
            prevDy = ndy
            prevTw = tw
            prevTh = th
        }

        // Level 0 is half resolution with 16 px tiles, i.e. 32 px tiles at full resolution.
        val flow = Flow(TILE, prevTw, prevTh)
        val fdx = prevDx
        val fdy = prevDy
        val nT = flow.tw * flow.th
        Par.rows(flow.th) { t0, t1 ->
            val o = FloatArray(6)
            for (ty in t0 until t1) for (tx in 0 until flow.tw) {
                val ti = ty * flow.tw + tx
                refine(ref, alt, tx, ty, fdx[ti] * 2f, fdy[ti] * 2f, 2f, o)
                flow.dx[ti] = o[0]
                flow.dy[ti] = o[1]
                flow.resid[ti] = o[2]
                flow.mean[ti] = o[3]
                flow.grad[ti] = o[4]
                flow.weight[ti] = o[5] // 1 = tile had pixels
            }
        }

        // Camera shake is a smooth motion of the whole frame (shift + slight rotation/zoom).
        // Fit that model robustly to the textured tiles, then let each tile adopt it unless
        // the tile's own vector explains the image clearly better (a subject that really
        // moved). This fixes tiles that locked onto the wrong repeat of a fine pattern, where
        // the image alone cannot tell the right sub-pixel offset.
        val model = fitAffine(flow) ?: return flow
        Par.rows(flow.th) { t0, t1 ->
            val o = FloatArray(6)
            for (ty in t0 until t1) for (tx in 0 until flow.tw) {
                val ti = ty * flow.tw + tx
                if (flow.weight[ti] == 0f) continue
                val cx = tx * TILE + TILE * 0.5f
                val cy = ty * TILE + TILE * 0.5f
                val mx = model[0] + model[1] * cx + model[2] * cy
                val my = model[3] + model[4] * cx + model[5] * cy
                val ddx = flow.dx[ti] - mx
                val ddy = flow.dy[ti] - my
                if (ddx * ddx + ddy * ddy < 0.15f * 0.15f) continue
                refine(ref, alt, tx, ty, mx, my, 0.5f, o)
                if (o[2] <= 2f * flow.resid[ti] + 2f) {
                    flow.dx[ti] = o[0]
                    flow.dy[ti] = o[1]
                    flow.resid[ti] = o[2]
                }
            }
        }
        check(nT == flow.dx.size)
        return flow
    }

    /**
     * Lucas-Kanade refinement of one tile's vector on full-resolution luma, starting at
     * (startX, startY) and staying within maxDev of it.
     * out = [dx, dy, residual, mean luma, mean squared gradient, valid]
     */
    private fun refine(
        ref: YuvFrame, alt: YuvFrame, tx: Int, ty: Int,
        startX: Float, startY: Float, maxDev: Float, out: FloatArray,
    ) {
        val w = ref.w
        val h = ref.h
        var vx = startX
        var vy = startY
        val xa = max(1, tx * TILE)
        val xb = min(w - 1, (tx + 1) * TILE)
        val ya = max(1, ty * TILE)
        val yb = min(h - 1, (ty + 1) * TILE)
        var sxx = 0f
        var sxy = 0f
        var syy = 0f
        var sum = 0f
        var cnt = 0
        // Structure tensor of the reference tile (every other pixel is plenty).
        var y = ya
        while (y < yb) {
            var x = xa
            while (x < xb) {
                val i = y * w + x
                val gx = ((ref.y[i + 1].toInt() and 0xFF) - (ref.y[i - 1].toInt() and 0xFF)) * 0.5f
                val gy = ((ref.y[i + w].toInt() and 0xFF) - (ref.y[i - w].toInt() and 0xFF)) * 0.5f
                sxx += gx * gx
                sxy += gx * gy
                syy += gy * gy
                sum += (ref.y[i].toInt() and 0xFF)
                cnt++
                x += 2
            }
            y += 2
        }
        out[0] = vx
        out[1] = vy
        out[2] = 0f
        out[3] = 0f
        out[4] = 0f
        out[5] = 0f
        if (cnt == 0) return
        val lam = 40f * cnt // regulariser: flat tiles keep their starting vector
        val det = (sxx + lam) * (syy + lam) - sxy * sxy
        for (iter in 0 until 4) {
            var bx = 0f
            var by = 0f
            y = ya
            while (y < yb) {
                var x = xa
                while (x < xb) {
                    val i = y * w + x
                    val gx = ((ref.y[i + 1].toInt() and 0xFF) - (ref.y[i - 1].toInt() and 0xFF)) * 0.5f
                    val gy = ((ref.y[i + w].toInt() and 0xFF) - (ref.y[i - w].toInt() and 0xFF)) * 0.5f
                    val e = sampleYCubic(alt, x + vx, y + vy) - (ref.y[i].toInt() and 0xFF)
                    bx += gx * e
                    by += gy * e
                    x += 2
                }
                y += 2
            }
            var ux = -((syy + lam) * bx - sxy * by) / det
            var uy = -((sxx + lam) * by - sxy * bx) / det
            ux = max(-1f, min(1f, ux))
            uy = max(-1f, min(1f, uy))
            vx = max(startX - maxDev, min(startX + maxDev, vx + ux))
            vy = max(startY - maxDev, min(startY + maxDev, vy + uy))
            if (abs(ux) < 0.01f && abs(uy) < 0.01f) break
        }
        var res = 0f
        y = ya
        while (y < yb) {
            var x = xa
            while (x < xb) {
                val e = sampleYCubic(alt, x + vx, y + vy) - (ref.y[y * w + x].toInt() and 0xFF)
                res += e * e
                x += 2
            }
            y += 2
        }
        out[0] = vx
        out[1] = vy
        out[2] = res / cnt
        out[3] = sum / cnt
        out[4] = (sxx + syy) / cnt
        out[5] = 1f
    }

    /**
     * Robust least-squares fit of dx = m0 + m1 x + m2 y, dy = m3 + m4 x + m5 y to the
     * vectors of textured tiles. Returns null when there is too little texture to trust.
     */
    private fun fitAffine(f: Flow): FloatArray? {
        val idx = ArrayList<Int>()
        val grads = f.grad.filterIndexed { i, _ -> f.weight[i] > 0f }.sorted()
        if (grads.size < 12) return null
        // "Textured" = clearly above the flattest tiles, which hold only noise.
        val thresh = max(60f, 4f * grads[grads.size / 10])
        for (i in f.grad.indices) if (f.weight[i] > 0f && f.grad[i] > thresh) idx.add(i)
        if (idx.size < 8) return null
        val dxs = idx.map { f.dx[it] }.sorted()
        val dys = idx.map { f.dy[it] }.sorted()
        val m = doubleArrayOf(dxs[dxs.size / 2].toDouble(), 0.0, 0.0, dys[dys.size / 2].toDouble(), 0.0, 0.0)
        val scale = 0.4 // px; tiles further than this from the model count for little
        for (iter in 0 until 6) {
            val a = Array(3) { DoubleArray(3) }
            val bx = DoubleArray(3)
            val by = DoubleArray(3)
            var wsum = 0.0
            for (i in idx) {
                val cx = (i % f.tw) * TILE + TILE * 0.5
                val cy = (i / f.tw) * TILE + TILE * 0.5
                val ex = f.dx[i] - (m[0] + m[1] * cx + m[2] * cy)
                val ey = f.dy[i] - (m[3] + m[4] * cx + m[5] * cy)
                val wgt = 1.0 / (1.0 + (ex * ex + ey * ey) / (scale * scale)).let { it * it }
                val v = doubleArrayOf(1.0, cx, cy)
                for (r in 0 until 3) {
                    for (c in 0 until 3) a[r][c] += wgt * v[r] * v[c]
                    bx[r] += wgt * v[r] * f.dx[i]
                    by[r] += wgt * v[r] * f.dy[i]
                }
                wsum += wgt
            }
            if (wsum < 4.0) return null
            val sx = solve3(a, bx) ?: return null
            val sy = solve3(a, by) ?: return null
            m[0] = sx[0]; m[1] = sx[1]; m[2] = sx[2]
            m[3] = sy[0]; m[4] = sy[1]; m[5] = sy[2]
        }
        return FloatArray(6) { m[it].toFloat() }
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Array(3) { r -> doubleArrayOf(a[r][0], a[r][1], a[r][2], b[r]) }
        for (c in 0 until 3) {
            var p = c
            for (r in c + 1 until 3) if (abs(m[r][c]) > abs(m[p][c])) p = r
            if (abs(m[p][c]) < 1e-9) return null
            val t = m[c]; m[c] = m[p]; m[p] = t
            for (r in 0 until 3) {
                if (r == c) continue
                val k = m[r][c] / m[c][c]
                for (j in c until 4) m[r][j] -= k * m[c][j]
            }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }

    private fun median(v: IntArray): Int {
        val c = v.copyOf()
        c.sort()
        return c[c.size / 2]
    }

    /** Bilinear luma sample in 8-bit units, clamped at the borders. */
    internal fun sampleY(f: YuvFrame, x: Float, y: Float): Float {
        val fx = if (x < 0f) 0f else if (x > f.w - 1.001f) f.w - 1.001f else x
        val fy = if (y < 0f) 0f else if (y > f.h - 1.001f) f.h - 1.001f else y
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val ax = fx - x0
        val ay = fy - y0
        val i = y0 * f.w + x0
        val p00 = f.y[i].toInt() and 0xFF
        val p10 = f.y[i + 1].toInt() and 0xFF
        val p01 = f.y[i + f.w].toInt() and 0xFF
        val p11 = f.y[i + f.w + 1].toInt() and 0xFF
        val top = p00 + (p10 - p00) * ax
        val bot = p01 + (p11 - p01) * ax
        return top + (bot - top) * ay
    }

    /**
     * Catmull-Rom luma sample in 8-bit units. Unlike bilinear it barely softens fine
     * texture, which matters because every merged frame is resampled at a sub-pixel offset.
     */
    internal fun sampleYCubic(f: YuvFrame, x: Float, y: Float): Float {
        val x0 = x.toInt()
        val y0 = y.toInt()
        if (x < 1f || y < 1f || x0 >= f.w - 2 || y0 >= f.h - 2) return sampleY(f, x, y)
        val tx = x - x0
        val ty = y - y0
        val wx0 = ((-0.5f * tx + 1f) * tx - 0.5f) * tx
        val wx1 = (1.5f * tx - 2.5f) * tx * tx + 1f
        val wx2 = ((-1.5f * tx + 2f) * tx + 0.5f) * tx
        val wx3 = (0.5f * tx - 0.5f) * tx * tx
        val wy0 = ((-0.5f * ty + 1f) * ty - 0.5f) * ty
        val wy1 = (1.5f * ty - 2.5f) * ty * ty + 1f
        val wy2 = ((-1.5f * ty + 2f) * ty + 0.5f) * ty
        val wy3 = (0.5f * ty - 0.5f) * ty * ty
        var i = (y0 - 1) * f.w + x0 - 1
        val r0 = wx0 * (f.y[i].toInt() and 0xFF) + wx1 * (f.y[i + 1].toInt() and 0xFF) +
            wx2 * (f.y[i + 2].toInt() and 0xFF) + wx3 * (f.y[i + 3].toInt() and 0xFF)
        i += f.w
        val r1 = wx0 * (f.y[i].toInt() and 0xFF) + wx1 * (f.y[i + 1].toInt() and 0xFF) +
            wx2 * (f.y[i + 2].toInt() and 0xFF) + wx3 * (f.y[i + 3].toInt() and 0xFF)
        i += f.w
        val r2 = wx0 * (f.y[i].toInt() and 0xFF) + wx1 * (f.y[i + 1].toInt() and 0xFF) +
            wx2 * (f.y[i + 2].toInt() and 0xFF) + wx3 * (f.y[i + 3].toInt() and 0xFF)
        i += f.w
        val r3 = wx0 * (f.y[i].toInt() and 0xFF) + wx1 * (f.y[i + 1].toInt() and 0xFF) +
            wx2 * (f.y[i + 2].toInt() and 0xFF) + wx3 * (f.y[i + 3].toInt() and 0xFF)
        val v = wy0 * r0 + wy1 * r1 + wy2 * r2 + wy3 * r3
        return if (v < 0f) 0f else if (v > 255f) 255f else v
    }

    /** Index of the sharpest frame (largest mean absolute Laplacian on a sparse grid). */
    fun sharpest(frames: List<YuvFrame>): Int {
        var best = 0
        var bestScore = -1.0
        for ((k, f) in frames.withIndex()) {
            var s = 0.0
            var n = 0
            var y = 2
            while (y < f.h - 2) {
                var x = 2
                val o = y * f.w
                while (x < f.w - 2) {
                    val c = f.y[o + x].toInt() and 0xFF
                    val lap = 4 * c - (f.y[o + x - 1].toInt() and 0xFF) - (f.y[o + x + 1].toInt() and 0xFF) -
                        (f.y[o + x - f.w].toInt() and 0xFF) - (f.y[o + x + f.w].toInt() and 0xFF)
                    s += abs(lap)
                    n++
                    x += 3
                }
                y += 3
            }
            val score = if (n > 0) s / n else 0.0
            if (score > bestScore) {
                bestScore = score
                best = k
            }
        }
        return best
    }
}

/**
 * Expected squared luma difference between two aligned frames of static content,
 * as a function of brightness. Estimated from the burst itself.
 */
class NoiseModel(val lut: FloatArray) {
    /** Approximate single-frame noise sigma (8-bit units) at mid grey. */
    val sigmaMid: Float get() = kotlin.math.sqrt(lut[110] * 0.5f)

    companion object {
        private const val BINS = 8
        private const val FLOOR = 0.6f

        fun estimate(flows: List<Flow>): NoiseModel {
            val all = ArrayList<Float>()
            val perBin = Array(BINS) { ArrayList<Float>() }
            for (f in flows) for (i in f.resid.indices) {
                if (f.resid[i] <= 0f && f.mean[i] <= 0f) continue
                // Remove the part of the residual that slight misalignment of texture explains.
                val r = max(0f, f.resid[i] - 0.02f * f.grad[i])
                all.add(r)
                perBin[clampI((f.mean[i] * BINS / 256f).toInt(), 0, BINS - 1)].add(r)
            }
            // Static, well-aligned tiles sit at the low end of the distribution.
            fun pick(l: ArrayList<Float>): Float {
                l.sort()
                return l[(l.size * 0.35f).toInt()]
            }
            val global = if (all.isEmpty()) FLOOR else max(FLOOR, pick(all))
            val binVal = FloatArray(BINS) { b -> if (perBin[b].size >= 24) max(FLOOR, pick(perBin[b])) else global }
            val lut = FloatArray(256)
            for (v in 0 until 256) {
                val p = (v + 0.5f) * BINS / 256f - 0.5f
                val b0 = clampI(floor(p).toInt(), 0, BINS - 1)
                val b1 = min(BINS - 1, b0 + 1)
                val a = max(0f, min(1f, p - b0))
                lut[v] = binVal[b0] * (1f - a) + binVal[b1] * a
            }
            return NoiseModel(lut)
        }
    }

    /** Fills in Flow.weight: 1 where the tile matches the reference, 0 where it clearly doesn't. */
    fun scoreTiles(f: Flow) {
        for (i in f.weight.indices) {
            val expected = lut[clampI(f.mean[i].toInt(), 0, 255)] + 0.1f * f.grad[i]
            val ratio = f.resid[i] / expected
            f.weight[i] = max(0f, min(1f, (4f - ratio) / 2f))
        }
    }
}
