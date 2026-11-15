package com.rj.procam.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sin

/** Synthetic bursts with known ground truth: sub-pixel shake, sensor noise, a moving object. */
class PipelineTest {
    private val w = 384
    private val h = 288
    private val outDir = File("build/test-out").apply { mkdirs() }

    /** Continuous scene, 0..1. Coordinates in frame pixels. */
    private fun scene(x: Double, y: Double): Double {
        val u = x / w
        val v = y / h
        var s = 0.25 + 0.5 * u // gradient
        // zone plate (frequencies rising to beyond the frame's Nyquist limit)
        val dx = x - w * 0.3
        val dy = y - h * 0.5
        val r2 = dx * dx + dy * dy
        if (r2 < 110.0 * 110.0) s = 0.5 + 0.4 * cos(r2 * 0.014)
        // fine slanted bars
        if (u > 0.62 && u < 0.95 && v > 0.1 && v < 0.45) s = if (floor((x + 0.37 * y) / 1.7).toInt() % 2 == 0) 0.85 else 0.15
        // blocky texture
        if (u > 0.62 && u < 0.95 && v > 0.55 && v < 0.9) {
            val hx = floor(x / 3.0).toInt()
            val hy = floor(y / 3.0).toInt()
            s = 0.2 + 0.6 * (((hx * 73856093) xor (hy * 19349663)) and 0xFF) / 255.0
        }
        return s
    }

    /**
     * What a camera with a sharp lens records: the scene blurred by a small point-spread
     * function (Gaussian, sigma 0.5 frame px) and sampled on a grid `scale` times denser
     * than the frame grid. `sub` is unused resolution of the integration and kept for clarity.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun render(scale: Int, sx: Double, sy: Double, sub: Int): FloatArray {
        val ow = w * scale
        val oh = h * scale
        val out = FloatArray(ow * oh)
        val taps = 13
        val step = 0.25
        val k = DoubleArray(taps) { val d = (it - taps / 2) * step; kotlin.math.exp(-d * d / (2 * 0.5 * 0.5)) }
        val norm = k.sum().let { it * it }
        Par.rows(oh) { r0, r1 ->
            for (j in r0 until r1) for (i in 0 until ow) {
                val cx = (i + 0.5) / scale + sx
                val cy = (j + 0.5) / scale + sy
                var s = 0.0
                for (b in 0 until taps) for (a in 0 until taps) {
                    s += k[a] * k[b] * scene(cx + (a - taps / 2) * step, cy + (b - taps / 2) * step)
                }
                out[j * ow + i] = (s / norm).toFloat()
            }
        }
        return out
    }

    private fun toFrame(img: FloatArray, noise: Double, rnd: Random, obj: Int = -1): YuvFrame {
        val y = ByteArray(w * h)
        for (j in 0 until h) for (i in 0 until w) {
            var v = img[j * w + i].toDouble()
            if (obj >= 0 && i in obj until obj + 28 && j in 200 until 228) v = 0.95
            v += rnd.nextGaussian() * noise
            y[j * w + i] = (v * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
        }
        val u = ByteArray(w * h / 4) { (128 + rnd.nextGaussian() * noise * 255).toInt().coerceIn(0, 255).toByte() }
        val v = ByteArray(w * h / 4) { (128 + rnd.nextGaussian() * noise * 255).toInt().coerceIn(0, 255).toByte() }
        return YuvFrame(w, h, y, u, v)
    }

    private val shifts = arrayOf(
        0.0 to 0.0, 0.37 to -0.61, -1.25 to 0.48, 2.71 to 1.33, -0.52 to -2.18, 1.14 to 0.77, -3.4 to 2.6, 0.81 to -1.45,
    )

    private fun burst(noise: Double, n: Int = shifts.size, moving: Boolean = false): List<YuvFrame> {
        val rnd = Random(7)
        return (0 until n).map { k ->
            toFrame(render(1, shifts[k].first, shifts[k].second, 4), noise, rnd, if (moving) 60 + 30 * k else -1)
        }
    }

    private fun psnr(a: FloatArray, b: FloatArray, aw: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var s = 0.0
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val d = (a[y * aw + x] - b[y * aw + x]).toDouble()
            s += d * d
            n++
        }
        return 10 * log10(1.0 / (s / n + 1e-12))
    }

    private fun save(name: String, d: FloatArray, iw: Int, ih: Int) {
        val img = BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until ih) for (x in 0 until iw) {
            val v = (d[y * iw + x] * 255f + 0.5f).toInt().coerceIn(0, 255)
            img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
        }
        ImageIO.write(img, "png", File(outDir, name))
    }

    private fun lumaOf(f: YuvFrame) = FloatArray(f.w * f.h) { (f.y[it].toInt() and 0xFF) / 255f }

    private class Aligned(val ref: YuvFrame, val alts: List<YuvFrame>, val flows: List<Flow>, val noise: NoiseModel)

    private fun alignAll(frames: List<YuvFrame>): Aligned {
        val ref = frames[0]
        val pyr = Align.pyramid(ref)
        val alts = frames.drop(1)
        val flows = alts.map { Align.align(ref, pyr, it) }
        val noise = NoiseModel.estimate(flows)
        flows.forEach { noise.scoreTiles(it) }
        return Aligned(ref, alts, flows, noise)
    }

    @Test
    fun alignmentRecoversSubPixelShake() {
        val a = alignAll(burst(0.02))
        var worst = 0.0
        for ((k, f) in a.flows.withIndex()) {
            // frame k+1 was rendered at scene offset s, so alt(x - s) == ref(x)
            val ex = -shifts[k + 1].first
            val ey = -shifts[k + 1].second
            var err = 0.0
            var n = 0
            for (ty in 1 until f.th - 1) for (tx in 1 until f.tw - 1) {
                val i = ty * f.tw + tx
                // Only the non-repeating texture pins motion down uniquely: flat tiles carry no
                // information, and the bars / zone plate match equally well one period over.
                val cx = tx * f.tile + f.tile / 2
                val cy = ty * f.tile + f.tile / 2
                if (cx < 0.66 * w || cx > 0.92 * w || cy < 0.6 * h || cy > 0.86 * h) continue
                err += abs(f.dx[i] - ex) + abs(f.dy[i] - ey)
                n++
            }
            val mean = err / (2 * n)
            println("frame ${k + 1}: mean flow error ${"%.3f".format(mean)} px over $n textured tiles")
            worst = maxOf(worst, mean)
        }
        assertTrue("sub-pixel alignment error $worst", worst < 0.15)
    }

    @Test
    fun mergeReducesNoise() {
        val frames = burst(0.04)
        val clean = render(1, 0.0, 0.0, 4)
        val a = alignAll(frames)
        val m = Merge.merge(a.ref, a.alts, a.flows, a.noise)
        // Smooth area (bottom-left) and non-repeating texture (bottom-right).
        val single = psnr(lumaOf(frames[0]), clean, w, 8, 240, 230, 280)
        val merged = psnr(m.y.d, clean, w, 8, 240, 230, 280)
        val singleT = psnr(lumaOf(frames[0]), clean, w, 245, 165, 360, 255)
        val mergedT = psnr(m.y.d, clean, w, 245, 165, 360, 255)
        println("texture PSNR single $singleT dB, merged $mergedT dB")
        assertTrue("texture must not get worse", mergedT > singleT + 1.0)
        println("noise sigma estimate ${a.noise.sigmaMid} (true ${0.04 * 255})")
        println("PSNR single frame $single dB, merged $merged dB")
        save("merge_single.png", lumaOf(frames[0]), w, h)
        save("merge_merged.png", m.y.d, w, h)
        assertTrue("merge gain ${merged - single}", merged > single + 5.0)
        assertEquals(0.04 * 255, a.noise.sigmaMid.toDouble(), 0.04 * 255 * 0.35)
    }

    @Test
    fun movingObjectDoesNotGhost() {
        val frames = burst(0.02, moving = true)
        val a = alignAll(frames)
        val m = Merge.merge(a.ref, a.alts, a.flows, a.noise)
        save("ghost_merged.png", m.y.d, w, h)
        // Where the object is in other frames (but not the reference) the result must match the reference.
        val ref = lumaOf(frames[0])
        var worst = 0f
        for (y in 204 until 224) for (x in 95 until 290) worst = maxOf(worst, abs(m.y.d[y * w + x] - ref[y * w + x]))
        println("largest deviation from reference along the object's path: $worst")
        assertTrue("ghost residue $worst", worst < 0.12f)
    }

    @Test
    fun superResolutionRecoversRealDetail() {
        val frames = burst(0.015)
        val truth = render(2, 0.0, 0.0, 2)
        val a = alignAll(frames)
        val sr = Merge.superRes2x(a.ref, a.alts, a.flows, a.noise)
        val raw = sr.d.copyOf()
        Finish.sharpen(sr, 1.0f, 0.004f)
        // Baseline: the best a single-frame pipeline can do - denoised merge, then interpolation.
        val m = Merge.merge(a.ref, a.alts, a.flows, a.noise)
        val up = FloatArray(4 * w * h)
        for (y in 0 until 2 * h) for (x in 0 until 2 * w) {
            up[y * 2 * w + x] = Filters.bilinear(m.y, (x + 0.5f) / 2 - 0.5f, (y + 0.5f) / 2 - 0.5f)
        }
        save("sr_truth.png", truth, 2 * w, 2 * h)
        save("sr_multiframe.png", sr.d, 2 * w, 2 * h)
        save("sr_interpolated.png", up, 2 * w, 2 * h)
        // Score on the detailed regions (zone plate, bars, texture).
        val pSr = psnr(sr.d, truth, 2 * w, 20, 20, 2 * w - 20, 2 * h - 20)
        val pUp = psnr(up, truth, 2 * w, 20, 20, 2 * w - 20, 2 * h - 20)
        for ((nm, rg) in listOf("texture" to intArrayOf(490, 330, 720, 510), "bars" to intArrayOf(490, 70, 720, 250), "zone" to intArrayOf(40, 100, 420, 480), "flat" to intArrayOf(20, 500, 440, 560))) {
            println("  $nm: sr-raw ${psnr(raw, truth, 2 * w, rg[0], rg[1], rg[2], rg[3])} sr-sharp ${psnr(sr.d, truth, 2 * w, rg[0], rg[1], rg[2], rg[3])} interp ${psnr(up, truth, 2 * w, rg[0], rg[1], rg[2], rg[3])}")
        }
        println("2x PSNR: multi-frame super-res $pSr dB, merge+interpolate $pUp dB")
        assertTrue("super-res gain ${pSr - pUp}", pSr > pUp + 1.5)
    }

    @Test
    fun fullPipelineAndPlans() {
        // 1x
        val p1 = Pipeline.plan(4000, 3000, 6, 1f, 800_000)
        assertTrue(!p1.superRes && !p1.ai && p1.outW == 4000)
        // 2.5x: crop is 1600x1200 -> multi-frame super-res only
        val p2 = Pipeline.plan(4000, 3000, 6, 2.5f, 800_000)
        assertTrue(p2.superRes && !p2.ai)
        assertEquals(3200, p2.outW)
        // 5x: crop 800x600 -> AI 4x, capped at 3200x2400
        val p5 = Pipeline.plan(4000, 3000, 6, 5f, 800_000)
        assertTrue(!p5.superRes && p5.ai)
        assertEquals(3200, p5.outW)
        // 10x: crop 400x300 -> super-res 2x then AI 4x = 8x
        val p10 = Pipeline.plan(4000, 3000, 6, 10f, 800_000)
        assertTrue(p10.superRes && p10.ai)
        assertEquals(3200, p10.outW)
        // No AI available: falls back to super-res
        val p10b = Pipeline.plan(4000, 3000, 6, 10f, 0)
        assertTrue(p10b.superRes && !p10b.ai)

        val frames = burst(0.03).toMutableList()
        val plan = Pipeline.plan(w, h, frames.size, 2f, 0)
        val out = Pipeline.process(frames, plan, Look(underExposureEv = 1f))
        assertTrue(frames.isEmpty())
        assertEquals(plan.cropW * 2, out.w)
        assertEquals(out.w * out.h, out.argb.size)
        val img = BufferedImage(out.w, out.h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, out.w, out.h, out.argb, 0, out.w)
        ImageIO.write(img, "png", File(outDir, "pipeline_2x.png"))
        val resized = Resample.resize(out.argb, out.w, out.h, plan.outW, plan.outH)
        assertEquals(plan.outW * plan.outH, resized.size)
    }

    @Test
    fun toneMappingLiftsShadowsAndKeepsHighlights() {
        // Left half deep shadow with faint texture, right half bright with texture.
        val pw = 320
        val ph = 240
        val y = Plane(pw, ph)
        for (j in 0 until ph) for (i in 0 until pw) {
            val tex = 0.02f * sin(i * 0.9f) * cos(j * 0.7f)
            y.d[j * pw + i] = if (i < pw / 2) 0.06f + tex else 0.80f + tex * 2
        }
        val u = Plane(pw / 2, ph / 2, FloatArray(pw * ph / 4) { 0.55f })
        val v = Plane(pw / 2, ph / 2, FloatArray(pw * ph / 4) { 0.45f })
        val out = Finish.toneMap(Merged(y, u, v), Look(underExposureEv = 1f))
        fun stats(p: Plane, x0: Int, x1: Int): Pair<Float, Float> {
            var s = 0f; var lo = 1f; var hi = 0f; var n = 0
            for (j in 40 until ph - 40) for (i in x0 until x1) {
                val q = p.d[j * pw + i]; s += q; n++; lo = minOf(lo, q); hi = maxOf(hi, q)
            }
            return (s / n) to (hi - lo)
        }
        val (darkIn, darkRangeIn) = stats(y, 30, 130)
        val (darkOut, darkRangeOut) = stats(out.y, 30, 130)
        val (brightIn, _) = stats(y, 190, 290)
        val (brightOut, brightRangeOut) = stats(out.y, 190, 290)
        println("shadows $darkIn -> $darkOut (texture range $darkRangeIn -> $darkRangeOut); highlights $brightIn -> $brightOut")
        assertTrue(darkOut > darkIn * 1.5f)
        assertTrue(darkRangeOut > darkRangeIn * 1.5f)
        assertTrue(brightOut < 0.97f && brightRangeOut > 0.03f) // not clipped
        assertTrue(out.y.d.all { it in 0f..1f && !it.isNaN() })
    }

    @Test
    fun resampleAndBackProjection() {
        val lw = 40
        val lh = 30
        val rnd = Random(3)
        val low = IntArray(lw * lh) { i ->
            val r = (128 + 90 * sin((i % lw) * 0.3) * cos((i / lw) * 0.25)).toInt() + rnd.nextInt(5)
            (0xFF shl 24) or (r shl 16) or (((i % lw) * 6) shl 8) or (i / lw) * 8
        }
        // A "bad" upscale: right structure, wrong brightness.
        val up = Resample.resize(low, lw, lh, lw * 4, lh * 4)
        val skewed = IntArray(up.size) { i ->
            val p = up[i]
            val r = (((p shr 16) and 0xFF) * 0.8f).toInt()
            (0xFF shl 24) or (r shl 16) or (p and 0xFFFF)
        }
        fun redError(img: IntArray): Double {
            var e = 0.0
            for (y in 2 until lh - 2) for (x in 2 until lw - 2) {
                var s = 0
                for (yy in 0 until 4) for (xx in 0 until 4) s += (img[(y * 4 + yy) * lw * 4 + x * 4 + xx] shr 16) and 0xFF
                e += abs(s / 16.0 - ((low[y * lw + x] shr 16) and 0xFF))
            }
            return e / ((lw - 4) * (lh - 4))
        }
        val before = redError(skewed)
        Resample.backProject(low, lw, lh, skewed, 4)
        val after = redError(skewed)
        println("back-projection: mean red error vs capture $before -> $after")
        assertTrue(after < before * 0.25)
        // Flat image stays flat through a resize.
        val flat = IntArray(64 * 48) { 0xFF336699.toInt() }
        assertTrue(Resample.resize(flat, 64, 48, 23, 17).all { it == 0xFF336699.toInt() })
    }
}
