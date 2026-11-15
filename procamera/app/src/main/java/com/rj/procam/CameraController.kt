package com.rj.procam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import com.rj.procam.imaging.YuvFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class ShotMode { PHOTO, NIGHT, PRO }

/** What the UI wants from the camera. null means "automatic". */
class CamSettings {
    var zoom = 1f
    var iso: Int? = null
    var shutterNs: Long? = null
    var focusDiopters: Float? = null
    var awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
    var evSteps = 0
}

/** Fixed facts about the opened camera. */
class CamInfo(
    val previewSize: Size,
    val captureSize: Size,
    val sensorOrientation: Int,
    val zoomMin: Float,
    val zoomMax: Float,
    /** Zoom ratios at which a lens of the camera is used without digital enlargement. */
    val opticalAnchors: List<Float>,
    val manualSensor: Boolean,
    val isoRange: IntRange,
    val shutterRangeNs: LongRange,
    val minFocusDiopters: Float,
    val evRange: IntRange,
    val evStep: Float,
    val awbModes: List<Int>,
)

class Burst(
    val frames: MutableList<YuvFrame>,
    val iso: Int,
    val exposureNs: Long,
    val underExposureEv: Float,
    /** Zoom still to be applied by cropping (user zoom / zoom the frames were captured at). */
    val residualZoom: Float,
    val userZoom: Float,
    val rotationDegrees: Int,
    val mode: ShotMode,
)

class CameraController(context: Context, private val listener: Listener) {

    interface Listener {
        fun onCameraReady(info: CamInfo)
        fun onMetering(iso: Int, exposureNs: Long, focusDiopters: Float)
        fun onBurstProgress(received: Int, total: Int)
        fun onBurst(burst: Burst)
        fun onCameraError(message: String)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val main = Handler(Looper.getMainLooper())
    private var camThread: HandlerThread? = null
    private var cam: Handler? = null
    private var frameThread: HandlerThread? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var chars: CameraCharacteristics? = null
    var info: CamInfo? = null
        private set

    val settings = CamSettings()

    @Volatile private var lastIso = 0
    @Volatile private var lastExposureNs = 0L
    @Volatile private var lastFocus = 0f
    private var lastMeterPost = 0L

    private var afRegion: MeteringRectangle? = null
    private val revertAf = Runnable {
        afRegion = null
        updatePreview()
    }

    // ---- burst state (guarded by lock) ----
    private val lock = Any()
    private var pending: PendingBurst? = null

    private class PendingBurst(
        val total: Int, val iso: Int, val exposureNs: Long, val underEv: Float,
        val residualZoom: Float, val userZoom: Float, val rotation: Int, val mode: ShotMode,
    ) {
        val frames = ArrayList<Pair<Long, YuvFrame>>()
        val physicalIds = HashMap<Long, String>()
        var sequenceDone = false
        var delivered = false
    }

    val isOpen: Boolean get() = session != null
    val busy: Boolean get() = synchronized(lock) { pending != null }

    // ------------------------------------------------------------------ open / close

    @SuppressLint("MissingPermission")
    fun open(texture: SurfaceTexture) {
        if (device != null || camThread != null) return
        camThread = HandlerThread("procam-camera").also { it.start() }
        cam = Handler(camThread!!.looper)
        frameThread = HandlerThread("procam-frames").also { it.start() }
        try {
            val id = pickCamera() ?: return fail("No back camera found")
            val c = manager.getCameraCharacteristics(id)
            chars = c
            val inf = describe(c)
            info = inf
            texture.setDefaultBufferSize(inf.previewSize.width, inf.previewSize.height)
            previewSurface = Surface(texture)
            reader = ImageReader.newInstance(
                inf.captureSize.width, inf.captureSize.height, ImageFormat.YUV_420_888, 4
            ).also { it.setOnImageAvailableListener(::onImage, Handler(frameThread!!.looper)) }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    createSession(d)
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    device = null
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    device = null
                    fail("Camera error $error")
                }
            }, cam)
        } catch (e: Exception) {
            fail("Cannot open camera: ${e.message}")
        }
    }

    fun close() {
        main.removeCallbacks(revertAf)
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        reader?.close()
        reader = null
        previewSurface?.release()
        previewSurface = null
        camThread?.quitSafely()
        camThread = null
        cam = null
        frameThread?.quitSafely()
        frameThread = null
        synchronized(lock) { pending = null }
    }

    private fun fail(msg: String) {
        Log.e(TAG, msg)
        main.post { listener.onCameraError(msg) }
    }

    private fun pickCamera(): String? {
        // The first back camera is the main (usually logical multi-lens) camera.
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    private fun describe(c: CameraCharacteristics): CamInfo {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("no stream configurations")
        val level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val full = level == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ||
            level == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manual = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

        fun is43(s: Size) = abs(s.width * 3 - s.height * 4) <= 16 && s.width % 2 == 0 && s.height % 2 == 0
        val preview = (map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray())
            .filter { is43(it) && it.width <= 1600 }.maxByOrNull { it.width } ?: Size(640, 480)
        // Full-resolution YUV next to a preview is only guaranteed on FULL devices;
        // elsewhere stay within the sizes every device must support together.
        val maxPixels = if (full) 13_500_000 else 2_800_000
        val yuv = (map.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray())
            .filter { is43(it) && it.width.toLong() * it.height <= maxPixels }
            .maxByOrNull { it.width } ?: Size(640, 480)

        var zMin = 1f
        var zMax = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        if (Build.VERSION.SDK_INT >= 30) {
            c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                zMin = it.lower
                zMax = it.upper
            }
        }
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        return CamInfo(
            previewSize = preview,
            captureSize = yuv,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            zoomMin = zMin,
            zoomMax = max(zMax, 1f),
            opticalAnchors = opticalAnchors(c, zMax),
            manualSensor = manual && iso != null && exp != null,
            isoRange = if (iso != null) iso.lower..iso.upper else 100..100,
            shutterRangeNs = if (exp != null) exp.lower..exp.upper else 0L..0L,
            minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            evRange = if (ev != null) ev.lower..ev.upper else 0..0,
            evStep = evStep,
            awbModes = (c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)).toList(),
        )
    }

    /**
     * Zoom ratios at which the camera switches to a longer lens, derived from the focal
     * lengths of its physical cameras. Shooting at one of these and cropping the rest
     * ourselves means the pipeline always starts from real, un-enlarged sensor pixels.
     */
    private fun opticalAnchors(c: CameraCharacteristics, zoomMax: Float): List<Float> {
        val anchors = arrayListOf(1f)
        if (Build.VERSION.SDK_INT < 30) return anchors
        try {
            fun fov(cc: CameraCharacteristics): Float? {
                val f = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
                val s = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
                return f / s.width
            }
            val base = fov(c) ?: return anchors
            for (pid in c.physicalCameraIds) {
                val r = (fov(manager.getCameraCharacteristics(pid)) ?: continue) / base
                if (r > 1.3f && r <= zoomMax && anchors.none { abs(it - r) < 0.2f }) anchors.add(r)
            }
        } catch (e: Exception) {
            Log.w(TAG, "anchor detection failed", e)
        }
        anchors.sort()
        return anchors
    }

    @Suppress("DEPRECATION")
    private fun createSession(d: CameraDevice) {
        val surfaces = listOf(previewSurface ?: return, reader?.surface ?: return)
        try {
            d.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    updatePreview()
                    info?.let { i -> main.post { listener.onCameraReady(i) } }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    fail("Camera stream configuration failed")
                }
            }, cam)
        } catch (e: Exception) {
            fail("Cannot start camera session: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ preview

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureNs = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocus = it }
            val now = System.currentTimeMillis()
            if (now - lastMeterPost > 200) {
                lastMeterPost = now
                val i = lastIso
                val e = lastExposureNs
                val f = lastFocus
                main.post { listener.onMetering(i, e, f) }
            }
        }
    }

    /** Re-sends the preview request after any setting changed. */
    fun updatePreview() {
        val h = cam ?: return
        h.post {
            val s = session ?: return@post
            val d = device ?: return@post
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(previewSurface ?: return@post)
                applySettings(b, settings.zoom)
                s.setRepeatingRequest(b.build(), previewCallback, h)
            } catch (e: Exception) {
                Log.w(TAG, "preview update failed", e)
            }
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder, zoom: Float) {
        val c = chars ?: return
        if (Build.VERSION.SDK_INT >= 30 && c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            val a = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val z = max(1f, zoom)
            val w = (a.width() / z).toInt()
            val h = (a.height() / z).toInt()
            val x = (a.width() - w) / 2
            val y = (a.height() - h) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(x, y, x + w, y + h))
        }
    }

    private fun applySettings(b: CaptureRequest.Builder, zoom: Float) {
        val inf = info ?: return
        val st = settings
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        applyZoom(b, zoom)

        val manualExposure = inf.manualSensor && (st.iso != null || st.shutterNs != null) && lastIso > 0
        if (manualExposure) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, (st.iso ?: lastIso).coerceIn(inf.isoRange))
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, (st.shutterNs ?: lastExposureNs).coerceIn(inf.shutterRangeNs))
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, st.evSteps.coerceIn(inf.evRange))
        }

        val fd = st.focusDiopters
        if (fd != null && inf.minFocusDiopters > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd.coerceIn(0f, inf.minFocusDiopters))
        } else {
            val region = afRegion
            if (region != null) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, if (st.awbMode in inf.awbModes) st.awbMode else CameraMetadata.CONTROL_AWB_MODE_AUTO)
    }

    /** Focus and meter on a point of the preview; nx, ny are 0..1 in the portrait view. */
    fun focusAt(nx: Float, ny: Float) {
        val c = chars ?: return
        val inf = info ?: return
        val h = cam ?: return
        if (settings.focusDiopters != null) return
        val a = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        // The view is the sensor image rotated by the sensor orientation.
        val (sx, sy) = when (inf.sensorOrientation) {
            90 -> ny to 1f - nx
            270 -> 1f - ny to nx
            180 -> 1f - nx to 1f - ny
            else -> nx to ny
        }
        val half = (min(a.width(), a.height()) * 0.06f).toInt()
        val cx = (sx * a.width()).toInt().coerceIn(half, a.width() - half)
        val cy = (sy * a.height()).toInt().coerceIn(half, a.height() - half)
        afRegion = MeteringRectangle(cx - half, cy - half, half * 2, half * 2, MeteringRectangle.METERING_WEIGHT_MAX)
        h.post {
            val s = session ?: return@post
            val d = device ?: return@post
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(previewSurface ?: return@post)
                applySettings(b, settings.zoom)
                s.setRepeatingRequest(b.build(), previewCallback, h)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, h)
            } catch (e: Exception) {
                Log.w(TAG, "focus failed", e)
            }
        }
        main.removeCallbacks(revertAf)
        main.postDelayed(revertAf, 5000)
    }

    // ------------------------------------------------------------------ burst capture

    /**
     * Shoots a burst of identically exposed full-resolution frames.
     * @param hdr under-expose the burst to keep highlights; the pipeline brings shadows back
     * @param maxFrames memory limit decided by the caller
     */
    fun capture(mode: ShotMode, hdr: Boolean, maxFrames: Int, rotationDegrees: Int) {
        val inf = info ?: return
        val h = cam ?: return
        if (busy) return
        val st = settings
        val userZoom = st.zoom

        // Capture at the nearest optical anchor at or below the requested zoom.
        var captureZoom = userZoom
        if (userZoom > 1f) captureZoom = inf.opticalAnchors.filter { it <= userZoom + 0.01f }.maxOrNull() ?: 1f
        val residual = max(1f, userZoom / captureZoom)

        val manualRequested = st.iso != null || st.shutterNs != null
        var iso = st.iso ?: lastIso
        var exposure = st.shutterNs ?: lastExposureNs
        var underEv = 0f
        val controlExposure = inf.manualSensor && iso > 0 && exposure > 0
        if (controlExposure && !manualRequested) {
            if (mode == ShotMode.NIGHT) {
                // Trade gain for time: longer frames at lower ISO, the burst handles the shake.
                val target = 66_000_000L
                if (exposure < target) {
                    val k = min(target.toFloat() / exposure, iso.toFloat() / inf.isoRange.first)
                    if (k > 1f) {
                        exposure = (exposure * k).toLong()
                        iso = (iso / k).toInt()
                    }
                }
            }
            if (hdr) {
                underEv = if (mode == ShotMode.NIGHT) 0.5f else 1f
                val want = 2f.pow(-underEv)
                val newIso = max(inf.isoRange.first, (iso * want).toInt())
                val rest = want * iso / newIso // what lowering ISO could not cover
                iso = newIso
                exposure = (exposure * rest).toLong()
            }
            iso = iso.coerceIn(inf.isoRange)
            exposure = exposure.coerceIn(inf.shutterRangeNs)
        }

        var n = when (mode) {
            ShotMode.NIGHT -> 12
            else -> if (iso >= 800) 8 else 6
        }
        n = n.coerceIn(1, max(1, maxFrames))

        val p = PendingBurst(n, iso, exposure, underEv, residual, userZoom, rotationDegrees, mode)
        synchronized(lock) { pending = p }
        // Whatever happens in the camera stack, the burst ends: with the frames received
        // so far, or with an error if there are none.
        h.postDelayed({ finishBurst(p, force = true) }, 8000 + n * exposure / 1_000_000)

        h.post {
            val s = session
            val d = device
            val target = reader?.surface
            if (s == null || d == null || target == null) {
                synchronized(lock) { pending = null }
                return@post fail("Camera not ready")
            }
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(target)
                applySettings(b, captureZoom)
                if (controlExposure) {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                } else {
                    b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                }
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                // The pipeline does its own sharpening; in good light it also prefers the
                // sensor's real texture over the camera's noise smoothing.
                val c = chars
                val edge = c?.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                if (edge != null && edge.contains(CameraMetadata.EDGE_MODE_OFF)) {
                    b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                }
                val nr = c?.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                if (nr != null) {
                    val wantNr = if (iso in 1..400) CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
                    else CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
                    if (nr.contains(wantNr)) b.set(CaptureRequest.NOISE_REDUCTION_MODE, wantNr)
                }
                val req = b.build()
                s.captureBurst(List(n) { req }, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(cs: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                        val pid = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: return
                        synchronized(lock) { p.physicalIds[ts] = pid }
                    }

                    override fun onCaptureSequenceCompleted(cs: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                        synchronized(lock) { p.sequenceDone = true }
                        // Images can trail the results slightly; give them a moment.
                        h.postDelayed({ finishBurst(p, force = false) }, 150)
                        h.postDelayed({ finishBurst(p, force = true) }, 2500)
                    }

                    override fun onCaptureSequenceAborted(cs: CameraCaptureSession, sequenceId: Int) {
                        synchronized(lock) { p.sequenceDone = true }
                        finishBurst(p, force = true)
                    }
                }, h)
            } catch (e: Exception) {
                synchronized(lock) { pending = null }
                fail("Capture failed: ${e.message}")
            }
        }
    }

    private fun onImage(r: ImageReader) {
        val image = try {
            r.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return
        var p: PendingBurst? = null
        try {
            p = synchronized(lock) { pending }
            if (p == null || p.delivered) return
            val frame = copyFrame(image)
            val ts = image.timestamp
            val count = synchronized(lock) {
                p.frames.add(ts to frame)
                p.frames.size
            }
            main.post { listener.onBurstProgress(count, p.total) }
        } catch (e: Throwable) {
            Log.e(TAG, "frame copy failed", e)
        } finally {
            image.close()
        }
        if (p != null) finishBurst(p, force = false)
    }

    private fun finishBurst(p: PendingBurst, force: Boolean) {
        val burst: Burst
        synchronized(lock) {
            if (p.delivered) return
            val complete = p.frames.size >= p.total && p.sequenceDone
            if (!complete && !force) return
            p.delivered = true
            pending = null
            // If the camera changed lens part-way, keep only frames from the lens used most.
            var frames = p.frames.toList()
            if (p.physicalIds.isNotEmpty()) {
                val ids = frames.map { p.physicalIds[it.first] }
                val best = ids.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                if (best != null) {
                    val same = frames.filterIndexed { i, _ -> ids[i] == null || ids[i] == best }
                    if (same.isNotEmpty()) frames = same
                }
            }
            burst = Burst(
                frames.map { it.second }.toMutableList(), p.iso, p.exposureNs, p.underEv,
                p.residualZoom, p.userZoom, p.rotation, p.mode,
            )
            p.frames.clear()
        }
        if (burst.frames.isEmpty()) fail("The camera delivered no frames") else main.post { listener.onBurst(burst) }
    }

    /** Copies a YUV_420_888 image into tightly packed planes, whatever its row/pixel strides. */
    private fun copyFrame(image: Image): YuvFrame {
        val w = image.width and 1.inv()
        val h = image.height and 1.inv()
        val y = ByteArray(w * h)
        val yp = image.planes[0]
        val yb = yp.buffer
        if (yp.rowStride == w && yb.remaining() >= w * h) {
            yb.get(y, 0, w * h)
        } else {
            for (r in 0 until h) {
                yb.position(r * yp.rowStride)
                yb.get(y, r * w, w)
            }
        }
        val cw = w / 2
        val ch = h / 2
        fun chroma(plane: Image.Plane): ByteArray {
            val out = ByteArray(cw * ch)
            val buf = plane.buffer
            val ps = plane.pixelStride
            val rs = plane.rowStride
            if (ps == 1) {
                for (r in 0 until ch) {
                    buf.position(r * rs)
                    buf.get(out, r * cw, cw)
                }
            } else {
                val rowLen = (cw - 1) * ps + 1
                val row = ByteArray(rowLen)
                for (r in 0 until ch) {
                    buf.position(r * rs)
                    buf.get(row, 0, min(rowLen, buf.remaining()))
                    var o = r * cw
                    var i = 0
                    while (i < rowLen) {
                        out[o++] = row[i]
                        i += ps
                    }
                }
            }
            return out
        }
        return YuvFrame(w, h, y, chroma(image.planes[1]), chroma(image.planes[2]))
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
