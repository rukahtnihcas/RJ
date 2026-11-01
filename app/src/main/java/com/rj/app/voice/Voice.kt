package com.rj.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice as TtsVoice
import com.rj.app.data.VoiceEngine
import com.rj.core.Speaker
import com.rj.core.SpokenLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

internal val speechAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/**
 * The RJ's voices. Uses ElevenLabs when configured and online (natural, expressive, two distinct
 * voices), and the best on-device TTS voice otherwise. Handles audio focus so the music app either
 * pauses (between songs) or ducks underneath (talking over an intro), like a radio desk.
 */
class VoiceDesk(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val device = DeviceVoice(context)
    private val cloud = ElevenLabsVoice(context)

    var engine = VoiceEngine.DEVICE
    var elevenLabsKey = ""
    var hostVoiceId = ""
    var cohostVoiceId = ""
    var isOnline: () -> Boolean = { true }
    /** Set when the cloud voice fails, so the UI can say why it fell back. */
    var lastError: String? = null
        private set

    private val useCloud get() = engine == VoiceEngine.ELEVENLABS && elevenLabsKey.isNotBlank() && isOnline()

    suspend fun configure(locale: Locale, rate: Float, pitch: Float) = device.configure(locale, rate, pitch, isOnline())

    /** Render upcoming lines in the background so they play instantly when it's their turn. */
    suspend fun prefetch(parts: List<SpokenLine>) {
        if (!useCloud) return
        withContext(Dispatchers.IO) {
            parts.forEach { runCatching { cloud.render(it.text, voiceFor(it.speaker), elevenLabsKey) } }
        }
    }

    /**
     * Speak and suspend until finished.
     * @param duck true = lower the music underneath; false = pause the music for the duration.
     */
    suspend fun say(text: String, speaker: Speaker = Speaker.HOST, duck: Boolean = false) {
        if (text.isBlank()) return
        val focus = AudioFocusRequest.Builder(
            if (duck) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        ).setAudioAttributes(speechAttributes).build()
        audio.requestAudioFocus(focus)
        try {
            val spoke = useCloud && runCatching {
                val file = withContext(Dispatchers.IO) { cloud.render(text, voiceFor(speaker), elevenLabsKey) }
                cloud.play(file)
                lastError = null
                true
            }.getOrElse { e ->
                lastError = "Natural voice unavailable (${e.message}), using the phone's voice."
                false
            }
            if (!spoke) device.speak(text, speaker)
        } finally {
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private fun voiceFor(s: Speaker) = if (s == Speaker.COHOST) cohostVoiceId else hostVoiceId

    fun stop() { device.stop(); cloud.stop() }
}

/** On-device TTS, tuned to sound as natural as the phone allows. */
private class DeviceVoice(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var hostVoice: TtsVoice? = null
    private var cohostVoice: TtsVoice? = null
    private var basePitch = 1f

    // Google's engine has by far the most natural voices; use it when it's installed.
    private val googleEngine = "com.google.android.tts"
    private val tts: TextToSpeech = run {
        val hasGoogle = runCatching { context.packageManager.getPackageInfo(googleEngine, 0) }.isSuccess
        val listener = TextToSpeech.OnInitListener { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        if (hasGoogle) TextToSpeech(context.applicationContext, listener, googleEngine)
        else TextToSpeech(context.applicationContext, listener)
    }.apply {
        setAudioAttributes(speechAttributes)
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = Unit
            override fun onDone(id: String) { synchronized(pending) { pending.remove(id) }?.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { synchronized(pending) { pending.remove(id) }?.complete(Unit) }
            override fun onError(id: String, errorCode: Int) { synchronized(pending) { pending.remove(id) }?.complete(Unit) }
            override fun onStop(id: String, interrupted: Boolean) { synchronized(pending) { pending.remove(id) }?.complete(Unit) }
        })
    }

    suspend fun configure(locale: Locale, rate: Float, pitch: Float, online: Boolean) {
        if (!ready.await()) return
        tts.language = locale
        tts.setSpeechRate(rate)
        basePitch = pitch
        val candidates = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == locale.language }
            .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .filter { online || !it.isNetworkConnectionRequired }
            .sortedWith(
                compareByDescending<TtsVoice> { it.locale.country == locale.country }
                    .thenByDescending { it.quality }
                    .thenByDescending { it.isNetworkConnectionRequired } // network voices sound better
                    .thenBy { it.latency }
            )
        hostVoice = candidates.firstOrNull()
        // A clearly different voice for the co-host: different speaker code (e.g. "-x-iom-" vs "-x-tpd-").
        fun speakerCode(v: TtsVoice) = Regex("-x-([a-z]+)").find(v.name)?.groupValues?.get(1) ?: v.name
        cohostVoice = candidates.firstOrNull { c -> hostVoice?.let { speakerCode(it) != speakerCode(c) } ?: false }
        hostVoice?.let { tts.voice = it }
    }

    suspend fun speak(text: String, speaker: Speaker) {
        if (!ready.await()) return
        val v = if (speaker == Speaker.COHOST) cohostVoice ?: hostVoice else hostVoice
        v?.let { if (tts.voice != it) tts.voice = it }
        // If there's no second voice, shift the co-host's pitch a little so they're distinguishable.
        tts.setPitch(if (speaker == Speaker.COHOST && cohostVoice == null) basePitch * 0.88f else basePitch)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        synchronized(pending) { pending[id] = done }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        withTimeoutOrNull(5_000L + text.length * 120L) { done.await() }
    }

    fun stop() { tts.stop() }
}

/** ElevenLabs text-to-speech, cached on disk so pre-rendered lines play instantly. */
private class ElevenLabsVoice(context: Context) {
    private val dir = File(context.cacheDir, "voice").apply { mkdirs() }
    private val http = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(30)).build()
    private val rendered = object : LinkedHashMap<String, File>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, File>): Boolean =
            (size > 80).also { if (it) eldest.value.delete() }
    }
    @Volatile private var player: MediaPlayer? = null

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** Thread-safe: if the same line is already being rendered (prefetch), wait for that instead of re-requesting. */
    fun render(text: String, voiceId: String, key: String): File {
        val id = sha1("$voiceId|$MODEL|$text")
        synchronized(locks.computeIfAbsent(id) { Any() }) {
            try { return renderLocked(id, text, voiceId, key) } finally { locks.remove(id) }
        }
    }

    private fun renderLocked(id: String, text: String, voiceId: String, key: String): File {
        synchronized(rendered) { rendered[id]?.takeIf { it.exists() }?.let { return it } }
        val body = JSONObject()
            .put("text", text)
            .put("model_id", MODEL)
            .put("voice_settings", JSONObject()
                .put("stability", 0.4)          // a bit of variation sounds more human
                .put("similarity_boost", 0.8)
                .put("style", 0.35)             // expressive, radio-presenter delivery
                .put("use_speaker_boost", true))
            .toString()
        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128")
            .header("xi-api-key", key)
            .header("Accept", "audio/mpeg")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error(if (r.code == 401) "ElevenLabs key rejected" else "ElevenLabs HTTP ${r.code}")
            val file = File(dir, "$id.mp3")
            file.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            synchronized(rendered) { rendered[id] = file }
            return file
        }
    }

    suspend fun play(file: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            player = mp
            fun finish() {
                runCatching { mp.release() }
                if (player === mp) player = null
                if (cont.isActive) cont.resume(Unit)
            }
            runCatching {
                mp.setAudioAttributes(speechAttributes)
                mp.setDataSource(file.path)
                mp.setOnCompletionListener { finish() }
                mp.setOnErrorListener { _, _, _ -> finish(); true }
                mp.setOnPreparedListener { it.start() }
                mp.prepareAsync()
            }.onFailure { finish() }
            cont.invokeOnCancellation { runCatching { mp.stop(); mp.release() } }
        }
    }

    fun stop() { runCatching { player?.stop() } }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object { const val MODEL = "eleven_multilingual_v2" }
}

/** Hands-free listening through the platform speech recognizer. */
class Ears(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())

    fun available() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Beep, listen for one utterance, and return the transcript (or null on silence / error). */
    suspend fun listen(language: Locale, preferOffline: Boolean = false): String? {
        if (!available()) return null
        withContext(Dispatchers.Main) {
            runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70).startTone(ToneGenerator.TONE_PROP_BEEP, 150) }
        }
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                var recognizer: SpeechRecognizer? = null
                fun finish(result: String?) {
                    main.post { recognizer?.destroy(); recognizer = null }
                    if (cont.isActive) cont.resume(result)
                }
                main.post {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(object : RecognitionListener {
                            override fun onResults(results: Bundle) {
                                finish(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                            }
                            override fun onError(error: Int) = finish(null)
                            override fun onReadyForSpeech(params: Bundle?) = Unit
                            override fun onBeginningOfSpeech() = Unit
                            override fun onRmsChanged(rmsdB: Float) = Unit
                            override fun onBufferReceived(buffer: ByteArray?) = Unit
                            override fun onEndOfSpeech() = Unit
                            override fun onPartialResults(partialResults: Bundle?) = Unit
                            override fun onEvent(eventType: Int, params: Bundle?) = Unit
                        })
                        startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.toLanguageTag())
                            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        })
                    }
                }
                cont.invokeOnCancellation { main.post { recognizer?.destroy(); recognizer = null } }
            }
        }
    }
}
