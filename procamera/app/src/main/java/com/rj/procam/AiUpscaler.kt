package com.rj.procam

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * 4x neural super-resolution, entirely on the device.
 *
 * The model is the conv trunk of Real-ESRGAN "realesr-general-x4v3" (BSD-3-Clause),
 * converted to LiteRT by tools/convert_model.py. It runs on the GPU when the device
 * supports the GPU delegate and on the CPU (XNNPACK) otherwise. Nothing leaves the phone.
 */
class AiUpscaler(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var gpu: GpuDelegate? = null
    var usingGpu = false
        private set

    /** Largest input (pixels) worth sending through the network on this device. */
    val maxInputPixels: Int get() = if (usingGpu) 800_000 else 250_000

    /** Loads the model; returns false if it cannot run on this device at all. */
    fun prepare(): Boolean {
        if (interpreter != null) return true
        val model = try {
            loadModel()
        } catch (e: Exception) {
            Log.w(TAG, "model missing", e)
            return false
        }
        try {
            if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                val d = GpuDelegate()
                try {
                    val it = Interpreter(model, Interpreter.Options().addDelegate(d))
                    warmUp(it)
                    interpreter = it
                    gpu = d
                    usingGpu = true
                    return true
                } catch (e: Throwable) {
                    Log.w(TAG, "GPU delegate failed, using CPU", e)
                    d.close()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "GPU check failed, using CPU", e)
        }
        return try {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val it = Interpreter(model, Interpreter.Options().setNumThreads(threads))
            interpreter = it
            usingGpu = false
            true
        } catch (e: Throwable) {
            Log.e(TAG, "interpreter failed", e)
            false
        }
    }

    private fun loadModel(): MappedByteBuffer {
        context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    private fun warmUp(it: Interpreter) {
        val inp = ByteBuffer.allocateDirect(TILE * TILE * 3 * 4).order(ByteOrder.nativeOrder())
        val out = ByteBuffer.allocateDirect(TILE * TILE * OUT_CH * 4).order(ByteOrder.nativeOrder())
        it.run(inp, out)
    }

    /**
     * Upscales packed ARGB by 4x. The image is processed in overlapping tiles; only the
     * centre of each tile is kept so tile borders never show.
     */
    fun upscale(src: IntArray, w: Int, h: Int, progress: (Int, Int) -> Unit): IntArray {
        val it = interpreter ?: throw IllegalStateException("prepare() not called")
        val core = TILE - 2 * PAD
        val inBuf = ByteBuffer.allocateDirect(TILE * TILE * 3 * 4).order(ByteOrder.nativeOrder())
        val outBuf = ByteBuffer.allocateDirect(TILE * TILE * OUT_CH * 4).order(ByteOrder.nativeOrder())
        val inF = FloatArray(TILE * TILE * 3)
        val outF = FloatArray(TILE * TILE * OUT_CH)
        val ow = w * SCALE
        val out = IntArray(ow * h * SCALE)
        val tilesX = (w + core - 1) / core
        val tilesY = (h + core - 1) / core
        var done = 0
        for (tyI in 0 until tilesY) for (txI in 0 until tilesX) {
            val cx = txI * core
            val cy = tyI * core
            // Fill the tile, repeating edge pixels where it hangs over the image.
            var k = 0
            for (y in 0 until TILE) {
                val sy = (cy - PAD + y).coerceIn(0, h - 1) * w
                for (x in 0 until TILE) {
                    val p = src[sy + (cx - PAD + x).coerceIn(0, w - 1)]
                    inF[k++] = ((p shr 16) and 0xFF) * INV255
                    inF[k++] = ((p shr 8) and 0xFF) * INV255
                    inF[k++] = (p and 0xFF) * INV255
                }
            }
            inBuf.rewind()
            inBuf.asFloatBuffer().put(inF)
            outBuf.rewind()
            it.run(inBuf, outBuf)
            outBuf.rewind()
            outBuf.asFloatBuffer().get(outF)

            // Pixel-shuffle (depth-to-space) plus the nearest-neighbour residual of the input.
            val cw = minOf(core, w - cx)
            val chh = minOf(core, h - cy)
            for (y in 0 until chh) for (x in 0 until cw) {
                val ti = ((y + PAD) * TILE + x + PAD)
                val o = ti * OUT_CH
                val i3 = ti * 3
                val r0 = inF[i3]
                val g0 = inF[i3 + 1]
                val b0 = inF[i3 + 2]
                for (i in 0 until SCALE) {
                    val row = ((cy + y) * SCALE + i) * ow + (cx + x) * SCALE
                    for (j in 0 until SCALE) {
                        val s = i * SCALE + j
                        val r = to8(outF[o + s] + r0)
                        val g = to8(outF[o + 16 + s] + g0)
                        val b = to8(outF[o + 32 + s] + b0)
                        out[row + j] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
            done++
            progress(done, tilesX * tilesY)
        }
        return out
    }

    private fun to8(v: Float): Int {
        val i = (v * 255f + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        gpu?.close()
        gpu = null
    }

    companion object {
        private const val TAG = "AiUpscaler"
        private const val MODEL = "sr_x4.tflite"
        const val SCALE = 4
        private const val TILE = 192
        private const val PAD = 16
        private const val OUT_CH = 48
        private const val INV255 = 1f / 255f
    }
}
