package com.rj.procam.imaging

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object Merge {

    /** Interpolates one per-tile array along y for image row position yPos (pixel units). */
    private fun tileRow(src: FloatArray, tw: Int, th: Int, tile: Int, yPos: Float, out: FloatArray) {
        val tf = (yPos + 0.5f) / tile - 0.5f
        val t0 = clampI(floor(tf).toInt(), 0, th - 1)
        val t1 = min(th - 1, t0 + 1)
        val a = max(0f, min(1f, tf - t0))
        val o0 = t0 * tw
        val o1 = t1 * tw
        for (x in 0 until tw) out[x] = src[o0 + x] + (src[o1 + x] - src[o0 + x]) * a
    }

    /**
     * Robust temporal merge at the reference frame's resolution.
     * Every pixel is a weighted mean of the reference and the aligned burst frames; a frame
     * loses its weight where it disagrees with the reference by more than noise explains
     * (motion, occlusion, failed alignment), so moving subjects don't ghost.
     */
    fun merge(ref: YuvFrame, alts: List<YuvFrame>, flows: List<Flow>, noise: NoiseModel): Merged {
        val w = ref.w
        val h = ref.h
        val cw = ref.cw
        val outY = Plane(w, h)
        val outU = Plane(cw, ref.ch)
        val outV = Plane(cw, ref.ch)
        val n = alts.size
        val lut = noise.lut
        val inv255 = 1f / 255f
        Par.rows(h) { r0, r1 ->
            val rdx = Array(n) { FloatArray(flows[it].tw) }
            val rdy = Array(n) { FloatArray(flows[it].tw) }
            val rwt = Array(n) { FloatArray(flows[it].tw) }
            for (y in r0 until r1) {
                for (k in 0 until n) {
                    val f = flows[k]
                    tileRow(f.dx, f.tw, f.th, f.tile, y.toFloat(), rdx[k])
                    tileRow(f.dy, f.tw, f.th, f.tile, y.toFloat(), rdy[k])
                    tileRow(f.weight, f.tw, f.th, f.tile, y.toFloat(), rwt[k])
                }
                val chromaRow = (y and 1) == 0
                val o = y * w
                for (x in 0 until w) {
                    val r = (ref.y[o + x].toInt() and 0xFF).toFloat()
                    val s2 = lut[r.toInt()]
                    var acc = r
                    var ws = 1f
                    val chroma = chromaRow && (x and 1) == 0
                    val ci = (y shr 1) * cw + (x shr 1)
                    var accU = 0f
                    var accV = 0f
                    if (chroma) {
                        accU = (ref.u[ci].toInt() and 0xFF).toFloat()
                        accV = (ref.v[ci].toInt() and 0xFF).toFloat()
                    }
                    for (k in 0 until n) {
                        val f = flows[k]
                        val tf = (x + 0.5f) / f.tile - 0.5f
                        val t0 = clampI(floor(tf).toInt(), 0, f.tw - 1)
                        val t1 = min(f.tw - 1, t0 + 1)
                        val a = max(0f, min(1f, tf - t0))
                        val tWeight = rwt[k][t0] + (rwt[k][t1] - rwt[k][t0]) * a
                        if (tWeight < 0.01f) continue
                        val sx = x + rdx[k][t0] + (rdx[k][t1] - rdx[k][t0]) * a
                        val sy = y + rdy[k][t0] + (rdy[k][t1] - rdy[k][t0]) * a
                        if (sx < 0f || sy < 0f || sx > w - 1.001f || sy > h - 1.001f) continue
                        val alt = alts[k]
                        val v = Align.sampleYCubic(alt, sx, sy)
                        val d = v - r
                        val t = d * d / s2
                        val pw = if (t < 4f) 1f else if (t > 12f) 0f else (12f - t) * 0.125f
                        val wgt = tWeight * pw
                        if (wgt <= 0f) continue
                        acc += wgt * v
                        ws += wgt
                        if (chroma) {
                            val cx = min(sx * 0.5f, cw - 1.001f)
                            val cy = min(sy * 0.5f, alt.ch - 1.001f)
                            val x0 = cx.toInt()
                            val y0 = cy.toInt()
                            val ax = cx - x0
                            val ay = cy - y0
                            val i = y0 * cw + x0
                            val u = bil(alt.u, i, cw, ax, ay)
                            val vv = bil(alt.v, i, cw, ax, ay)
                            accU += wgt * u
                            accV += wgt * vv
                        }
                    }
                    outY.d[o + x] = acc / ws * inv255
                    if (chroma) {
                        outU.d[ci] = accU / ws * inv255
                        outV.d[ci] = accV / ws * inv255
                    }
                }
            }
        }
        return Merged(outY, outU, outV)
    }

    private fun bil(p: ByteArray, i: Int, stride: Int, ax: Float, ay: Float): Float {
        val p00 = p[i].toInt() and 0xFF
        val p10 = p[i + 1].toInt() and 0xFF
        val p01 = p[i + stride].toInt() and 0xFF
        val p11 = p[i + stride + 1].toInt() and 0xFF
        val top = p00 + (p10 - p00) * ax
        val bot = p01 + (p11 - p01) * ax
        return top + (bot - top) * ay
    }

    private val EXP_LUT = FloatArray(257) { exp(-it / 32.0).toFloat() }

    /**
     * Multi-frame super-resolution: builds luma on a 2x grid.
     *
     * Hand shake shifts every burst frame by a different sub-pixel amount, so together the
     * frames sample the scene more densely than any one of them. Each output pixel gathers
     * the real sensor samples (not interpolated values) that landed near it in every frame,
     * weighted by distance, so detail above a single frame's sampling limit is recovered
     * from measurements rather than invented.
     */
    fun superRes2x(ref: YuvFrame, alts: List<YuvFrame>, flows: List<Flow>, noise: NoiseModel): Plane {
        val w = ref.w
        val h = ref.h
        val ow = w * 2
        val oh = h * 2
        val out = Plane(ow, oh)
        val n = alts.size
        val lut = noise.lut
        if (n == 0) {
            Par.rows(oh) { r0, r1 ->
                for (oy in r0 until r1) for (ox in 0 until ow) {
                    out.d[oy * ow + ox] = Align.sampleY(ref, (ox + 0.5f) * 0.5f - 0.5f, (oy + 0.5f) * 0.5f - 0.5f) / 255f
                }
            }
            return out
        }
        val tw = flows[0].tw
        val th = flows[0].th
        val tile = flows[0].tile
        // How many frames can be trusted per tile decides how tight the kernel may be.
        val conf = FloatArray(tw * th) { i -> 1f + flows.sumOf { it.weight[i].toDouble() }.toFloat() }
        Par.rows(oh) { r0, r1 ->
            val rdx = Array(n) { FloatArray(tw) }
            val rdy = Array(n) { FloatArray(tw) }
            val rwt = Array(n) { FloatArray(tw) }
            val rcf = FloatArray(tw)
            for (oy in r0 until r1) {
                val y = (oy + 0.5f) * 0.5f - 0.5f
                for (k in 0 until n) {
                    val f = flows[k]
                    tileRow(f.dx, tw, th, tile, y, rdx[k])
                    tileRow(f.dy, tw, th, tile, y, rdy[k])
                    tileRow(f.weight, tw, th, tile, y, rwt[k])
                }
                tileRow(conf, tw, th, tile, y, rcf)
                val yc = max(0f, min(h - 1.001f, y))
                val y0 = yc.toInt()
                val ay = yc - y0
                for (ox in 0 until ow) {
                    val x = (ox + 0.5f) * 0.5f - 0.5f
                    val xc = max(0f, min(w - 1.001f, x))
                    val x0 = xc.toInt()
                    val ax = xc - x0
                    val i = y0 * w + x0
                    val p00 = (ref.y[i].toInt() and 0xFF).toFloat()
                    val p10 = (ref.y[i + 1].toInt() and 0xFF).toFloat()
                    val p01 = (ref.y[i + w].toInt() and 0xFF).toFloat()
                    val p11 = (ref.y[i + w + 1].toInt() and 0xFF).toFloat()
                    val top = p00 + (p10 - p00) * ax
                    val bot = p01 + (p11 - p01) * ax
                    val rb = top + (bot - top) * ay
                    val gx = (p10 - p00 + p11 - p01) * 0.5f
                    val gy = (p01 - p00 + p11 - p10) * 0.5f
                    val allow = lut[clampI(rb.toInt(), 0, 255)] + gx * gx + gy * gy

                    val tf = (x + 0.5f) / tile - 0.5f
                    val t0 = clampI(floor(tf).toInt(), 0, tw - 1)
                    val t1 = min(tw - 1, t0 + 1)
                    val a = max(0f, min(1f, tf - t0))
                    val c = rcf[t0] + (rcf[t1] - rcf[t0]) * a
                    // Kernel width (input px). Tight (0.36) where there is detail and 4+ trusted
                    // frames to resolve it; wide where few frames agree, and in smooth areas,
                    // where averaging more samples means less noise.
                    val g2 = gx * gx + gy * gy
                    val s2n = lut[clampI(rb.toInt(), 0, 255)]
                    val detail = g2 / (g2 + 6f * s2n)
                    val tight = 0.55f - 0.19f * max(0f, min(1f, (c - 1.5f) / 2.5f))
                    val sigma = 0.85f + (tight - 0.85f) * detail
                    val kScale = 32f / (2f * sigma * sigma)

                    var acc = 0.02f * rb
                    var ws = 0.02f
                    for (k in -1 until n) {
                        var qx = x
                        var qy = y
                        var fw = 1f
                        val src: YuvFrame
                        if (k < 0) {
                            src = ref
                        } else {
                            fw = rwt[k][t0] + (rwt[k][t1] - rwt[k][t0]) * a
                            if (fw < 0.01f) continue
                            qx += rdx[k][t0] + (rdx[k][t1] - rdx[k][t0]) * a
                            qy += rdy[k][t0] + (rdy[k][t1] - rdy[k][t0]) * a
                            src = alts[k]
                        }
                        val qx0 = floor(qx).toInt()
                        val qy0 = floor(qy).toInt()
                        if (qx0 < 0 || qy0 < 0 || qx0 >= w - 1 || qy0 >= h - 1) continue
                        val fx = qx - qx0
                        val fy = qy - qy0
                        val si = qy0 * w + qx0
                        for (j in 0 until 4) {
                            val ddx = if (j and 1 == 0) fx else 1f - fx
                            val ddy = if (j and 2 == 0) fy else 1f - fy
                            val li = ((ddx * ddx + ddy * ddy) * kScale).toInt()
                            if (li >= 256) continue
                            val v = (src.y[si + (j and 1) + (j shr 1) * w].toInt() and 0xFF).toFloat()
                            val d = v - rb
                            val t = d * d / allow
                            val pw = if (t < 4f) 1f else if (t > 12f) 0f else (12f - t) * 0.125f
                            val wgt = EXP_LUT[li] * fw * pw
                            acc += wgt * v
                            ws += wgt
                        }
                    }
                    out.d[oy * ow + ox] = acc / ws * (1f / 255f)
                }
            }
        }
        return out
    }
}
