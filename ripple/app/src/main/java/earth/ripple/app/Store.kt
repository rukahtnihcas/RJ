package earth.ripple.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import earth.ripple.core.Provider
import earth.ripple.core.Assessment
import earth.ripple.core.Profile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Entry(
    val key: String,
    val time: Long,
    val thumb: String = "",
    val assessment: Assessment,
    /** Other library entries the camera's labels pointed to, offered when the first guess is wrong. */
    val candidates: List<String> = emptyList(),
)

data class Settings(
    /** Which AI service refines scans. Free Kimi by default. */
    val provider: String = Provider.KIMI.id,
    /** API keys, one per service, so switching back and forth doesn't lose them. */
    val keys: Map<String, String> = emptyMap(),
    /** Model names per service; blank means the service's default. */
    val models: Map<String, String> = emptyMap(),
    /** Base URL for "Other OpenAI-compatible service". */
    val customUrl: String = "",
    val autoLock: Boolean = true,
    val guessFirst: Boolean = false,
) {
    val active: Provider get() = Provider.byId(provider)
    val apiKey: String get() = keys[provider].orEmpty()
    val model: String get() = models[provider]?.takeIf { it.isNotBlank() } ?: active.defaultModel
    val baseUrl: String get() = if (active == Provider.CUSTOM) customUrl.trim() else active.baseUrl
    val aiEnabled: Boolean get() = apiKey.isNotBlank() && model.isNotBlank() && (active.isClaude || baseUrl.isNotBlank())
}

/** Everything the app remembers, kept as small JSON files in private storage. */
class Store(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val profileFile = File(context.filesDir, "profile.json")
    private val journalFile = File(context.filesDir, "journal.json")
    private val thumbs = File(context.filesDir, "thumbs").apply { mkdirs() }
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The API key lives in encrypted storage. If the keystore is unavailable it is kept in memory only. */
    private val secret: SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create(
            "secret", MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()
    private val memoryKeys = mutableMapOf<String, String>()

    fun loadProfile(): Profile =
        runCatching { json.decodeFromString(Profile.serializer(), profileFile.readText()) }.getOrDefault(Profile())

    fun saveProfile(p: Profile) = write(profileFile, json.encodeToString(Profile.serializer(), p))

    fun loadJournal(): List<Entry> =
        runCatching { json.decodeFromString(ListSerializer(Entry.serializer()), journalFile.readText()) }.getOrDefault(emptyList())

    fun saveJournal(entries: List<Entry>) {
        val kept = entries.take(MAX_ENTRIES)
        entries.drop(MAX_ENTRIES).forEach { if (it.thumb.isNotEmpty()) File(it.thumb).delete() }
        write(journalFile, json.encodeToString(ListSerializer(Entry.serializer()), kept))
    }

    fun saveThumb(key: String, bitmap: Bitmap): String {
        val file = File(thumbs, "$key.jpg")
        runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
        return file.absolutePath
    }

    fun loadSettings(): Settings {
        // Earlier versions stored a single Claude key and model.
        val legacyKey = secret?.getString("api_key", "").orEmpty()
        val keys = Provider.all.associate { p -> p.id to (secret?.getString("key_${p.id}", null) ?: memoryKeys[p.id] ?: "") }
            .toMutableMap().also { if (it[Provider.CLAUDE.id].isNullOrBlank() && legacyKey.isNotBlank()) it[Provider.CLAUDE.id] = legacyKey }
        val models = Provider.all.associate { p -> p.id to (prefs.getString("model_${p.id}", null) ?: "") }
            .toMutableMap().also { m -> prefs.getString("model", null)?.takeIf { it.isNotBlank() && m[Provider.CLAUDE.id].isNullOrBlank() }?.let { m[Provider.CLAUDE.id] = it } }
        val provider = prefs.getString("provider", null) ?: if (legacyKey.isNotBlank()) Provider.CLAUDE.id else Provider.KIMI.id
        return Settings(
            provider = provider, keys = keys, models = models,
            customUrl = prefs.getString("custom_url", "").orEmpty(),
            autoLock = prefs.getBoolean("auto_lock", true),
            guessFirst = prefs.getBoolean("guess_first", false),
        )
    }

    fun saveSettings(s: Settings) {
        memoryKeys.putAll(s.keys)
        secret?.edit()?.apply { s.keys.forEach { (id, k) -> putString("key_$id", k) } }?.apply()
        prefs.edit().apply {
            s.models.forEach { (id, m) -> putString("model_$id", m) }
            putString("provider", s.provider).putString("custom_url", s.customUrl)
            putBoolean("auto_lock", s.autoLock).putBoolean("guess_first", s.guessFirst)
        }.apply()
    }

    private fun write(file: File, text: String) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }

    companion object { const val MAX_ENTRIES = 150 }
}
