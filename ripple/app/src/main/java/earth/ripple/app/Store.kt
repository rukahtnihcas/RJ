package earth.ripple.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import earth.ripple.core.AiProtocol
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
)

data class Settings(
    val apiKey: String = "",
    val model: String = AiProtocol.DEFAULT_MODEL,
    val autoLock: Boolean = true,
    val guessFirst: Boolean = false,
) {
    val aiEnabled: Boolean get() = apiKey.isNotBlank()
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
    private var memoryKey = ""

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

    fun loadSettings(): Settings = Settings(
        apiKey = secret?.getString("api_key", "") ?: memoryKey,
        model = prefs.getString("model", null)?.takeIf { it.isNotBlank() } ?: AiProtocol.DEFAULT_MODEL,
        autoLock = prefs.getBoolean("auto_lock", true),
        guessFirst = prefs.getBoolean("guess_first", false),
    )

    fun saveSettings(s: Settings) {
        memoryKey = s.apiKey
        secret?.edit()?.putString("api_key", s.apiKey)?.apply()
        prefs.edit().putString("model", s.model).putBoolean("auto_lock", s.autoLock).putBoolean("guess_first", s.guessFirst).apply()
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
