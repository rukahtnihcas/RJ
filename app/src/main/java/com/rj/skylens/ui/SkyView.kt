package com.rj.skylens.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.rj.skylens.astro.*
import com.rj.skylens.data.*
import com.rj.skylens.sensor.OrientationProvider
import java.util.Locale
import kotlin.math.*

/** Something the user can tap / search for. */
sealed class Selection {
    data class StarSel(val index: Int) : Selection()
    data class BodySel(val body: Body) : Selection()
    data class DsoSel(val index: Int) : Selection()
    data class SatSel(val id: Int, val name: String) : Selection()
    data class PlaneSel(val hex: String, val label: String) : Selection()
}

class Layers(
    var stars: Boolean = true,
    var constellations: Boolean = true,
    var deepSky: Boolean = true,
    var planets: Boolean = true,
    var satellites: Boolean = true,
    var planes: Boolean = true,
    var grid: Boolean = false
)

/**
 * Transparent overlay that projects the sky onto the camera preview using the phone's
 * orientation. All drawing happens in [onDraw]; it re-renders continuously.
 */
class SkyView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    // ---- inputs set by the activity ----------------------------------------------------------
    var catalog: Catalog? = null
        set(v) { field = v; prepareStarArrays() }
    var observer: Observer? = null
    var orientation: OrientationProvider? = null
    /** Magnetic declination (east positive) + user alignment offset, degrees. */
    var declinationDeg = 0.0
    var azimuthOffsetDeg = 0.0
    var cameraMode = true
        set(v) { field = v; invalidate() }
    /** Focal length of the camera preview in pixels (zoom 1x); null if unknown. */
    var cameraFocalPx: Double? = null
    var zoom = 1.0
        private set
    val layers = Layers()
    var flights: FlightRepository? = null
    var selection: Selection? = null
        set(v) { field = v; selectedTrack = emptyList() }
    /** ENU unit vectors of the selected satellite's track, provided by a background task. */
    @Volatile var selectedTrack: List<Vec3> = emptyList()

    /** Satellite positions from the background propagator, with the time they were computed for. */
    @Volatile var satPositions: List<SatPosition> = emptyList()
    @Volatile var satComputedAtMs = 0L

    var onZoomChanged: ((Double) -> Unit)? = null
    var onSelectionChanged: ((Selection?) -> Unit)? = null

    // ---- per-frame state ---------------------------------------------------------------------
    @Volatile var bodies: List<BodyPosition> = emptyList()
        private set
    private var bodiesComputedAt = 0L
    private var moonWaxing = true
    private var lastFrame: SkyFrame? = null
    private var enuToDevice = Mat3.IDENTITY
    private var j2000ToDevice = Mat3.IDENTITY
    private var cx = 0f
    private var cy = 0f
    private var focal = 1000.0
    private val pt = PointF()
    private val pt2 = PointF()

    private var starX = DoubleArray(0)
    private var starY = DoubleArray(0)
    private var starZ = DoubleArray(0)

    private class Hit(var x: Float, var y: Float, var sel: Selection, var weight: Float)
    private val hits = ArrayList<Hit>(512)

    private val dp = resources.displayMetrics.density

    // ---- paints --------------------------------------------------------------------------------
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0E6F0.toInt(); textSize = 12 * dp; setShadowLayer(3 * dp, 0f, 0f, Color.BLACK)
    }
    private val smallLabel = Paint(labelPaint).apply { textSize = 10.5f * dp; color = 0xFFB8C4D8.toInt() }
    private val conLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8A5D8FD6.toInt(); strokeWidth = 1.2f * dp; style = Paint.Style.STROKE
    }
    private val conLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB07FA8E8.toInt(); textSize = 11 * dp; textAlign = Paint.Align.CENTER; letterSpacing = 0.15f
        setShadowLayer(2 * dp, 0f, 0f, Color.BLACK)
    }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC3DDC84.toInt(); strokeWidth = 1.6f * dp; style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF; strokeWidth = 1f * dp; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4 * dp, 6 * dp), 0f)
    }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3DDC84.toInt(); textSize = 16 * dp; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(3 * dp, 0f, 0f, Color.BLACK)
    }
    private val dsoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFF8AD8.toInt(); strokeWidth = 1.3f * dp; style = Paint.Style.STROKE
    }
    private val dsoLabel = Paint(labelPaint).apply { color = 0xFFFFB0E6.toInt(); textSize = 11 * dp }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyLabel = Paint(labelPaint).apply { textSize = 14 * dp; isFakeBoldText = true }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val satLabel = Paint(labelPaint).apply { color = 0xFF7CFFB2.toInt(); textSize = 11 * dp }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * dp; color = 0xAA7CFFB2.toInt()
        pathEffect = DashPathEffect(floatArrayOf(6 * dp, 5 * dp), 0f)
    }
    private val planePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB74D.toInt(); style = Paint.Style.FILL }
    private val planeLabel = Paint(labelPaint).apply { color = 0xFFFFCC80.toInt(); textSize = 11 * dp }
    private val planeTrack = Paint(trackPaint).apply { color = 0xAAFFB74D.toInt() }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFEB3B.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * dp
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFEB3B.toInt(); style = Paint.Style.FILL }
    private val arrowLabel = Paint(labelPaint).apply { color = 0xFFFFEB3B.toInt(); textSize = 13 * dp; textAlign = Paint.Align.CENTER }
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.2f * dp
    }
    private val skyBg = Paint()
    private val moonDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2C33.toInt() }
    private val moonLit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F0E6.toInt() }
    private val path = Path()
    private val path2 = Path()

    // ---- gestures --------------------------------------------------------------------------------
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            setZoom(zoom * d.scaleFactor)
            return true
        }
    })
    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { handleTap(e.x, e.y); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { setZoom(1.0); return true }
    })

    fun setZoom(z: Double) {
        val nz = z.coerceIn(1.0, if (cameraMode) 8.0 else 30.0)
        if (nz != zoom) { zoom = nz; onZoomChanged?.invoke(zoom) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) tapDetector.onTouchEvent(event)
        return true
    }

    private fun prepareStarArrays() {
        val stars = catalog?.stars ?: return
        starX = DoubleArray(stars.size) { stars[it].j2000.x }
        starY = DoubleArray(stars.size) { stars[it].j2000.y }
        starZ = DoubleArray(stars.size) { stars[it].j2000.z }
    }

    // ---- projection ------------------------------------------------------------------------------

    /** Projects a device-frame direction. Returns false when behind the camera. */
    private fun projectDevice(x: Double, y: Double, z: Double, out: PointF): Boolean {
        if (z > -0.02 * sqrt(x * x + y * y + z * z)) return false
        val k = focal / -z
        out.x = (cx + x * k).toFloat()
        out.y = (cy - y * k).toFloat()
        return true
    }

    private fun projectEnu(v: Vec3, out: PointF): Boolean {
        val m = enuToDevice.m
        return projectDevice(
            m[0] * v.x + m[1] * v.y + m[2] * v.z,
            m[3] * v.x + m[4] * v.y + m[5] * v.z,
            m[6] * v.x + m[7] * v.y + m[8] * v.z, out
        )
    }

    private fun projectJ2000(v: Vec3, out: PointF): Boolean {
        val m = j2000ToDevice.m
        return projectDevice(
            m[0] * v.x + m[1] * v.y + m[2] * v.z,
            m[3] * v.x + m[4] * v.y + m[5] * v.z,
            m[6] * v.x + m[7] * v.y + m[8] * v.z, out
        )
    }

    private fun onScreen(p: PointF, margin: Float = 40 * dp) =
        p.x >= -margin && p.y >= -margin && p.x <= width + margin && p.y <= height + margin

    /** Direction the camera is pointing, in true ENU. */
    fun pointing(): AltAz {
        val m = enuToDevice.m // row 3 is device Z expressed in ENU
        return AltAz.fromEnu(Vec3(-m[6], -m[7], -m[8]))
    }

    private fun updateFrame(): SkyFrame? {
        val obs = observer ?: return null
        val now = System.currentTimeMillis()
        val frame = SkyFrame(Time.julianDate(now), obs)
        lastFrame = frame

        val r = FloatArray(9)
        orientation?.let { synchronized(it.matrix) { System.arraycopy(it.matrix, 0, r, 0, 9) } }
        // device <- world(magnetic): transpose of the device->world matrix
        val rt = Mat3(doubleArrayOf(
            r[0].toDouble(), r[3].toDouble(), r[6].toDouble(),
            r[1].toDouble(), r[4].toDouble(), r[7].toDouble(),
            r[2].toDouble(), r[5].toDouble(), r[8].toDouble()
        ))
        // world(magnetic) <- true ENU: rotate azimuths by -(declination + offset)
        val d = (declinationDeg + azimuthOffsetDeg) * DEG
        val dec = Mat3(doubleArrayOf(cos(d), -sin(d), 0.0, sin(d), cos(d), 0.0, 0.0, 0.0, 1.0))
        enuToDevice = rt * dec
        j2000ToDevice = enuToDevice * frame.j2000ToEnu

        cx = width / 2f
        cy = height / 2f
        val base = if (cameraMode) cameraFocalPx ?: defaultFocal() else defaultFocal()
        focal = base * zoom

        if (now - bodiesComputedAt > 500) {
            bodies = SolarSystem.compute(frame)
            moonWaxing = SolarSystem.moonWaxing(frame.jd)
            bodiesComputedAt = now
        }
        return frame
    }

    /** ~65° vertical field of view when no camera information is available. */
    private fun defaultFocal(): Double = max(width, height) / 2.0 / tan(32.5 * DEG)

    // ---- drawing -----------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hits.clear()
        if (!cameraMode) drawSkyBackground(canvas)
        val frame = updateFrame()
        if (frame == null) {
            postInvalidateOnAnimation(); return
        }
        if (layers.grid) drawGrid(canvas)
        drawHorizon(canvas)
        if (layers.constellations) drawConstellations(canvas)
        if (layers.deepSky) drawDeepSky(canvas)
        if (layers.stars) drawStars(canvas)
        if (layers.planets) drawBodies(canvas)
        if (layers.satellites) drawSatellites(canvas, frame)
        if (layers.planes) drawPlanes(canvas, frame)
        drawSelection(canvas)
        drawReticle(canvas)
        postInvalidateOnAnimation()
    }

    private fun drawSkyBackground(canvas: Canvas) {
        if (skyBg.shader == null || skyBg.shader !is LinearGradient) {
            skyBg.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0xFF050814.toInt(), 0xFF0B1630.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), skyBg)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        skyBg.shader = null
    }

    /** Draws a polyline through ENU (or J2000 if [j2000]) points, breaking where it goes behind the camera. */
    private fun drawPolyline(canvas: Canvas, pts: List<Vec3>, paint: Paint, j2000: Boolean = false) {
        path.reset()
        var penDown = false
        val maxJump = max(width, height) * 1.5f
        var lx = 0f; var ly = 0f
        for (v in pts) {
            val ok = if (j2000) projectJ2000(v, pt) else projectEnu(v, pt)
            if (!ok) { penDown = false; continue }
            if (penDown && abs(pt.x - lx) < maxJump && abs(pt.y - ly) < maxJump) path.lineTo(pt.x, pt.y)
            else path.moveTo(pt.x, pt.y)
            penDown = true; lx = pt.x; ly = pt.y
        }
        canvas.drawPath(path, paint)
    }

    private fun circleEnu(altDeg: Double): List<Vec3> =
        (0..120).map { AltAz(altDeg, it * 3.0).toEnu() }

    private fun drawHorizon(canvas: Canvas) {
        drawPolyline(canvas, circleEnu(0.0), horizonPaint)
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        for (i in names.indices) {
            if (projectEnu(AltAz(0.0, i * 45.0).toEnu(), pt) && onScreen(pt, 0f)) {
                cardinalPaint.textSize = (if (i % 2 == 0) 16 else 12) * dp
                canvas.drawText(names[i], pt.x, pt.y - 6 * dp, cardinalPaint)
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        for (alt in listOf(-30.0, 30.0, 60.0)) drawPolyline(canvas, circleEnu(alt), gridPaint)
        for (az in 0 until 360 step 30) {
            drawPolyline(canvas, (-30..90 step 5).map { AltAz(it.toDouble(), az.toDouble()).toEnu() }, gridPaint)
            if (az % 90 != 0 && projectEnu(AltAz(0.0, az.toDouble()).toEnu(), pt) && onScreen(pt, 0f))
                canvas.drawText("$az°", pt.x + 4 * dp, pt.y + 14 * dp, smallLabel)
        }
        for (alt in listOf(30, 60)) {
            val p = pointing()
            if (projectEnu(AltAz(alt.toDouble(), p.az).toEnu(), pt) && onScreen(pt, 0f))
                canvas.drawText("$alt°", pt.x + 4 * dp, pt.y - 4 * dp, smallLabel)
        }
    }

    private fun bvColor(bv: Float): Int = when {
        bv < -0.1f -> 0xFF9BB0FF.toInt()
        bv < 0.15f -> 0xFFC4D2FF.toInt()
        bv < 0.45f -> 0xFFEEF0FF.toInt()
        bv < 0.75f -> 0xFFFFF4E4.toInt()
        bv < 1.1f -> 0xFFFFE0B5.toInt()
        bv < 1.45f -> 0xFFFFCE94.toInt()
        else -> 0xFFFFB46E.toInt()
    }

    private fun magLimit(): Float {
        val base = if (cameraMode) 4.8f else 5.5f
        return (base + 2.2f * log2(zoom.toFloat())).coerceAtMost(6.5f)
    }

    private fun drawStars(canvas: Canvas) {
        val cat = catalog ?: return
        val stars = cat.stars
        val m = j2000ToDevice.m
        val limit = magLimit()
        val labelMag = (1.6 + 1.6 * log2(zoom)).toFloat()
        val bayerMag = if (zoom > 3) (zoom.toFloat() - 1f).coerceAtMost(4.5f) else -10f
        for (i in stars.indices) {
            val s = stars[i]
            if (s.mag > limit) break // catalogue is sorted by magnitude
            val x = starX[i]; val y = starY[i]; val z = starZ[i]
            if (!projectDevice(
                    m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z, pt
                ) || !onScreen(pt, 10 * dp)) continue
            val r = max(0.9f, (limit + 1.2f - s.mag) * 0.62f) * dp
            starPaint.color = bvColor(s.bv)
            if (s.mag < 1.0f) {
                glowPaint.shader = RadialGradient(pt.x, pt.y, r * 3.2f, (starPaint.color and 0x00FFFFFF) or 0x55000000, 0, Shader.TileMode.CLAMP)
                canvas.drawCircle(pt.x, pt.y, r * 3.2f, glowPaint)
                glowPaint.shader = null
            }
            canvas.drawCircle(pt.x, pt.y, r, starPaint)
            if (s.name.isNotEmpty() && ((s.hasProperName && s.mag < labelMag) || s.mag < bayerMag)) {
                canvas.drawText(s.name, pt.x + r + 4 * dp, pt.y + 4 * dp, if (s.hasProperName) labelPaint else smallLabel)
            }
            if (s.mag < 4.5f || zoom > 2) hits += Hit(pt.x, pt.y, Selection.StarSel(i), 1f + max(0f, 3f - s.mag) * 0.2f)
        }
    }

    private fun drawConstellations(canvas: Canvas) {
        val cat = catalog ?: return
        val stars = cat.stars
        for (c in cat.constellations) {
            val seg = c.segments
            var k = 0
            while (k + 1 < seg.size) {
                val a = stars[seg[k]]; val b = stars[seg[k + 1]]
                k += 2
                if (!projectJ2000(a.j2000, pt) || !projectJ2000(b.j2000, pt2)) continue
                if (!onScreen(pt, width.toFloat()) && !onScreen(pt2, width.toFloat())) continue
                // shorten lines a bit so they don't touch the star dots
                val dx = pt2.x - pt.x; val dy = pt2.y - pt.y; val len = hypot(dx, dy)
                if (len < 12 * dp) continue
                val g = 5 * dp / len
                canvas.drawLine(pt.x + dx * g, pt.y + dy * g, pt2.x - dx * g, pt2.y - dy * g, conLine)
            }
            if (projectJ2000(c.center, pt) && onScreen(pt, 0f)) {
                canvas.drawText(c.name.uppercase(Locale.ROOT), pt.x, pt.y, conLabel)
            }
        }
    }

    private fun drawDeepSky(canvas: Canvas) {
        val cat = catalog ?: return
        val showAll = zoom > 2.5
        for ((i, d) in cat.deepSky.withIndex()) {
            if (!d.isMessier && !showAll && d.commonName.isEmpty()) continue
            if (!projectJ2000(d.j2000, pt) || !onScreen(pt)) continue
            val r = max(6 * dp, (d.sizeArcmin / 60.0 * DEG * focal / 2).toFloat()).coerceAtMost(120 * dp)
            when {
                d.type.startsWith("Galaxy") -> canvas.drawOval(pt.x - r, pt.y - r * 0.55f, pt.x + r, pt.y + r * 0.55f, dsoPaint)
                d.type.contains("cluster", true) || d.type.contains("association", true) -> {
                    dsoPaint.pathEffect = DashPathEffect(floatArrayOf(2 * dp, 3 * dp), 0f)
                    canvas.drawCircle(pt.x, pt.y, r, dsoPaint)
                    dsoPaint.pathEffect = null
                }
                else -> canvas.drawRect(pt.x - r * 0.8f, pt.y - r * 0.8f, pt.x + r * 0.8f, pt.y + r * 0.8f, dsoPaint)
            }
            val text = if (d.commonName.isNotEmpty() && (d.isMessier || zoom > 1.8)) d.displayName else d.label
            canvas.drawText(text, pt.x + r + 3 * dp, pt.y - r * 0.3f, dsoLabel)
            hits += Hit(pt.x, pt.y, Selection.DsoSel(i), 1.3f)
        }
    }

    private fun drawBodies(canvas: Canvas) {
        val sun = bodies.firstOrNull { it.body == Body.SUN }
        // draw far-to-near so the Moon covers planets
        for (b in bodies.sortedByDescending { it.distanceKm }) {
            if (!projectEnu(b.enu, pt) || !onScreen(pt)) continue
            val below = b.enu.z < 0
            val alpha = if (below) 110 else 255
            val apparent = (b.angularRadiusDeg * DEG * focal).toFloat()
            when (b.body) {
                Body.SUN -> {
                    val r = max(apparent, 16 * dp)
                    glowPaint.shader = RadialGradient(pt.x, pt.y, r * 3, 0x88FFD54F.toInt(), 0, Shader.TileMode.CLAMP)
                    glowPaint.alpha = alpha
                    canvas.drawCircle(pt.x, pt.y, r * 3, glowPaint)
                    glowPaint.shader = null; glowPaint.alpha = 255
                    bodyPaint.color = b.body.color; bodyPaint.alpha = alpha
                    canvas.drawCircle(pt.x, pt.y, r, bodyPaint)
                    drawBodyLabel(canvas, b.body.label, r, alpha)
                }
                Body.MOON -> {
                    val r = max(apparent, 15 * dp)
                    drawMoon(canvas, pt.x, pt.y, r, b, sun, alpha)
                    drawBodyLabel(canvas, "Moon", r, alpha)
                }
                else -> {
                    val r = max(apparent, ((2.5 - b.magnitude) * 1.3 + 4).toFloat().coerceIn(3.5f, 9f) * dp)
                    bodyPaint.color = b.body.color; bodyPaint.alpha = alpha
                    glowPaint.shader = RadialGradient(pt.x, pt.y, r * 2.4f, (b.body.color and 0x00FFFFFF) or 0x66000000, 0, Shader.TileMode.CLAMP)
                    canvas.drawCircle(pt.x, pt.y, r * 2.4f, glowPaint)
                    glowPaint.shader = null
                    canvas.drawCircle(pt.x, pt.y, r, bodyPaint)
                    if (b.body == Body.SATURN) {
                        bodyPaint.style = Paint.Style.STROKE; bodyPaint.strokeWidth = 1.5f * dp
                        canvas.drawOval(pt.x - r * 2f, pt.y - r * 0.6f, pt.x + r * 2f, pt.y + r * 0.6f, bodyPaint)
                        bodyPaint.style = Paint.Style.FILL
                    }
                    drawBodyLabel(canvas, b.body.label, r, alpha)
                }
            }
            hits += Hit(pt.x, pt.y, Selection.BodySel(b.body), 2.5f)
        }
    }

    private fun drawBodyLabel(canvas: Canvas, text: String, r: Float, alpha: Int) {
        bodyLabel.alpha = alpha
        canvas.drawText(text, pt.x + r + 5 * dp, pt.y - r * 0.4f, bodyLabel)
        bodyLabel.alpha = 255
    }

    /** Moon disk with the terminator drawn from the illuminated fraction and the Sun's direction. */
    private fun drawMoon(canvas: Canvas, x: Float, y: Float, r: Float, moon: BodyPosition, sun: BodyPosition?, alpha: Int) {
        moonDark.alpha = alpha; moonLit.alpha = alpha
        canvas.drawCircle(x, y, r, moonDark)
        // direction towards the Sun on screen: project a point slightly displaced from the Moon towards the Sun
        var angle = 0.0
        if (sun != null) {
            val toward = (moon.enu + (sun.enu - moon.enu * moon.enu.dot(sun.enu)).unit() * 0.01).unit()
            if (projectEnu(toward, pt2)) angle = atan2((pt2.y - y).toDouble(), (pt2.x - x).toDouble())
        }
        val k = moon.illuminated.toFloat()
        canvas.save()
        canvas.rotate((angle * RAD).toFloat(), x, y)
        // lit half towards +x
        path.reset()
        path.addArc(RectF(x - r, y - r, x + r, y + r), -90f, 180f)
        val w = abs(2 * k - 1) * r
        path2.reset()
        path2.addOval(RectF(x - w, y - r, x + w, y + r), Path.Direction.CW)
        if (k >= 0.5f) {
            // gibbous: half disk plus half ellipse on the dark side
            path.close()
            val left = Path().apply { addRect(x - r, y - r, x, y + r, Path.Direction.CW) }
            path2.op(left, Path.Op.INTERSECT)
            path.op(path2, Path.Op.UNION)
        } else {
            path.close()
            path.op(path2, Path.Op.DIFFERENCE)
        }
        canvas.drawPath(path, moonLit)
        canvas.restore()
        moonDark.alpha = 255; moonLit.alpha = 255
    }

    private fun interpolatedSatEnu(s: SatPosition, now: Long): Vec3 {
        val t = ((now - satComputedAtMs) / 1000.0).coerceIn(0.0, 3.0)
        return (s.look.enu + (s.enuNext - s.look.enu) * t).unit()
    }

    private fun drawSatellites(canvas: Canvas, frame: SkyFrame) {
        val now = System.currentTimeMillis()
        val list = satPositions
        val selId = (selection as? Selection.SatSel)?.id
        if (selId != null) drawPolyline(canvas, selectedTrack, trackPaint)
        val labelAll = list.count { it.look.enu.z > 0 } < 40 || zoom > 2.5
        for (s in list) {
            val enu = interpolatedSatEnu(s, now)
            if (enu.z < -0.02) continue
            if (!projectEnu(enu, pt) || !onScreen(pt)) continue
            val lit = s.look.sunlit
            satPaint.color = when {
                s.isStation -> 0xFF4FC3F7.toInt()
                lit -> 0xFF7CFFB2.toInt()
                else -> 0xFF5F7D70.toInt()
            }
            val r = (if (s.isStation) 5.5f else 3.2f) * dp
            path.reset()
            path.moveTo(pt.x, pt.y - r); path.lineTo(pt.x + r, pt.y); path.lineTo(pt.x, pt.y + r); path.lineTo(pt.x - r, pt.y); path.close()
            canvas.drawPath(path, satPaint)
            if (s.isStation || labelAll || s.id == selId) {
                satLabel.color = satPaint.color
                canvas.drawText(s.name, pt.x + r + 4 * dp, pt.y + 4 * dp, satLabel)
            }
            hits += Hit(pt.x, pt.y, Selection.SatSel(s.id, s.name), 2f)
        }
    }

    private fun drawPlanes(canvas: Canvas, frame: SkyFrame) {
        val repo = flights ?: return
        val obs = frame.observer
        val now = System.currentTimeMillis()
        val selHex = (selection as? Selection.PlaneSel)?.hex
        for (a in repo.aircraft) {
            val enuKm = a.enuKm(obs, now)
            val dist = enuKm.norm()
            val enu = enuKm * (1.0 / dist)
            if (enu.z < -0.01) continue
            if (a.hex == selHex) {
                drawPolyline(canvas, (0..18).map { a.enuKm(obs, now + it * 10_000L).unit() }, planeTrack)
            }
            if (!projectEnu(enu, pt) || !onScreen(pt)) continue
            val ahead = a.enuKm(obs, now + 5000).unit()
            var heading = -PI / 2
            if (projectEnu(ahead, pt2)) {
                val dx = pt2.x - pt.x; val dy = pt2.y - pt.y
                if (hypot(dx, dy) > 0.5f) heading = atan2(dy.toDouble(), dx.toDouble())
            }
            drawPlaneIcon(canvas, pt.x, pt.y, heading.toFloat(), (if (dist < 30) 11f else 8f) * dp)
            val alt = String.format(Locale.US, "%,d ft", (a.altM / 0.3048).roundToInt())
            canvas.drawText("${a.label}  $alt", pt.x + 12 * dp, pt.y - 6 * dp, planeLabel)
            if (a.type.isNotEmpty()) canvas.drawText("${a.type} · ${dist.roundToInt()} km", pt.x + 12 * dp, pt.y + 8 * dp, smallLabel)
            hits += Hit(pt.x, pt.y, Selection.PlaneSel(a.hex, a.label), 2.2f)
        }
    }

    private fun drawPlaneIcon(canvas: Canvas, x: Float, y: Float, angle: Float, s: Float) {
        canvas.save()
        canvas.rotate(angle * RAD.toFloat(), x, y)
        path.reset()
        // simple airliner silhouette pointing along +x
        path.moveTo(x + s, y)
        path.lineTo(x + s * 0.2f, y - s * 0.12f)
        path.lineTo(x - s * 0.1f, y - s * 0.9f)
        path.lineTo(x - s * 0.3f, y - s * 0.9f)
        path.lineTo(x - s * 0.15f, y - s * 0.12f)
        path.lineTo(x - s * 0.75f, y - s * 0.1f)
        path.lineTo(x - s * 0.95f, y - s * 0.4f)
        path.lineTo(x - s * 1.05f, y - s * 0.4f)
        path.lineTo(x - s * 0.95f, y)
        path.lineTo(x - s * 1.05f, y + s * 0.4f)
        path.lineTo(x - s * 0.95f, y + s * 0.4f)
        path.lineTo(x - s * 0.75f, y + s * 0.1f)
        path.lineTo(x - s * 0.15f, y + s * 0.12f)
        path.lineTo(x - s * 0.3f, y + s * 0.9f)
        path.lineTo(x - s * 0.1f, y + s * 0.9f)
        path.lineTo(x + s * 0.2f, y + s * 0.12f)
        path.close()
        canvas.drawPath(path, planePaint)
        canvas.restore()
    }

    private fun drawReticle(canvas: Canvas) {
        val r = 18 * dp
        canvas.drawCircle(cx, cy, r, reticlePaint)
        canvas.drawLine(cx - r - 8 * dp, cy, cx - r + 6 * dp, cy, reticlePaint)
        canvas.drawLine(cx + r - 6 * dp, cy, cx + r + 8 * dp, cy, reticlePaint)
        canvas.drawLine(cx, cy - r - 8 * dp, cx, cy - r + 6 * dp, reticlePaint)
        canvas.drawLine(cx, cy + r - 6 * dp, cx, cy + r + 8 * dp, reticlePaint)
    }

    /** Highlights the selected object, or draws an arrow towards it when it is out of view. */
    private fun drawSelection(canvas: Canvas) {
        val sel = selection ?: return
        val enu = enuOf(sel) ?: return
        val m = enuToDevice.m
        val dx = m[0] * enu.x + m[1] * enu.y + m[2] * enu.z
        val dy = m[3] * enu.x + m[4] * enu.y + m[5] * enu.z
        val dz = m[6] * enu.x + m[7] * enu.y + m[8] * enu.z
        if (projectDevice(dx, dy, dz, pt) && pt.x in 0f..width.toFloat() && pt.y in 0f..height.toFloat()) {
            val pulse = (sin(System.currentTimeMillis() / 250.0) * 3 * dp).toFloat()
            canvas.drawCircle(pt.x, pt.y, 22 * dp + pulse, selPaint)
            return
        }
        // off-screen: arrow at the edge pointing the way to turn
        var ax = dx; var ay = -dy
        val len = hypot(ax, ay)
        if (len < 1e-6) { ax = 0.0; ay = 1.0 } else { ax /= len; ay /= len }
        val margin = 48 * dp
        val hw = cx - margin; val hh = cy - margin
        val t = min(if (ax != 0.0) hw / abs(ax) else Double.MAX_VALUE, if (ay != 0.0) hh / abs(ay) else Double.MAX_VALUE)
        val px = (cx + ax * t).toFloat(); val py = (cy + ay * t).toFloat()
        val ang = atan2(ay, ax).toFloat()
        canvas.save()
        canvas.rotate(ang * RAD.toFloat(), px, py)
        path.reset()
        path.moveTo(px + 22 * dp, py); path.lineTo(px - 10 * dp, py - 14 * dp); path.lineTo(px - 2 * dp, py); path.lineTo(px - 10 * dp, py + 14 * dp); path.close()
        canvas.drawPath(path, arrowPaint)
        canvas.restore()
        val name = selectionName(sel)
        val ty = if (py > cy) py - 24 * dp else py + 36 * dp
        canvas.drawText(name, px.coerceIn(70 * dp, width - 70 * dp), ty, arrowLabel)
    }

    // ---- selection helpers ------------------------------------------------------------------------------

    private fun handleTap(x: Float, y: Float) {
        var best: Hit? = null
        var bestScore = Float.MAX_VALUE
        val radius = 36 * dp
        for (h in hits) {
            val d = hypot(h.x - x, h.y - y)
            if (d > radius) continue
            val score = d / h.weight
            if (score < bestScore) { bestScore = score; best = h }
        }
        selection = best?.sel
        onSelectionChanged?.invoke(selection)
    }

    fun enuOf(sel: Selection): Vec3? {
        val frame = lastFrame ?: return null
        return when (sel) {
            is Selection.StarSel -> catalog?.stars?.getOrNull(sel.index)?.let { frame.j2000ToEnu * it.j2000 }
            is Selection.DsoSel -> catalog?.deepSky?.getOrNull(sel.index)?.let { frame.j2000ToEnu * it.j2000 }
            is Selection.BodySel -> bodies.firstOrNull { it.body == sel.body }?.enu
            is Selection.SatSel -> satPositions.firstOrNull { it.id == sel.id }?.let { interpolatedSatEnu(it, System.currentTimeMillis()) }
            is Selection.PlaneSel -> flights?.aircraft?.firstOrNull { it.hex == sel.hex }?.enuKm(frame.observer, System.currentTimeMillis())?.unit()
        }
    }

    fun selectionName(sel: Selection): String = when (sel) {
        is Selection.StarSel -> catalog?.stars?.getOrNull(sel.index)?.name?.ifEmpty { "Star" } ?: "Star"
        is Selection.DsoSel -> catalog?.deepSky?.getOrNull(sel.index)?.displayName ?: "Deep-sky object"
        is Selection.BodySel -> sel.body.label
        is Selection.SatSel -> sel.name
        is Selection.PlaneSel -> sel.label
    }

    /** Multi-line description of the current selection, or null. */
    fun describeSelection(): String? {
        val sel = selection ?: return null
        val enu = enuOf(sel) ?: return "${selectionName(sel)}\nNot currently tracked"
        val aa = AltAz.fromEnu(enu)
        val pos = String.format(Locale.US, "Alt %.1f°  Az %.1f° (%s)%s", aa.alt, aa.az, compass(aa.az),
            if (aa.alt < 0) "  · below horizon" else "")
        val sb = StringBuilder()
        when (sel) {
            is Selection.StarSel -> {
                val s = catalog!!.stars[sel.index]
                sb.append(s.name.ifEmpty { "HIP ${s.hip}" }).append('\n')
                sb.append(String.format(Locale.US, "Star · magnitude %.2f%s\n", s.mag, if (s.hip != 0) " · HIP ${s.hip}" else ""))
                sb.append(String.format(Locale.US, "RA %s  Dec %+.2f°\n", hms(s.raHours), s.decDeg))
            }
            is Selection.DsoSel -> {
                val d = catalog!!.deepSky[sel.index]
                sb.append(d.displayName).append('\n')
                sb.append(d.type)
                if (d.mag > 0) sb.append(String.format(Locale.US, " · mag %.1f", d.mag))
                if (d.sizeArcmin > 0) sb.append(String.format(Locale.US, " · %.0f′", d.sizeArcmin))
                if (d.label != d.id) sb.append(" · ").append(d.id)
                sb.append('\n')
            }
            is Selection.BodySel -> {
                val b = bodies.first { it.body == sel.body }
                sb.append(sel.body.label).append('\n')
                when (sel.body) {
                    Body.MOON -> sb.append(String.format(Locale.US, "%s · %.0f%% lit · %,.0f km\n",
                        SolarSystem.moonPhaseName(b.illuminated, moonWaxing), b.illuminated * 100, b.distanceKm))
                    Body.SUN -> sb.append(String.format(Locale.US, "Star · %.4f AU · never look directly at the Sun\n", b.distanceKm / SolarSystem.AU_KM))
                    else -> sb.append(String.format(Locale.US, "Planet · mag %.1f · %.2f AU from Earth · %.0f%% lit\n",
                        b.magnitude, b.distanceKm / SolarSystem.AU_KM, b.illuminated * 100))
                }
            }
            is Selection.SatSel -> {
                val s = satPositions.firstOrNull { it.id == sel.id }
                sb.append(sel.name).append('\n')
                if (s != null) sb.append(String.format(Locale.US, "Satellite #%d · %,.0f km up · %,.0f km away · %.2f km/s · %s\n",
                    s.id, s.look.altitudeKm, s.look.rangeKm, s.look.speedKmS, if (s.look.sunlit) "sunlit" else "in Earth's shadow"))
            }
            is Selection.PlaneSel -> {
                val a = flights?.aircraft?.firstOrNull { it.hex == sel.hex }
                sb.append(sel.label).append('\n')
                if (a != null) {
                    val km = a.enuKm(lastFrame!!.observer, System.currentTimeMillis()).norm()
                    sb.append(listOf(a.type, a.registration).filter { it.isNotBlank() }.joinToString(" · ").let { if (it.isEmpty()) "" else "$it · " })
                    sb.append(String.format(Locale.US, "%,d ft · %.0f kt · heading %.0f° · %.1f km away",
                        (a.altM / 0.3048).roundToInt(), a.groundSpeedMs / 0.514444, a.trackDeg, km))
                    if (abs(a.verticalRateMs) > 1) sb.append(String.format(Locale.US, " · %s %,d ft/min",
                        if (a.verticalRateMs > 0) "climbing" else "descending", abs(a.verticalRateMs / 0.3048 * 60).roundToInt()))
                    sb.append('\n')
                }
            }
        }
        sb.append(pos)
        return sb.toString()
    }

    /** Things the user can search for, grouped and roughly ordered by interest. */
    fun searchables(): List<Pair<String, Selection>> {
        val out = ArrayList<Pair<String, Selection>>()
        fun altOf(v: Vec3?) = v?.let { AltAz.fromEnu(it).alt } ?: -90.0
        fun tag(v: Vec3?) = if (altOf(v) >= 0) "" else "  (below horizon)"
        for (b in bodies) out += "☉ ${b.body.label}${tag(b.enu)}" to Selection.BodySel(b.body)
        satPositions.filter { it.look.enu.z > 0 }.sortedByDescending { it.isStation }.take(60)
            .forEach { out += "🛰 ${it.name}" to Selection.SatSel(it.id, it.name) }
        flights?.aircraft?.let { list ->
            val obs = lastFrame?.observer
            if (obs != null) list.map { it to it.enuKm(obs, System.currentTimeMillis()) }
                .filter { it.second.z > 0 }.sortedBy { it.second.norm() }
                .forEach { (a, v) -> out += "✈ ${a.label} · ${v.norm().roundToInt()} km" to Selection.PlaneSel(a.hex, a.label) }
        }
        catalog?.let { cat ->
            cat.stars.withIndex().filter { it.value.hasProperName && it.value.mag < 2.6f }
                .forEach { out += "★ ${it.value.name}${tag(lastFrame?.let { f -> f.j2000ToEnu * it.value.j2000 })}" to Selection.StarSel(it.index) }
            cat.deepSky.withIndex().filter { it.value.isMessier }
                .sortedBy { it.value.label.drop(1).toIntOrNull() ?: 999 }
                .forEach { out += "◎ ${it.value.displayName}" to Selection.DsoSel(it.index) }
        }
        return out
    }

    /** Azimuth correction (degrees) that would put the selected object under the reticle. */
    fun alignmentCorrection(): Double? {
        val sel = selection ?: return null
        val target = AltAz.fromEnu(enuOf(sel) ?: return null)
        val now = pointing()
        var d = target.az - now.az
        while (d > 180) d -= 360
        while (d < -180) d += 360
        // computed true azimuth = magnetic azimuth + (declination + offset), so add d to the offset
        return d
    }

    companion object {
        fun compass(az: Double): String {
            val names = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
            return names[((az / 22.5) + 0.5).toInt() % 16]
        }

        fun hms(hours: Double): String {
            val h = floor(hours).toInt()
            val m = ((hours - h) * 60).toInt()
            val s = ((hours - h) * 3600 - m * 60).roundToInt().coerceAtMost(59)
            return String.format(Locale.US, "%02dh%02dm%02ds", h, m, s)
        }
    }
}
