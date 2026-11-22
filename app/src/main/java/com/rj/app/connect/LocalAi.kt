package com.rj.app.connect

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import com.rj.app.data.OnDeviceAi
import com.rj.core.LocalLlm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gemini Nano through Android AICore (ML Kit GenAI Prompt API). Nothing to download on supported
 * phones; AICore only serves the app that's in the foreground, so keep RJ on screen to use it.
 */
class NanoLlm : LocalLlm, AutoCloseable {
    private val model: GenerativeModel = Generation.getClient(
        generationConfig {
            modelConfig = modelConfig {
                releaseStage = ModelReleaseStage.STABLE
                preference = ModelPreference.FULL
            }
        }
    )

    suspend fun status(): Int = model.checkStatus()

    /** Ask AICore to fetch the model if it isn't on the phone yet. */
    suspend fun download(onProgress: (String) -> Unit) {
        model.download().collect { s ->
            when (s) {
                is DownloadStatus.DownloadStarted -> onProgress("Downloading Gemini Nano…")
                is DownloadStatus.DownloadProgress -> onProgress("Downloading Gemini Nano: ${s.totalBytesDownloaded / 1_000_000} MB")
                is DownloadStatus.DownloadFailed -> onProgress("Gemini Nano download failed: ${s.e.message}")
                is DownloadStatus.DownloadCompleted -> onProgress("Gemini Nano is ready.")
            }
        }
    }

    // Blocking by contract (the brain is called on an IO thread).
    override fun generate(system: String, prompt: String): String = runBlocking {
        val request = generateContentRequest(SystemInstruction(system), TextPart(prompt)) {
            temperature = 0.8f
            topK = 40
            maxOutputTokens = 512
            candidateCount = 1
        }
        model.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
    }

    override fun close() = model.close()
}

/** Gemma 4 (E2B) running through LiteRT-LM on the phone's GPU, falling back to the CPU. Works in the background. */
class GemmaLlm(private val modelFile: File, private val cacheDir: File) : LocalLlm, AutoCloseable {
    private var engine: Engine? = null
    var backendName: String = ""
        private set

    @Synchronized
    private fun engine(): Engine {
        engine?.let { return it }
        fun load(backend: Backend) = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                maxNumTokens = 4096,
                cacheDir = cacheDir.absolutePath,
            )
        ).also { it.initialize() }
        val e = runCatching { load(Backend.GPU()).also { backendName = "GPU" } }
            .getOrElse { load(Backend.CPU()).also { backendName = "CPU" } }
        engine = e
        return e
    }

    /** Load the model ahead of time (takes several seconds). */
    fun warmUp() { engine() }

    @Synchronized
    override fun generate(system: String, prompt: String): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
            maxOutputToken = 600,
        )
        return engine().createConversation(config).use { it.sendMessage(prompt).toString() }
    }

    @Synchronized
    override fun close() {
        runCatching { engine?.close() }
        engine = null
    }
}

/** Chooses and manages the on-device model, and downloads Gemma 4 when asked. */
class LocalAi(private val context: Context) {
    private val prefs = context.getSharedPreferences("rj_local_ai", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nano: NanoLlm? = null
    private var gemma: GemmaLlm? = null

    val gemmaFile: File get() = File(context.getExternalFilesDir("models"), "gemma-4-E2B-it.litertlm")
    val gemmaReady: Boolean get() = gemmaFile.length() > 500_000_000 && downloadId() == null

    /** Human-readable Gemini Nano status for Settings. */
    suspend fun nanoStatus(): String = withContext(Dispatchers.IO) {
        runCatching {
            NanoLlm().use { n ->
                when (n.status()) {
                    FeatureStatus.AVAILABLE -> "Available on this phone"
                    FeatureStatus.UNAVAILABLE -> "Not offered to apps on this phone (use Gemma 4 instead)"
                    else -> "Supported; needs a one-time download by Android"
                }
            }
        }.getOrElse { "Unavailable (${it.message ?: it.javaClass.simpleName})" }
    }

    /** Ask Android to fetch Gemini Nano (if the phone supports it but hasn't got it yet). */
    fun downloadNano(onProgress: (String) -> Unit) = scope.launch {
        runCatching { NanoLlm().use { it.download(onProgress) } }.onFailure { onProgress("Gemini Nano: ${it.message}") }
    }

    /** Pick the model for this drive. Returns the model and a label, or null to use the scripted host. */
    suspend fun prepare(choice: OnDeviceAi): Pair<LocalLlm, String>? = withContext(Dispatchers.IO) {
        if (choice == OnDeviceAi.OFF) return@withContext null
        if (choice == OnDeviceAi.AUTO || choice == OnDeviceAi.NANO) {
            val n = runCatching { NanoLlm() }.getOrNull()
            val ok = n != null && runCatching { n.status() == FeatureStatus.AVAILABLE }.getOrDefault(false)
            if (ok) { nano = n; return@withContext n!! to "Gemini Nano" }
            n?.close()
        }
        if ((choice == OnDeviceAi.AUTO || choice == OnDeviceAi.GEMMA) && gemmaReady) {
            val g = GemmaLlm(gemmaFile, File(context.cacheDir, "litertlm").apply { mkdirs() })
            val loaded = runCatching { g.warmUp() }.isSuccess
            if (loaded) { gemma = g; return@withContext g to "Gemma 4 (${g.backendName})" }
            g.close()
        }
        null
    }

    fun release() {
        nano?.close(); nano = null
        gemma?.close(); gemma = null
    }

    // --- Gemma download (Android DownloadManager: resumable, shows in the notification shade) ---

    private fun downloadId(): Long? = prefs.getLong("gemmaDownload", -1).takeIf { it >= 0 }?.let { id ->
        val dm = context.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) null
            else when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_FAILED -> null.also { prefs.edit().remove("gemmaDownload").apply() }
                else -> id
            }
        }
    }

    fun startGemmaDownload(url: String) {
        if (downloadId() != null) return
        gemmaFile.parentFile?.mkdirs()
        gemmaFile.delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("RJ: Gemma 4 on-device model")
            .setDescription("About 2.6 GB, downloaded once")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(false)
            .setDestinationUri(Uri.fromFile(gemmaFile))
        val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
        prefs.edit().putLong("gemmaDownload", id).apply()
    }

    /** "Downloading 41%", "Ready (2.6 GB)", "Not downloaded"... */
    fun gemmaStatus(): String {
        val id = downloadId()
        if (id != null) {
            val dm = context.getSystemService(DownloadManager::class.java)
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val paused = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_PAUSED
                    val pct = if (total > 0) " ${done * 100 / total}%" else ""
                    return if (paused) "Download paused (waiting for Wi-Fi?)$pct" else "Downloading$pct"
                }
            }
        }
        return if (gemmaReady) "Ready (${"%.1f".format(gemmaFile.length() / 1e9)} GB)" else "Not downloaded"
    }

    fun deleteGemma() {
        release()
        downloadId()?.let { context.getSystemService(DownloadManager::class.java).remove(it) }
        prefs.edit().remove("gemmaDownload").apply()
        gemmaFile.delete()
    }
}
