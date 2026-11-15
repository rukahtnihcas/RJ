package com.rj.procam.imaging

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Runs row-range work on all cores. */
object Par {
    val threads: Int = max(1, Runtime.getRuntime().availableProcessors())
    private val pool = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "procam-imaging").apply { isDaemon = true }
    }

    /** Splits [0, n) into chunks and calls body(start, endExclusive) for each, in parallel. */
    fun rows(n: Int, body: (Int, Int) -> Unit) {
        if (n <= 0) return
        val chunks = min(n, threads * 4)
        if (chunks <= 1 || threads == 1) {
            body(0, n)
            return
        }
        val tasks = ArrayList<Callable<Unit>>(chunks)
        for (c in 0 until chunks) {
            val a = (n.toLong() * c / chunks).toInt()
            val b = (n.toLong() * (c + 1) / chunks).toInt()
            if (b > a) tasks.add(Callable { body(a, b) })
        }
        // get() rethrows anything a worker threw.
        for (f in pool.invokeAll(tasks)) f.get()
    }
}

/** A single-channel float image, values nominally 0..1. */
class Plane(val w: Int, val h: Int, val d: FloatArray = FloatArray(w * h)) {
    init {
        require(d.size == w * h)
    }
}

/**
 * One 8-bit YUV 4:2:0 frame with tightly packed planes.
 * w and h are even; u and v are (w/2) x (h/2).
 */
class YuvFrame(val w: Int, val h: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray) {
    val cw: Int get() = w / 2
    val ch: Int get() = h / 2

    init {
        require(w % 2 == 0 && h % 2 == 0) { "frame size must be even: ${w}x$h" }
        require(y.size == w * h && u.size == cw * ch && v.size == cw * ch)
    }

    /** Copies out the rectangle at (x0, y0) of size cropW x cropH; all four must be even. */
    fun crop(x0: Int, y0: Int, cropW: Int, cropH: Int): YuvFrame {
        require(x0 % 2 == 0 && y0 % 2 == 0 && cropW % 2 == 0 && cropH % 2 == 0)
        require(x0 >= 0 && y0 >= 0 && x0 + cropW <= w && y0 + cropH <= h)
        val ny = ByteArray(cropW * cropH)
        for (r in 0 until cropH) System.arraycopy(y, (y0 + r) * w + x0, ny, r * cropW, cropW)
        val ncw = cropW / 2
        val nch = cropH / 2
        val nu = ByteArray(ncw * nch)
        val nv = ByteArray(ncw * nch)
        for (r in 0 until nch) {
            System.arraycopy(u, (y0 / 2 + r) * cw + x0 / 2, nu, r * ncw, ncw)
            System.arraycopy(v, (y0 / 2 + r) * cw + x0 / 2, nv, r * ncw, ncw)
        }
        return YuvFrame(cropW, cropH, ny, nu, nv)
    }
}

/** Result of merging a burst: luma may be at a higher resolution than chroma. */
class Merged(val y: Plane, val u: Plane, val v: Plane)

internal fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v
internal fun clampI(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
