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

/** Accent for both voice engines; also steers the RJ's wording. */
enum class VoiceAccent(val label: String, val localeTag: String, val dialect: String) {
    BRITISH("British", "en-GB", "British English"),
    AMERICAN("American", "en-US", "American English"),
    INDIAN("Indian", "en-IN", "Indian English"),
    AUSTRALIAN("Australian", "en-AU", "Australian English"),
    DEVICE("Phone default", "", ""),
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
    val accent: VoiceAccent = VoiceAccent.BRITISH,
    /** Lower, richer on-device voice. */
    val deepVoice: Boolean = true,

    // --- Connections ---
    /** Announce WhatsApp / SMS / email / car-app notifications during the show. */
    val announceMessages: Boolean = true,
    /** Send message snippets to the AI so it can summarise them (otherwise just who/which app). */
    val shareMessageText: Boolean = false,
    /** Comma-separated names whose messages are announced straight away. */
    val vipNames: String = "",
    val useHealth: Boolean = true,
    val tomTomKey: String = "",
    /** Azure app registration (public client) ID for Outlook calendar & mail. */
    val msClientId: String = "",
    /** Bluetooth MAC of an ELM327 OBD-II adapter. */
    val obdAddress: String = "",
    val obdName: String = "",
    /** Name of the car's Bluetooth, learned automatically; used to offer "go on air" when you get in. */
    val carBluetoothName: String = "",
) {
    val vipList: List<String> get() = vipNames.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        // ElevenLabs premade voices; any voice ID from your ElevenLabs voice library works.
        const val DEFAULT_HOST_VOICE = "onwK4e9ZLuTAKqWW03F9"   // "Daniel": deep, British, broadcaster
        const val DEFAULT_COHOST_VOICE = "Xb7hH8MSUJpSbSDYk0k2" // "Alice": clear, British
        /** Earlier defaults, migrated to the British ones above. */
        val OLD_DEFAULTS = setOf("EXAVITQu4vr4xnJjD0NA", "pNInz6obpgDQGcFmaJgB")
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
            hostVoiceId = getString("hostVoiceId", d.hostVoiceId)!!.ifBlank { d.hostVoiceId }
                .let { if (it in AppSettings.OLD_DEFAULTS) d.hostVoiceId else it },
            cohostVoiceId = getString("cohostVoiceId", d.cohostVoiceId)!!.ifBlank { d.cohostVoiceId }
                .let { if (it in AppSettings.OLD_DEFAULTS) d.cohostVoiceId else it },
            accent = enumOf("accent", d.accent),
            deepVoice = getBoolean("deepVoice", d.deepVoice),
            announceMessages = getBoolean("announceMessages", d.announceMessages),
            shareMessageText = getBoolean("shareMessageText", d.shareMessageText),
            vipNames = getString("vipNames", "")!!,
            useHealth = getBoolean("useHealth", d.useHealth),
            tomTomKey = getString("tomTomKey", "")!!,
            msClientId = getString("msClientId", "")!!,
            obdAddress = getString("obdAddress", "")!!,
            obdName = getString("obdName", "")!!,
            carBluetoothName = getString("carBluetoothName", "")!!,
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
            .putString("accent", s.accent.name)
            .putBoolean("deepVoice", s.deepVoice)
            .putBoolean("announceMessages", s.announceMessages)
            .putBoolean("shareMessageText", s.shareMessageText)
            .putString("vipNames", s.vipNames)
            .putBoolean("useHealth", s.useHealth)
            .putString("tomTomKey", s.tomTomKey.trim())
            .putString("msClientId", s.msClientId.trim())
            .putString("obdAddress", s.obdAddress)
            .putString("obdName", s.obdName)
            .putString("carBluetoothName", s.carBluetoothName)
            .apply()
    }

    /** Learned automatically; saved without touching anything else. */
    fun saveCarBluetooth(name: String) = prefs.edit().putString("carBluetoothName", name).apply()

    /** Microsoft 365 tokens (kept separate from the rest of the settings). */
    fun msTokens(): com.rj.core.MsTokens? {
        val at = prefs.getString("msAccess", null) ?: return null
        val rt = prefs.getString("msRefresh", null) ?: return null
        return com.rj.core.MsTokens(at, rt, java.time.Instant.ofEpochSecond(prefs.getLong("msExpires", 0)))
    }

    fun msAccount(): String? = prefs.getString("msAccount", null)

    fun saveMsTokens(t: com.rj.core.MsTokens?, account: String? = msAccount()) {
        prefs.edit().apply {
            if (t == null) { remove("msAccess"); remove("msRefresh"); remove("msExpires"); remove("msAccount") }
            else {
                putString("msAccess", t.accessToken); putString("msRefresh", t.refreshToken)
                putLong("msExpires", t.expiresAt.epochSecond); account?.let { putString("msAccount", it) }
            }
        }.apply()
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

    /** Learn how long trips to each destination usually take (simple running average). */
    fun recordTrip(destination: String?, minutes: Long) {
        val key = destination?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && minutes >= 3 } ?: return
        val trips = org.json.JSONObject(prefs.getString("trips", "{}")!!)
        val t = trips.optJSONObject(key) ?: org.json.JSONObject().put("avg", minutes).put("n", 0)
        val n = t.getInt("n")
        t.put("avg", (t.getDouble("avg") * n + minutes) / (n + 1)).put("n", (n + 1).coerceAtMost(20))
        trips.put(key, t)
        prefs.edit().putString("trips", trips.toString()).apply()
    }

    fun usualTripMinutes(destination: String?): Int? {
        val key = destination?.trim()?.lowercase() ?: return null
        val t = org.json.JSONObject(prefs.getString("trips", "{}")!!).optJSONObject(key) ?: return null
        return if (t.getInt("n") >= 2) t.getDouble("avg").toInt() else null
    }

    fun summary(): List<String> = buildList {
        if (drives > 1) add("drive number $drives together")
        if (totalKm >= 1) add("${totalKm.toInt()} km driven together so far")
        if (quizAsked > 0) add("lifetime quiz score $quizCorrect out of $quizAsked, best round $bestQuizStreak")
    }
}
