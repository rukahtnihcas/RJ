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
    ANY("Whatever's installed", null),
}

data class AppSettings(
    val apiKey: String = "",
    val model: String = ClaudeBrain.DEFAULT_MODEL,
    val persona: Persona = Persona(),
    val profile: UserProfile = UserProfile(),
    val musicApp: MusicApp = MusicApp.SPOTIFY,
    /** Fallback gap between links when we can't see the music app's track progress. */
    val linkEveryMinutes: Int = 4,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("rj_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = with(prefs) {
        AppSettings(
            apiKey = getString("apiKey", "")!!,
            model = getString("model", ClaudeBrain.DEFAULT_MODEL)!!.ifBlank { ClaudeBrain.DEFAULT_MODEL },
            persona = Persona(
                carName = getString("carName", "Nova")!!,
                style = runCatching { Persona.Style.valueOf(getString("style", "")!!) }.getOrDefault(Persona.Style.MORNING_HOST),
                quirks = getString("quirks", "")!!,
            ),
            profile = UserProfile(
                name = getString("name", "")!!,
                homeCity = getString("homeCity", "")!!,
                interests = getString("interests", "")!!.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                musicTaste = getString("musicTaste", "")!!,
                keyStuff = getString("keyStuff", "")!!,
                language = getString("language", "English")!!.ifBlank { "English" },
            ),
            musicApp = runCatching { MusicApp.valueOf(getString("musicApp", "")!!) }.getOrDefault(MusicApp.SPOTIFY),
            linkEveryMinutes = getInt("linkEveryMinutes", 4),
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("apiKey", s.apiKey.trim())
            .putString("model", s.model.trim())
            .putString("carName", s.persona.carName)
            .putString("style", s.persona.style.name)
            .putString("quirks", s.persona.quirks)
            .putString("name", s.profile.name)
            .putString("homeCity", s.profile.homeCity)
            .putString("interests", s.profile.interests.joinToString(", "))
            .putString("musicTaste", s.profile.musicTaste)
            .putString("keyStuff", s.profile.keyStuff)
            .putString("language", s.profile.language)
            .putString("musicApp", s.musicApp.name)
            .putInt("linkEveryMinutes", s.linkEveryMinutes)
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
