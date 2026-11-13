package earth.ripple.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import earth.ripple.core.Vocabulary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.FloatBuffer

/**
 * On-device recognition with the image half of a CLIP-family vision model, run by ONNX Runtime.
 * The model and the pre-embedded vocabulary are bundled in assets/recognizer by the build
 * (tools/recognizer.py). Without them, Ripple falls back to ML Kit's basic labeler.
 */
class Recognizer(private val context: Context) {
    @Serializable
    data class Config(
        val model: String, val revision: String = "", val variant: String = "fp32",
        val input: String, val output: String, val dim: Int, val count: Int,
        val resize: String = "exact", val size: Int = 224, val center_crop: Boolean = false, val crop: Int? = null,
        val rescale: Float = 1f / 255f, val normalize: Boolean = true,
        val mean: List<Float> = listOf(0.5f, 0.5f, 0.5f), val std: List<Float> = listOf(0.5f, 0.5f, 0.5f),
        /** Separate weight files the model refers to by name (used by very large models). */
        val external: List<String> = emptyList(),
    )

    sealed interface Status {
        data object Missing : Status
        data object Preparing : Status
        data class Ready(val model: String, val millis: Long) : Status
        data class Failed(val reason: String) : Status
    }

    @Volatile var status: Status = Status.Missing
        private set
    /** Milliseconds the last photo took. Used to decide whether live hints can afford the model. */
    @Volatile var lastMillis: Long = 0
        private set

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var config: Config? = null
    private var vocabulary: Vocabulary? = null
    private val lock = Any()

    val available: Boolean get() = status is Status.Ready

    /** Loads the model, copying it out of the APK on first run. Slow: call off the main thread. */
    fun load() {
        val files = runCatching { context.assets.list(DIR)?.toSet() }.getOrNull().orEmpty()
        if ("vision.onnx" !in files) { status = Status.Missing; return }
        status = Status.Preparing
        try {
            val json = Json { ignoreUnknownKeys = true }
            val cfg = json.decodeFromString(Config.serializer(), context.assets.open("$DIR/config.json").bufferedReader().use { it.readText() })
            val labels = context.assets.open("$DIR/labels.tsv").bufferedReader().use { it.readText() }
            val embeds = context.assets.open("$DIR/text_embeds.bin").use { it.readBytes() }
            val vocab = Vocabulary.load(labels, embeds, cfg.dim)
            check(vocab.ids.size == cfg.count) { "vocabulary size mismatch" }

            // ONNX Runtime wants files. Copy once per model version; later launches reuse them.
            val root = File(context.noBackupFilesDir, "recognizer")
            val dir = File(root, "${cfg.revision.take(12)}-${cfg.variant}")
            root.listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() }
            dir.mkdirs()
            for (name in listOf("vision.onnx") + cfg.external) {
                val target = File(dir, name)
                val expected = runCatching { context.assets.openFd("$DIR/$name").use { it.length } }.getOrDefault(-1L)
                if (target.exists() && (expected < 0 || target.length() == expected)) continue
                val tmp = File(dir, "$name.partial")
                context.assets.open("$DIR/$name").use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                check(tmp.renameTo(target)) { "could not store the model" }
            }
            val target = File(dir, "vision.onnx")

            val e = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            }
            val s = e.createSession(target.absolutePath, options)
            synchronized(lock) { env = e; session = s; config = cfg; vocabulary = vocab }
            // Warm up once so the user's first scan isn't the slow one.
            val warm = SystemClock.elapsedRealtime()
            classify(Bitmap.createBitmap(cfg.size, cfg.size, Bitmap.Config.ARGB_8888))
            status = Status.Ready(cfg.model.substringAfter('/'), SystemClock.elapsedRealtime() - warm)
        } catch (t: Throwable) {
            status = Status.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Ranks what [bitmap] most likely shows. Null when the model is not loaded. */
    fun classify(bitmap: Bitmap, limit: Int = 5): List<Vocabulary.Guess>? {
        val (e, s, cfg, vocab) = synchronized(lock) { Quad(env ?: return null, session ?: return null, config ?: return null, vocabulary ?: return null) }
        val started = SystemClock.elapsedRealtime()
        val pixels = prepare(bitmap, cfg)
        val side = (if (cfg.center_crop) cfg.crop ?: cfg.size else cfg.size).toLong()
        val embedding = synchronized(s) { // one inference at a time; the model already uses several threads
            OnnxTensor.createTensor(e, FloatBuffer.wrap(pixels), longArrayOf(1, 3, side, side)).use { t ->
                s.run(mapOf(cfg.input to t)).use { r ->
                    val out = r.get(cfg.output).orElse(r.get(0)) as OnnxTensor
                    val fb = out.floatBuffer
                    FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
        }
        lastMillis = SystemClock.elapsedRealtime() - started
        return vocab.rank(embedding.copyOf(cfg.dim), limit)
    }

    /** Resize, centre-crop and normalise exactly as tools/recognizer.py does at build time. */
    private fun prepare(source: Bitmap, cfg: Config): FloatArray {
        val bmp = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
        val scaled = if (cfg.resize == "shortest_edge") {
            val k = cfg.size.toFloat() / minOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, maxOf(1, Math.round(bmp.width * k)), maxOf(1, Math.round(bmp.height * k)), true)
        } else Bitmap.createScaledBitmap(bmp, cfg.size, cfg.size, true)
        val side = if (cfg.center_crop) cfg.crop ?: cfg.size else cfg.size
        val left = ((scaled.width - side) / 2).coerceAtLeast(0)
        val top = ((scaled.height - side) / 2).coerceAtLeast(0)
        val px = IntArray(side * side)
        scaled.getPixels(px, 0, side, left, top, minOf(side, scaled.width), minOf(side, scaled.height))
        val plane = side * side
        val out = FloatArray(3 * plane)
        val m = cfg.mean; val sd = cfg.std
        for (i in 0 until plane) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) * cfg.rescale
            val g = ((c shr 8) and 0xFF) * cfg.rescale
            val b = (c and 0xFF) * cfg.rescale
            if (cfg.normalize) {
                out[i] = (r - m[0]) / sd[0]; out[plane + i] = (g - m[1]) / sd[1]; out[2 * plane + i] = (b - m[2]) / sd[2]
            } else { out[i] = r; out[plane + i] = g; out[2 * plane + i] = b }
        }
        return out
    }

    private data class Quad(val e: OrtEnvironment, val s: OrtSession, val c: Config, val v: Vocabulary)

    companion object { const val DIR = "recognizer" }
}
