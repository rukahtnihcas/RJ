package com.rj.procam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Live luminance histogram. */
class HistogramView(context: Context) : View(context) {
    private var bins = FloatArray(0)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); style = Paint.Style.FILL }
    private val clip = Paint().apply { color = 0xFFFF5544.toInt(); strokeWidth = 4f }
    private val path = Path()
    private var clippedHigh = false
    private var clippedLow = false

    init {
        setBackgroundColor(0x66000000)
    }

    fun update(hist: IntArray) {
        val peak = (hist.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val total = hist.sum().coerceAtLeast(1)
        bins = FloatArray(hist.size) { (hist[it] / peak).coerceAtMost(1f) }
        clippedHigh = hist.last() > total * 0.02f
        clippedLow = hist.first() > total * 0.10f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = bins.size
        if (n == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        path.reset()
        path.moveTo(0f, h)
        for (i in 0 until n) path.lineTo((i + 0.5f) * w / n, h - bins[i] * (h - 2f))
        path.lineTo(w, h)
        path.close()
        canvas.drawPath(path, fill)
        if (clippedHigh) canvas.drawLine(w - 2f, 0f, w - 2f, h, clip)
        if (clippedLow) canvas.drawLine(2f, 0f, 2f, h, clip)
    }
}

/** Rule-of-thirds grid and the tap-to-focus ring, drawn over the preview. */
class OverlayView(context: Context) : View(context) {
    var showGrid = false
        set(v) {
            field = v
            invalidate()
        }
    private var fx = -1f
    private var fy = -1f
    private val grid = Paint().apply { color = 0x66FFFFFF; strokeWidth = 2f }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC533.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val hide = Runnable {
        fx = -1f
        invalidate()
    }

    fun showFocus(x: Float, y: Float) {
        fx = x
        fy = y
        invalidate()
        removeCallbacks(hide)
        postDelayed(hide, 1500)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (showGrid) {
            canvas.drawLine(w / 3, 0f, w / 3, h, grid)
            canvas.drawLine(2 * w / 3, 0f, 2 * w / 3, h, grid)
            canvas.drawLine(0f, h / 3, w, h / 3, grid)
            canvas.drawLine(0f, 2 * h / 3, w, 2 * h / 3, grid)
        }
        if (fx >= 0f) canvas.drawCircle(fx, fy, 70f, ring)
    }
}

/** The shutter button: a ring with a disc that dims while the camera is busy. */
class ShutterView(context: Context) : View(context) {
    var busy = false
        set(v) {
            field = v
            invalidate()
        }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        ringPaint.strokeWidth = r * 0.10f
        canvas.drawCircle(width / 2f, height / 2f, r * 0.92f, ringPaint)
        disc.color = if (busy) 0xFF666666.toInt() else if (isPressed) 0xFFCCCCCC.toInt() else Color.WHITE
        canvas.drawCircle(width / 2f, height / 2f, r * 0.76f, disc)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
