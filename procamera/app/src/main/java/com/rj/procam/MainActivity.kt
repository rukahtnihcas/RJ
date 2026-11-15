package com.rj.procam

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.rj.procam.imaging.Pipeline
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : Activity(), CameraController.Listener, PhotoProcessor.Callback {

    private lateinit var camera: CameraController
    private lateinit var ai: AiUpscaler
    private lateinit var processor: PhotoProcessor

    private lateinit var texture: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var histogram: HistogramView
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var zoomBar: SeekBar
    private lateinit var zoomChips: LinearLayout
    private lateinit var proPanel: LinearLayout
    private lateinit var proBar: SeekBar
    private lateinit var proAuto: TextView
    private lateinit var shutter: ShutterView
    private lateinit var thumb: ImageView
    private lateinit var summary: TextView

    private val modeChips = HashMap<ShotMode, TextView>()
    private val paramChips = HashMap<Param, TextView>()
    private lateinit var hdrChip: TextView
    private lateinit var aiChip: TextView
    private lateinit var gridChip: TextView
    private lateinit var formatChip: TextView

    private enum class Param { ISO, SHUTTER, FOCUS, WB, EV }

    private var mode = ShotMode.PHOTO
    private var hdr = true
    private var aiZoom = true
    private var png = false
    private var param = Param.ISO
    private var info: CamInfo? = null
    private var aiReady = false
    private var processing = false
    private var lastUri: Uri? = null
    private var deviceRotation = 0
    private var meterIso = 0
    private var meterExposure = 0L
    private var settingBars = false

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var orientation: OrientationEventListener

    private val histogramTick = object : Runnable {
        override fun run() {
            updateHistogram()
            ui.postDelayed(this, 300)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        camera = CameraController(this, this)
        ai = AiUpscaler(applicationContext)
        processor = PhotoProcessor(applicationContext, ai)
        setContentView(buildUi())
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                deviceRotation = ((o + 45) / 90 * 90) % 360
            }
        }
        processor.warmUp { ok ->
            aiReady = ok
            if (!ok) {
                aiZoom = false
                refreshChips()
            }
            updateZoomLabel()
        }
        refreshChips()
    }

    override fun onResume() {
        super.onResume()
        orientation.enable()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            startCamera()
        }
        ui.post(histogramTick)
    }

    override fun onPause() {
        super.onPause()
        orientation.disable()
        ui.removeCallbacks(histogramTick)
        val midBurst = camera.busy
        camera.close()
        if (midBurst) {
            // The burst died with the camera; a shot already being processed carries on.
            processing = false
            shutter.busy = false
            showProgress(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        processor.shutdown()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            status.text = "Camera permission is needed to take photos."
        }
    }

    private fun startCamera() {
        val st = texture.surfaceTexture
        if (texture.isAvailable && st != null) camera.open(st)
        // otherwise the SurfaceTextureListener opens it once the surface exists
    }

    // ------------------------------------------------------------------ UI construction

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun chip(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setColor(0x55000000)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(3f), 0, dp(3f), 0)
        }
    }

    private fun setChip(c: TextView, on: Boolean) {
        (c.background as GradientDrawable).setColor(if (on) ACCENT else 0x55000000)
        c.setTextColor(if (on) Color.BLACK else Color.WHITE)
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.fitsSystemWindows = true

        val screenW = resources.displayMetrics.widthPixels
        val previewH = screenW * 4 / 3
        val topH = dp(52f)

        // --- preview
        val previewBox = FrameLayout(this)
        texture = TextureView(this)
        previewBox.addView(texture, FrameLayout.LayoutParams(screenW, previewH))
        overlay = OverlayView(this)
        previewBox.addView(overlay, FrameLayout.LayoutParams(screenW, previewH))
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        previewBox.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        progress = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(0xAA000000.toInt()) }
            visibility = View.GONE
        }
        previewBox.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(previewBox, FrameLayout.LayoutParams(screenW, previewH).apply { topMargin = topH })

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.open(s)
            }

            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                camera.close()
                return true
            }

            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(camera.settings.zoom * d.scaleFactor)
                return true
            }
        })
        var moved = false
        overlay.setOnTouchListener { v, e ->
            pinch.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> moved = false
                MotionEvent.ACTION_POINTER_DOWN -> moved = true
                MotionEvent.ACTION_UP -> if (!moved && e.eventTime - e.downTime < 300) {
                    camera.focusAt(e.x / v.width, e.y / v.height)
                    overlay.showFocus(e.x, e.y)
                    v.performClick()
                }
            }
            true
        }

        // --- top bar
        val top = row().apply { setPadding(dp(8f), 0, dp(8f), 0) }
        hdrChip = chip("HDR") { hdr = !hdr; refreshChips() }
        aiChip = chip("AI ZOOM") {
            if (aiReady) {
                aiZoom = !aiZoom
                refreshChips()
                updateZoomLabel()
            } else toast("The AI model could not be loaded on this device")
        }
        gridChip = chip("GRID") { overlay.showGrid = !overlay.showGrid; refreshChips() }
        formatChip = chip("JPG") { png = !png; refreshChips() }
        top.addView(hdrChip)
        top.addView(aiChip)
        top.addView(gridChip)
        top.addView(formatChip)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        histogram = HistogramView(this)
        top.addView(histogram, LinearLayout.LayoutParams(dp(84f), dp(36f)))
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topH, Gravity.TOP))

        // --- bottom controls (overlap the preview on short screens)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(0, dp(6f), 0, dp(10f))
        }

        zoomChips = row()
        bottom.addView(zoomChips)
        val zoomRow = row().apply { setPadding(dp(12f), dp(4f), dp(12f), 0) }
        zoomBar = SeekBar(this).apply { max = 1000 }
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val i = info ?: return
                if (fromUser) setZoom(logLerp(i.zoomMin, i.zoomMax, p / 1000f), fromBar = true)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        zoomRow.addView(zoomBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(zoomRow)
        zoomLabel = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFFDDDDDD.toInt())
        }
        bottom.addView(zoomLabel)

        // pro panel
        proPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6f), 0, 0)
            visibility = View.GONE
        }
        val params = row()
        for (p in Param.values()) {
            val c = chip(p.name) {
                param = p
                refreshChips()
                syncProBar()
            }
            paramChips[p] = c
            params.addView(c)
        }
        proPanel.addView(params)
        val proRow = row().apply { setPadding(dp(12f), dp(4f), dp(12f), 0) }
        proAuto = chip("AUTO") { setParamAuto() }
        proBar = SeekBar(this).apply { max = 1000 }
        proBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser && !settingBars) setParamFromBar(p / 1000f)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        proRow.addView(proAuto)
        proRow.addView(proBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        proPanel.addView(proRow)
        bottom.addView(proPanel)

        // modes
        val modes = row().apply { setPadding(0, dp(8f), 0, dp(6f)) }
        for (m in ShotMode.values()) {
            val c = chip(m.name) { setMode(m) }
            modeChips[m] = c
            modes.addView(c)
        }
        bottom.addView(modes)

        // shutter row
        val shoot = FrameLayout(this)
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF222222.toInt())
            setOnClickListener { openLast() }
        }
        shoot.addView(thumb, FrameLayout.LayoutParams(dp(52f), dp(52f), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(24f) })
        shutter = ShutterView(this).apply {
            isClickable = true
            setOnClickListener { takePhoto() }
        }
        shoot.addView(shutter, FrameLayout.LayoutParams(dp(72f), dp(72f), Gravity.CENTER))
        summary = TextView(this).apply {
            textSize = 10f
            setTextColor(0xFFBBBBBB.toInt())
            gravity = Gravity.END
        }
        shoot.addView(summary, FrameLayout.LayoutParams(dp(120f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(12f) })
        bottom.addView(shoot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80f)))

        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        return root
    }

    private fun refreshChips() {
        setChip(hdrChip, hdr)
        setChip(aiChip, aiZoom && aiReady)
        setChip(gridChip, overlay.showGrid)
        formatChip.text = if (png) "PNG" else "JPG"
        setChip(formatChip, png)
        for ((m, c) in modeChips) setChip(c, m == mode)
        val s = camera.settings
        val i = info
        paramChips[Param.ISO]?.text = "ISO " + (s.iso?.toString() ?: "A")
        paramChips[Param.SHUTTER]?.text = "S " + (s.shutterNs?.let { shutterText(it) } ?: "A")
        paramChips[Param.FOCUS]?.text = "F " + (s.focusDiopters?.let { if (it < 0.05f) "∞" else "%.2fm".format(1f / it) } ?: "AF")
        paramChips[Param.WB]?.text = "WB " + awbName(s.awbMode)
        paramChips[Param.EV]?.text = "EV " + (if (i != null) "%+.1f".format(s.evSteps * i.evStep) else "0")
        for ((p, c) in paramChips) setChip(c, p == param)
    }

    private fun setMode(m: ShotMode) {
        mode = m
        proPanel.visibility = if (m == ShotMode.PRO) View.VISIBLE else View.GONE
        if (m != ShotMode.PRO) {
            // Leaving PRO hands exposure, focus and white balance back to the camera.
            val s = camera.settings
            s.iso = null
            s.shutterNs = null
            s.focusDiopters = null
            s.awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
            s.evSteps = 0
            camera.updatePreview()
        } else {
            syncProBar()
        }
        refreshChips()
    }

    // ------------------------------------------------------------------ pro controls

    private fun logLerp(a: Float, b: Float, t: Float): Float = exp(ln(a) + (ln(b) - ln(a)) * t)
    private fun logPos(a: Float, b: Float, v: Float): Float = if (b <= a) 0f else ((ln(v) - ln(a)) / (ln(b) - ln(a))).coerceIn(0f, 1f)

    private fun shutterRange(i: CamInfo): Pair<Float, Float> {
        val lo = max(i.shutterRangeNs.first, 125_000L).toFloat() // 1/8000 s
        val hi = i.shutterRangeNs.last.coerceAtMost(1_000_000_000L).toFloat().coerceAtLeast(lo * 2)
        return lo to hi
    }

    private fun setParamFromBar(t: Float) {
        val i = info ?: return
        val s = camera.settings
        when (param) {
            Param.ISO -> if (i.manualSensor) s.iso = logLerp(i.isoRange.first.toFloat(), i.isoRange.last.toFloat(), t).roundToInt()
            Param.SHUTTER -> if (i.manualSensor) {
                val (lo, hi) = shutterRange(i)
                s.shutterNs = logLerp(lo, hi, t).roundToLong()
            }
            Param.FOCUS -> if (i.minFocusDiopters > 0f) s.focusDiopters = i.minFocusDiopters * (1f - t) * (1f - t)
            Param.WB -> {
                val modes = awbChoices(i)
                s.awbMode = modes[(t * (modes.size - 1)).roundToInt().coerceIn(0, modes.size - 1)]
            }
            Param.EV -> s.evSteps = (i.evRange.first + t * (i.evRange.last - i.evRange.first)).roundToInt()
        }
        camera.updatePreview()
        refreshChips()
    }

    private fun setParamAuto() {
        val s = camera.settings
        when (param) {
            Param.ISO -> s.iso = null
            Param.SHUTTER -> s.shutterNs = null
            Param.FOCUS -> s.focusDiopters = null
            Param.WB -> s.awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
            Param.EV -> s.evSteps = 0
        }
        camera.updatePreview()
        refreshChips()
        syncProBar()
    }

    /** Moves the slider to the current value of the selected parameter. */
    private fun syncProBar() {
        val i = info ?: return
        val s = camera.settings
        val t = when (param) {
            Param.ISO -> logPos(i.isoRange.first.toFloat(), i.isoRange.last.toFloat(), (s.iso ?: meterIso.coerceAtLeast(i.isoRange.first)).toFloat())
            Param.SHUTTER -> {
                val (lo, hi) = shutterRange(i)
                logPos(lo, hi, (s.shutterNs ?: meterExposure).toFloat().coerceAtLeast(lo))
            }
            Param.FOCUS -> if (i.minFocusDiopters > 0f) 1f - kotlin.math.sqrt(((s.focusDiopters ?: 0f) / i.minFocusDiopters).coerceIn(0f, 1f)) else 0f
            Param.WB -> {
                val modes = awbChoices(i)
                if (modes.size > 1) modes.indexOf(s.awbMode).coerceAtLeast(0) / (modes.size - 1f) else 0f
            }
            Param.EV -> if (i.evRange.last > i.evRange.first) (s.evSteps - i.evRange.first).toFloat() / (i.evRange.last - i.evRange.first) else 0.5f
        }
        settingBars = true
        proBar.progress = (t * 1000).roundToInt()
        settingBars = false
        val supported = when (param) {
            Param.ISO, Param.SHUTTER -> i.manualSensor
            Param.FOCUS -> i.minFocusDiopters > 0f
            else -> true
        }
        proBar.isEnabled = supported
    }

    private fun awbChoices(i: CamInfo): List<Int> = listOf(
        CameraMetadata.CONTROL_AWB_MODE_AUTO,
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        CameraMetadata.CONTROL_AWB_MODE_SHADE,
    ).filter { it == CameraMetadata.CONTROL_AWB_MODE_AUTO || it in i.awbModes }

    private fun awbName(m: Int): String = when (m) {
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "Tungsten"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "Fluor."
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "Sun"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Cloud"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "Shade"
        else -> "A"
    }

    private fun shutterText(ns: Long): String {
        if (ns <= 0) return "-"
        val s = ns / 1e9
        return if (s >= 0.4) "%.1fs".format(s) else "1/" + (1.0 / s).roundToInt()
    }

    // ------------------------------------------------------------------ zoom

    private fun setZoom(z: Float, fromBar: Boolean = false) {
        val i = info ?: return
        val v = z.coerceIn(i.zoomMin, i.zoomMax)
        camera.settings.zoom = v
        camera.updatePreview()
        if (!fromBar) zoomBar.progress = (logPos(i.zoomMin, i.zoomMax, v) * 1000).roundToInt()
        updateZoomLabel()
    }

    /** Says, for the current zoom, where the pixels of the photo will come from. */
    private fun updateZoomLabel() {
        val i = info ?: return
        val z = camera.settings.zoom
        val anchor = if (z > 1f) i.opticalAnchors.filter { it <= z + 0.01f }.maxOrNull() ?: 1f else z
        val residual = max(1f, z / anchor)
        val aiMax = if (aiZoom && aiReady) ai.maxInputPixels else 0
        val plan = Pipeline.plan(i.captureSize.width, i.captureSize.height, 6, residual, aiMax)
        val how = when {
            plan.superRes && plan.ai -> "multi-frame super-res + AI detail"
            plan.ai -> "AI detail"
            plan.superRes -> "multi-frame super-res"
            residual >= 1.05f -> "sensor crop"
            else -> "optical"
        }
        zoomLabel.text = "%.1fx · %s · %dx%d".format(z, how, plan.outW, plan.outH)
    }

    private fun buildZoomChips(i: CamInfo) {
        zoomChips.removeAllViews()
        val stops = ArrayList<Float>()
        if (i.zoomMin < 0.95f) stops.add(i.zoomMin)
        stops.addAll(i.opticalAnchors)
        for (s in listOf(2f, 5f, 10f, 30f, i.zoomMax)) {
            if (s <= i.zoomMax + 0.01f && stops.none { kotlin.math.abs(it / s - 1f) < 0.25f }) stops.add(s)
        }
        stops.sort()
        for (s in stops) {
            val label = if (s < 1f) "%.1fx".format(s) else if (s == s.roundToInt().toFloat()) "${s.roundToInt()}x" else "%.1fx".format(s)
            zoomChips.addView(chip(label) { setZoom(s) })
        }
    }

    // ------------------------------------------------------------------ shooting

    private fun takePhoto() {
        val i = info ?: return
        if (processing || camera.busy || !camera.isOpen) return
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // Each frame is held in memory until merged; leave room for the pipeline's own buffers.
        val frameBytes = i.captureSize.width.toLong() * i.captureSize.height * 3 / 2
        val budget = (am.largeMemoryClass - 150L) * 1024 * 1024
        val maxFrames = (budget / frameBytes).toInt().coerceIn(3, 12)
        val rotation = (i.sensorOrientation + deviceRotation) % 360
        processing = true
        shutter.busy = true
        showProgress("Hold steady…")
        camera.capture(mode, hdr, maxFrames, rotation)
    }

    private fun showProgress(text: String?) {
        if (text == null) {
            progress.visibility = View.GONE
        } else {
            progress.text = text
            progress.visibility = View.VISIBLE
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0) takePhoto()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openLast() {
        val uri = lastUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast("No app found to open the photo")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun updateHistogram() {
        if (!texture.isAvailable || !camera.isOpen) return
        val bmp: Bitmap = try {
            texture.getBitmap(48, 64)
        } catch (e: Exception) {
            null
        } ?: return
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()
        val hist = IntArray(48)
        for (p in px) {
            val l = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            hist[l * 48 / 256]++
        }
        histogram.update(hist)
    }

    // ------------------------------------------------------------------ camera callbacks

    override fun onCameraReady(info: CamInfo) {
        this.info = info
        buildZoomChips(info)
        setZoom(camera.settings.zoom.coerceIn(info.zoomMin, info.zoomMax))
        refreshChips()
        syncProBar()
    }

    override fun onMetering(iso: Int, exposureNs: Long, focusDiopters: Float) {
        meterIso = iso
        meterExposure = exposureNs
        val i = info ?: return
        status.text = "ISO %d   %s   %dx%d".format(iso, shutterText(exposureNs), i.captureSize.width, i.captureSize.height)
    }

    override fun onBurstProgress(received: Int, total: Int) {
        showProgress("Hold steady… $received/$total")
    }

    override fun onBurst(burst: Burst) {
        showProgress("Processing…")
        processor.process(burst, PhotoProcessor.Options(hdr, aiZoom && aiReady, png), this)
    }

    override fun onCameraError(message: String) {
        processing = false
        shutter.busy = false
        showProgress(null)
        toast(message)
    }

    // ------------------------------------------------------------------ processing callbacks

    override fun onProgress(text: String) {
        showProgress("$text…")
    }

    override fun onSaved(uri: Uri, thumbnail: Bitmap, summary: String) {
        processing = false
        shutter.busy = false
        showProgress(null)
        lastUri = uri
        thumb.setImageBitmap(thumbnail)
        this.summary.text = summary
    }

    override fun onFailed(message: String) {
        processing = false
        shutter.busy = false
        showProgress(null)
        toast(message)
    }

    companion object {
        private const val ACCENT = 0xFFFFC533.toInt()
    }
}
