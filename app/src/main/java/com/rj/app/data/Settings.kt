package com.rj.app.data

import android.content.Context
import com.rj.core.ClaudeBrain
import com.rj.core.Persona
import com.rj.core.UserProfile
import org.json.JSONArray

/** Music apps the RJ knows how to drive via play-from-search. */
enum class MusicApp(val label: String, val packageName: String?) {
    SPOTIFY("Spotify", "com.spotify.music"),
    YT_MUSIC("YouTube Music", "com.google.android.apps.youtube.music"),
    APPLE_MUSIC("Apple Music", "com.apple.android.music"),
    AMAZON_MUSIC("Amazon Music", "com.amazon.mp3"),
    ANY("Any app", null),
}

/** Where songs come from. */
enum class MusicSource(val label: String) {
    AUTO("Auto"),               // streaming app when online, phone library when offline / if streaming fails
    LIBRARY("Phone library"),   // built-in player, works offline, exact control
    STREAMING("Streaming app"),
}

enum class VoiceEngine(val label: String) {
    DEVICE("On-device"),
    ELEVENLABS("ElevenLabs (natural)"),
}

enum class BrainMode(val label: String) {
    AUTO("Auto"),          // Claude when online, offline host when not
    OFFLINE("Always offline"),
}

data class AppSettings(
    val apiKey: String = "",
    val model: String = ClaudeBrain.DEFAULT_MODEL,
    val brainMode: BrainMode = BrainMode.AUTO,
    val persona: Persona = Persona(),
    val profile: UserProfile = UserProfile(),
    val musicSource: MusicSource = MusicSource.AUTO,
    val musicApp: MusicApp = MusicApp.SPOTIFY,
    /** Fallback gap between links when we can't see the music app's track progress. */
    val linkEveryMinutes: Int = 4,
    val voiceEngine: VoiceEngine = VoiceEngine.DEVICE,
    val elevenLabsKey: String = "",
    val hostVoiceId: String = DEFAULT_HOST_VOICE,
    val cohostVoiceId: String = DEFAULT_COHOST_VOICE,
) {
    companion object {
        // ElevenLabs premade voices; any voice ID from your ElevenLabs voice library works.
        const val DEFAULT_HOST_VOICE = "EXAVITQu4vr4xnJjD0NA"
        const val DEFAULT_COHOST_VOICE = "pNInz6obpgDQGcFmaJgB"
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("rj_settings", Context.MODE_PRIVATE)

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        runCatching { enumValueOf<E>(prefs.getString(key, null) ?: return default) }.getOrDefault(default)

    fun load(): AppSettings = with(prefs) {
        val d = AppSettings()
        AppSettings(
            apiKey = getString("apiKey", "")!!,
            model = getString("model", d.model)!!.ifBlank { d.model },
            brainMode = enumOf("brainMode", d.brainMode),
            persona = Persona(
                carName = getString("carName", d.persona.carName)!!,
                style = enumOf("style", d.persona.style),
                quirks = getString("quirks", "")!!,
                cohostName = getString("cohostName", d.persona.cohostName)!!.ifBlank { d.persona.cohostName },
                cohostVibe = getString("cohostVibe", d.persona.cohostVibe)!!.ifBlank { d.persona.cohostVibe },
            ),
            profile = UserProfile(
                name = getString("name", "")!!,
                homeCity = getString("homeCity", "")!!,
                interests = getString("interests", "")!!.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                musicTaste = getString("musicTaste", "")!!,
                keyStuff = getString("keyStuff", "")!!,
                language = getString("language", "English")!!.ifBlank { "English" },
            ),
            musicSource = enumOf("musicSource", d.musicSource),
            musicApp = enumOf("musicApp", d.musicApp),
            linkEveryMinutes = getInt("linkEveryMinutes", d.linkEveryMinutes),
            voiceEngine = enumOf("voiceEngine", d.voiceEngine),
            elevenLabsKey = getString("elevenLabsKey", "")!!,
            hostVoiceId = getString("hostVoiceId", d.hostVoiceId)!!.ifBlank { d.hostVoiceId },
            cohostVoiceId = getString("cohostVoiceId", d.cohostVoiceId)!!.ifBlank { d.cohostVoiceId },
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("apiKey", s.apiKey.trim())
            .putString("model", s.model.trim())
            .putString("brainMode", s.brainMode.name)
            .putString("carName", s.persona.carName)
            .putString("style", s.persona.style.name)
            .putString("quirks", s.persona.quirks)
            .putString("cohostName", s.persona.cohostName)
            .putString("cohostVibe", s.persona.cohostVibe)
            .putString("name", s.profile.name)
            .putString("homeCity", s.profile.homeCity)
            .putString("interests", s.profile.interests.joinToString(", "))
            .putString("musicTaste", s.profile.musicTaste)
            .putString("keyStuff", s.profile.keyStuff)
            .putString("language", s.profile.language)
            .putString("musicSource", s.musicSource.name)
            .putString("musicApp", s.musicApp.name)
            .putInt("linkEveryMinutes", s.linkEveryMinutes)
            .putString("voiceEngine", s.voiceEngine.name)
            .putString("elevenLabsKey", s.elevenLabsKey.trim())
            .putString("hostVoiceId", s.hostVoiceId.trim())
            .putString("cohostVoiceId", s.cohostVoiceId.trim())
            .apply()
    }
}

/** Long-term facts the RJ has learned about the driver across drives. */
class MemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("rj_memory", Context.MODE_PRIVATE)

    fun all(): List<String> {
        val arr = JSONArray(prefs.getString("items", "[]"))
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun add(items: List<String>) {
        if (items.isEmpty()) return
        val merged = (all() + items).map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.takeLast(MAX)
        prefs.edit().putString("items", JSONArray(merged).toString()).apply()
    }

    fun clear() = prefs.edit().remove("items").apply()

    private companion object { const val MAX = 60 }
}

/** Running totals that make the RJ feel like a companion ("drive number 23, new quiz record!"). */
class DriveStats(context: Context) {
    private val prefs = context.getSharedPreferences("rj_stats", Context.MODE_PRIVATE)

    val drives get() = prefs.getInt("drives", 0)
    val totalKm get() = prefs.getFloat("km", 0f).toDouble()
    val bestQuizStreak get() = prefs.getInt("bestQuiz", 0)
    val quizCorrect get() = prefs.getInt("quizCorrect", 0)
    val quizAsked get() = prefs.getInt("quizAsked", 0)

    fun startDrive(): Int {
        val n = drives + 1
        prefs.edit().putInt("drives", n).apply()
        return n
    }

    fun endDrive(km: Double) = prefs.edit().putFloat("km", (totalKm + km).toFloat()).apply()

    /** Returns true if this round set a new record. */
    fun recordQuiz(correct: Int, asked: Int): Boolean {
        val record = correct > bestQuizStreak
        prefs.edit()
            .putInt("quizCorrect", quizCorrect + correct)
            .putInt("quizAsked", quizAsked + asked)
            .apply { if (record) putInt("bestQuiz", correct) }
            .apply()
        return record
    }

    fun summary(): List<String> = buildList {
        if (drives > 1) add("drive number $drives together")
        if (totalKm >= 1) add("${totalKm.toInt()} km driven together so far")
        if (quizAsked > 0) add("lifetime quiz score $quizCorrect out of $quizAsked, best round $bestQuizStreak")
    }
}
