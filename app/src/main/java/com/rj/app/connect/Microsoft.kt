package com.rj.app.connect

import com.rj.app.data.SettingsStore
import com.rj.core.CalendarEvent
import com.rj.core.InboxItem
import com.rj.core.MicrosoftGraph
import com.rj.core.MsTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZonedDateTime

/** Outlook calendar + mail for the show, with token refresh and the device-code sign-in. */
class MicrosoftAccount(private val store: SettingsStore) {
    private fun graph(): MicrosoftGraph? = store.load().msClientId.takeIf { it.isNotBlank() }?.let { MicrosoftGraph(it) }

    val signedIn: Boolean get() = store.msTokens() != null

    /** Sign in: [onCode] shows the code to type at microsoft.com/devicelogin; suspends until done. */
    suspend fun signIn(onCode: (MicrosoftGraph.DeviceCode) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val g = graph() ?: error("Add your Azure app (client) ID first")
            val code = g.startDeviceLogin()
            withContext(Dispatchers.Main) { onCode(code) }
            val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(code.intervalSec * 1000L)
                when (val p = g.pollDeviceLogin(code)) {
                    is MicrosoftGraph.Poll.Done -> { store.saveMsTokens(p.tokens, "Microsoft account"); return@runCatching }
                    is MicrosoftGraph.Poll.Failed -> error(p.reason)
                    MicrosoftGraph.Poll.Pending -> Unit
                }
            }
            error("Sign-in timed out")
        }
    }

    fun signOut() = store.saveMsTokens(null)

    private fun tokens(): Pair<MicrosoftGraph, MsTokens>? {
        val g = graph() ?: return null
        var t = store.msTokens() ?: return null
        if (t.expired()) {
            t = runCatching { g.refresh(t) }.getOrElse { return null }
            store.saveMsTokens(t)
        }
        return g to t
    }

    // Blocking calls: use from Dispatchers.IO.
    fun calendar(now: ZonedDateTime): List<CalendarEvent> =
        tokens()?.let { (g, t) -> runCatching { g.calendar(t, now) }.getOrNull() }.orEmpty()

    fun unreadMail(since: Instant): List<InboxItem> =
        tokens()?.let { (g, t) -> runCatching { g.unreadMail(t, since) }.getOrNull() }.orEmpty()

    fun sendMail(to: String, subject: String, body: String): Boolean =
        tokens()?.let { (g, t) -> runCatching { g.sendMail(t, to, subject, body) }.isSuccess } ?: false
}
