package com.rj.procam.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How a shot at a given zoom is turned into pixels. */
class Plan(
    val cropX: Int, val cropY: Int, val cropW: Int, val cropH: Int,
    /** Multi-frame super-resolution to a 2x grid. */
    val superRes: Boolean,
    /** 4x neural upscale after the burst pipeline (done by the caller). */
    val ai: Boolean,
    val outW: Int, val outH: Int,
) {
    val pipelineScale: Int get() = if (superRes) 2 else 1
}

class PipelineOutput(
    val argb: IntArray, val w: Int, val h: Int,
    val plan: Plan,
    val framesUsed: Int,
    val noiseSigma: Float,
)

object Pipeline {
    private const val SUPER_RES_MAX_INPUT = 3_300_000

    /**
     * @param zoom remaining digital zoom (>= 1) to realise by cropping the frames
     * @param aiMaxInputPixels largest image the neural upscaler may be given; 0 = no AI
     */
    fun plan(frameW: Int, frameH: Int, frames: Int, zoom: Float, aiMaxInputPixels: Int): Plan {
        val z = max(1f, zoom)
        if (z < 1.05f) return Plan(0, 0, frameW, frameH, false, false, frameW, frameH)
        val cw = max(32, ((frameW / z / 2f).roundToInt() * 2).coerceAtMost(frameW))
        val ch = max(32, ((frameH / z / 2f).roundToInt() * 2).coerceAtMost(frameH))
        val cx = ((frameW - cw) / 4) * 2
        val cy = ((frameH - ch) / 4) * 2
        val px = cw * ch
        val multi = frames >= 3
        val superRes: Boolean
        val ai: Boolean
        when {
            multi && px * 4 <= aiMaxInputPixels -> { superRes = true; ai = true }
            px <= aiMaxInputPixels -> { superRes = false; ai = true }
            multi && px <= SUPER_RES_MAX_INPUT -> { superRes = true; ai = false }
            else -> { superRes = false; ai = false }
        }
        val scale = (if (superRes) 2 else 1) * (if (ai) 4 else 1)
        // Never output more pixels than the full sensor frame.
        val k = min(1f, min(frameW.toFloat() / (cw * scale), frameH.toFloat() / (ch * scale)))
        val outW = max(2, (cw * scale * k).roundToInt())
        val outH = max(2, (ch * scale * k).roundToInt())
        return Plan(cx, cy, cw, ch, superRes, ai, outW, outH)
    }

    /**
     * Burst -> finished ARGB image at plan.pipelineScale times the crop size.
     * The frame list is emptied as soon as the frames are no longer needed, to free memory.
     */
    fun process(
        frames: MutableList<YuvFrame>,
        plan: Plan,
        look: Look,
        progress: (String) -> Unit = {},
    ): PipelineOutput {
        require(frames.isNotEmpty())
        val fw = frames[0].w
        val fh = frames[0].h
        // Crop early (with a margin for alignment) so zoomed shots only process what they need.
        val margin = 64
        val x0 = max(0, plan.cropX - margin)
        val y0 = max(0, plan.cropY - margin)
        val x1 = min(fw, plan.cropX + plan.cropW + margin)
        val y1 = min(fh, plan.cropY + plan.cropH + margin)
        val cropped = x0 != 0 || y0 != 0 || x1 != fw || y1 != fh
        val work = ArrayList<YuvFrame>(frames.size)
        for (f in frames) work.add(if (cropped) f.crop(x0, y0, x1 - x0, y1 - y0) else f)
        frames.clear()

        progress("Aligning")
        val refIdx = Align.sharpest(work)
        val ref = work[refIdx]
        val alts = ArrayList<YuvFrame>()
        for ((i, f) in work.withIndex()) if (i != refIdx) alts.add(f)
        work.clear()
        val refPyr = Align.pyramid(ref)
        val flows = alts.map { Align.align(ref, refPyr, it) }
        val noise = NoiseModel.estimate(flows)
        flows.forEach { noise.scoreTiles(it) }

        progress(if (plan.superRes) "Merging (super-resolution)" else "Merging")
        var merged = Merge.merge(ref, alts, flows, noise)
        val scale = plan.pipelineScale
        if (plan.superRes) merged = Merged(Merge.superRes2x(ref, alts, flows, noise), merged.u, merged.v)
        val used = 1 + alts.size
        alts.clear()

        // Trim the alignment margin.
        val ox = (plan.cropX - x0)
        val oy = (plan.cropY - y0)
        if (cropped) {
            merged = Merged(
                cut(merged.y, ox * scale, oy * scale, plan.cropW * scale, plan.cropH * scale),
                cut(merged.u, ox / 2, oy / 2, plan.cropW / 2, plan.cropH / 2),
                cut(merged.v, ox / 2, oy / 2, plan.cropW / 2, plan.cropH / 2),
            )
        }

        progress("Tone and detail")
        // Noise left after averaging the frames that were actually usable.
        val sigma = noise.sigmaMid / 255f / sqrt(max(1f, used * 0.6f))
        merged = Finish.denoiseChroma(merged, min(1f, noise.sigmaMid / 6f))
        // The super-resolution kernel softens slightly; compensate before tone mapping.
        Finish.sharpen(merged.y, look.sharpen * (if (plan.superRes) 1.5f else 1f), sigma)
        merged = Finish.toneMap(merged, look)
        val argb = Finish.toArgb(merged)
        return PipelineOutput(argb, merged.y.w, merged.y.h, plan, used, noise.sigmaMid)
    }

    private fun cut(p: Plane, x0: Int, y0: Int, w: Int, h: Int): Plane {
        val out = Plane(w, h)
        for (y in 0 until h) System.arraycopy(p.d, (y0 + y) * p.w + x0, out.d, y * w, w)
        return out
    }
}
