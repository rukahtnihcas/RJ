package com.rj.procam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.rj.procam.imaging.Look
import com.rj.procam.imaging.Pipeline
import com.rj.procam.imaging.Resample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Turns a captured burst into a saved photo on a background thread. */
class PhotoProcessor(private val context: Context, private val ai: AiUpscaler) {

    class Options(val hdr: Boolean, val aiZoom: Boolean, val png: Boolean)

    interface Callback {
        fun onProgress(text: String)
        fun onSaved(uri: Uri, thumbnail: Bitmap, summary: String)
        fun onFailed(message: String)
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "procam-process") }
    private val main = Handler(Looper.getMainLooper())

    /** Loads the AI model ahead of the first zoomed shot. */
    fun warmUp(done: (Boolean) -> Unit) {
        worker.execute {
            val ok = ai.prepare()
            main.post { done(ok) }
        }
    }

    /** Finishes any shot in progress, then releases the model on the thread that owns it. */
    fun shutdown() {
        worker.execute { ai.close() }
        worker.shutdown()
    }

    fun process(burst: Burst, opt: Options, cb: Callback) {
        worker.execute {
            try {
                run(burst, opt, cb)
            } catch (e: Throwable) {
                Log.e(TAG, "processing failed", e)
                val msg = if (e is OutOfMemoryError) "Not enough memory to process this shot" else "Processing failed: ${e.message}"
                main.post { cb.onFailed(msg) }
            }
        }
    }

    private fun run(burst: Burst, opt: Options, cb: Callback) {
        fun say(s: String) = main.post { cb.onProgress(s) }
        val t0 = System.currentTimeMillis()
        val fw = burst.frames[0].w
        val fh = burst.frames[0].h
        val nFrames = burst.frames.size
        val wantAi = opt.aiZoom && burst.residualZoom >= 1.05f && ai.prepare()
        val plan = Pipeline.plan(fw, fh, nFrames, burst.residualZoom, if (wantAi) ai.maxInputPixels else 0)
        val look = Look(
            underExposureEv = burst.underExposureEv,
            hdrStrength = if (opt.hdr) 1f else 0.3f,
        )
        val out = Pipeline.process(burst.frames, plan, look) { say(it) }
        var argb = out.argb
        var w = out.w
        var h = out.h
        val steps = ArrayList<String>()
        steps.add("${out.framesUsed}-frame merge")
        if (plan.superRes) steps.add("multi-frame super-res 2x")

        if (plan.ai) {
            try {
                val hi = ai.upscale(argb, w, h) { done, total -> say("AI detail $done/$total") }
                Resample.backProject(argb, w, h, hi, AiUpscaler.SCALE)
                argb = hi
                w *= AiUpscaler.SCALE
                h *= AiUpscaler.SCALE
                steps.add("AI 4x (${if (ai.usingGpu) "GPU" else "CPU"})")
            } catch (e: Throwable) {
                Log.e(TAG, "AI upscale failed; saving without it", e)
                steps.add("AI unavailable")
            }
        }
        // Bring it to the planned output size (never enlarging by plain interpolation).
        if (w > plan.outW) {
            say("Finishing")
            argb = Resample.resize(argb, w, h, plan.outW, plan.outH)
            w = plan.outW
            h = plan.outH
        }

        say("Saving")
        var bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        @Suppress("UNUSED_VALUE")
        argb = IntArray(0)
        if (burst.rotationDegrees != 0) {
            val m = Matrix().apply { postRotate(burst.rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, w, h, m, false)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        val uri = save(bmp, opt.png, burst)
        val tw = 256
        val thumb = Bitmap.createScaledBitmap(bmp, tw, (tw.toLong() * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
        val summary = "${bmp.width}x${bmp.height} · ${steps.joinToString(" + ")} · ${(System.currentTimeMillis() - t0) / 100 / 10f}s"
        bmp.recycle()
        main.post { cb.onSaved(uri, thumb, summary) }
    }

    private fun save(bmp: Bitmap, png: Boolean, burst: Burst): Uri {
        val name = "PROCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name + if (png) ".png" else ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the image file")
        try {
            resolver.openOutputStream(uri)?.use { os ->
                val ok = if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
                else bmp.compress(Bitmap.CompressFormat.JPEG, 97, os)
                if (!ok) throw IllegalStateException("Image encoding failed")
            } ?: throw IllegalStateException("Could not open the image file")
            if (!png) writeExif(uri, burst)
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun writeExif(uri: Uri, burst: Burst) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "ProCam")
                exif.setAttribute(
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                )
                if (burst.iso > 0) exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, burst.iso.toString())
                if (burst.exposureNs > 0) exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (burst.exposureNs / 1e9).toString())
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "EXIF not written", e)
        }
    }

    companion object {
        private const val TAG = "PhotoProcessor"
    }
}
