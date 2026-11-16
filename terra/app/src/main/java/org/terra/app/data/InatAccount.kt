package org.terra.app.data

import android.content.Context
import org.terra.app.BuildConfig
import org.terra.app.domain.Community
import java.security.SecureRandom
import java.util.Base64

/**
 * The player's link to iNaturalist. After one sign-in the app holds a lasting access token and
 * quietly renews the short-lived API token itself, so sharing never asks again.
 * A pasted 24-hour token remains as the fallback when no sign-in application is configured.
 */
class InatAccount(context: Context) {
    private val prefs = context.getSharedPreferences("terra", Context.MODE_PRIVATE)
    val clientId: String = BuildConfig.INAT_CLIENT_ID
    val canSignIn get() = clientId.isNotBlank()
    val signedIn get() = prefs.contains("inat_access")
    var pastedToken: String
        get() = prefs.getString("inat_token", "").orEmpty()
        set(v) = prefs.edit().putString("inat_token", v).apply()
    var autoShare: Boolean
        get() = prefs.getBoolean("auto_share", false)
        set(v) = prefs.edit().putBoolean("auto_share", v).apply()
    var autoSync: Boolean
        get() = prefs.getBoolean("auto_sync", true)
        set(v) = prefs.edit().putBoolean("auto_sync", v).apply()
    val ready get() = signedIn || pastedToken.isNotBlank()

    /** Starts a sign-in: remembers a fresh PKCE verifier and returns the page to open. */
    fun beginSignIn(): String {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        prefs.edit().putString("inat_verifier", verifier).apply()
        return Community.inatAuthorizeUrl(clientId, Community.pkceChallenge(verifier))
    }

    suspend fun finishSignIn(code: String): Boolean = runCatching {
        val verifier = prefs.getString("inat_verifier", null) ?: return false
        val access = CommunityApi.inatExchange(clientId, code, verifier)
        prefs.edit().putString("inat_access", access).remove("inat_verifier").remove("inat_api").apply()
        true
    }.getOrDefault(false)

    fun signOut() = prefs.edit().remove("inat_access").remove("inat_api").remove("inat_api_at").apply()

    /** A token the API will accept right now, renewed silently when signed in. Null if the player has no link. */
    suspend fun apiToken(forceFresh: Boolean = false): String? {
        val access = prefs.getString("inat_access", null) ?: return pastedToken.ifBlank { null }
        val cached = prefs.getString("inat_api", null)
        val age = System.currentTimeMillis() - prefs.getLong("inat_api_at", 0)
        if (!forceFresh && cached != null && age < 20 * 3_600_000L) return cached
        val fresh = CommunityApi.inatApiToken(access)
        prefs.edit().putString("inat_api", fresh).putLong("inat_api_at", System.currentTimeMillis()).apply()
        return fresh
    }
}
