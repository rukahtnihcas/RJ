package com.rj.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * The RJ's voice. Uses on-device TTS and audio focus so the music app either pauses (between songs)
 * or ducks underneath (talking over an intro), exactly like a radio desk.
 */
class Voice(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val ready = CompletableDeferred<Boolean>()
    private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setAudioAttributes(attrs)
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = Unit
            override fun onDone(id: String) { synchronized(pending) { pending.remove(id) }?.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { synchronized(pending) { pending.remove(id) }?.complete(Unit) }
            override fun onError(id: String, errorCode: Int) { onError(id) }
            override fun onStop(id: String, interrupted: Boolean) { onError(id) }
        })
    }

    suspend fun configure(locale: Locale, rate: Float, pitch: Float) {
        if (!ready.await()) return
        tts.language = locale
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        pickBestVoice(locale)?.let { tts.voice = it }
    }

    private fun pickBestVoice(locale: Locale): TtsVoice? = runCatching {
        tts.voices
            ?.filter { it.locale.language == locale.language && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            ?.sortedWith(
                compareByDescending<TtsVoice> { it.locale.country == locale.country }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
            )
            ?.firstOrNull()
    }.getOrNull()

    /**
     * Speak and suspend until finished.
     * @param duck true = lower the music underneath; false = pause the music for the duration.
     */
    suspend fun say(text: String, duck: Boolean = false) {
        if (text.isBlank() || !ready.await()) return
        val focus = AudioFocusRequest.Builder(
            if (duck) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        ).setAudioAttributes(attrs).build()
        audio.requestAudioFocus(focus)
        try {
            val id = UUID.randomUUID().toString()
            val done = CompletableDeferred<Unit>()
            synchronized(pending) { pending[id] = done }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            // Safety net in case the engine never reports completion.
            withTimeoutOrNull(5_000L + text.length * 120L) { done.await() }
        } finally {
            audio.abandonAudioFocusRequest(focus)
        }
    }

    fun stop() { tts.stop() }

    fun shutdown() { tts.shutdown() }
}

/** Hands-free listening through the platform speech recognizer. */
class Ears(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())

    fun available() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Beep, listen for one utterance, and return the transcript (or null on silence / error). */
    suspend fun listen(language: Locale): String? {
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
                        })
                    }
                }
                cont.invokeOnCancellation { main.post { recognizer?.destroy(); recognizer = null } }
            }
        }
    }
}
