package com.rj.app.connect

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.rj.core.InboxItem
import com.rj.core.InboxKind
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Collects chat messages, emails and car-app alerts from notifications (WhatsApp, SMS, Outlook,
 * Gmail, Teams, Tata iRA and other connected-car apps...), keeping each sender's reply action so the
 * driver can answer by voice. Fed by [com.rj.app.music.MediaWatcher], which needs Notification access.
 */
object Inbox {
    data class Entry(
        val key: String,
        val kind: InboxKind,
        val app: String,
        val from: String,
        val text: String,
        val at: ZonedDateTime,
        val count: Int,
        val reply: Notification.Action?,
    )

    private val apps = mapOf(
        "com.whatsapp" to ("WhatsApp" to InboxKind.MESSAGE),
        "com.whatsapp.w4b" to ("WhatsApp Business" to InboxKind.MESSAGE),
        "com.google.android.apps.messaging" to ("Messages" to InboxKind.MESSAGE),
        "com.samsung.android.messaging" to ("Messages" to InboxKind.MESSAGE),
        "org.telegram.messenger" to ("Telegram" to InboxKind.MESSAGE),
        "org.thoughtcrime.securesms" to ("Signal" to InboxKind.MESSAGE),
        "com.facebook.orca" to ("Messenger" to InboxKind.MESSAGE),
        "com.microsoft.teams" to ("Teams" to InboxKind.MESSAGE),
        "com.Slack" to ("Slack" to InboxKind.MESSAGE),
        "com.microsoft.office.outlook" to ("Outlook" to InboxKind.EMAIL),
        "com.google.android.gm" to ("Gmail" to InboxKind.EMAIL),
        "com.samsung.android.email.provider" to ("Email" to InboxKind.EMAIL),
    )

    /** Package-name fragments of connected-car apps (Tata iRA, Hyundai Bluelink, MG iSmart, ...). */
    private val carHints = listOf("tata", "ira", "bluelink", "kiaconnect", "ismart", "mahindra", "bluesense",
        "suzukiconnect", "fordpass", "myhyundai", "toyota", "tesla", "mercedesme", "mybmw", "skoda", "volkswagen")

    private val entries = LinkedHashMap<String, Entry>()
    @Volatile var enabled = true
    @Volatile var vips: List<String> = emptyList()

    fun classify(pkg: String): Pair<String, InboxKind>? {
        apps[pkg]?.let { return it }
        val segments = pkg.lowercase().split('.')
        val car = carHints.any { h -> segments.any { it == h || it.startsWith(h) } }
        return if (car) ((if ("tata" in pkg || segments.any { it.startsWith("ira") }) "iRA" else "Car app") to InboxKind.VEHICLE) else null
    }

    fun onPosted(sbn: StatusBarNotification, ownPackage: String) {
        if (!enabled || sbn.packageName == ownPackage || sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val (app, kind) = classify(sbn.packageName) ?: return
        val x = n.extras
        val conversation = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        // WhatsApp-style "3 new messages" summaries carry no content.
        if (Regex("^\\d+ (new )?messages?( from \\d+ chats?)?$").matches(text.lowercase())) return
        val from = when {
            kind == InboxKind.VEHICLE -> app
            conversation != null && conversation != title -> "$title in $conversation"
            else -> title.ifEmpty { app }
        }
        val reply = n.actions?.firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true &&
                (Build.VERSION.SDK_INT < 28 || a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY || a.semanticAction == 0)
        }
        val key = "${sbn.packageName}|${conversation ?: title}"
        synchronized(entries) {
            val prev = entries.remove(key)
            entries[key] = Entry(
                key = key, kind = kind, app = app, from = from,
                text = if (kind == InboxKind.VEHICLE) listOf(title, text).filter { it.isNotBlank() }.joinToString(": ") else text,
                at = Instant.ofEpochMilli(sbn.postTime).atZone(ZoneId.systemDefault()),
                count = (prev?.count ?: 0) + 1,
                reply = reply ?: prev?.reply,
            )
            while (entries.size > 40) entries.remove(entries.keys.first())
        }
    }

    /** Everything since [since], as context for the RJ. Text is withheld unless [shareText]. */
    fun items(since: ZonedDateTime, shareText: Boolean): List<InboxItem> = snapshot(since).map { e ->
        InboxItem(
            id = "${e.key}#${e.count}",
            kind = e.kind, app = e.app, from = e.from,
            // Car alerts are about the car, not private correspondence: always share.
            text = if (shareText || e.kind == InboxKind.VEHICLE) e.text.take(200) else null,
            at = e.at, canReply = e.reply != null, count = e.count,
            vip = vips.any { v -> e.from.contains(v, ignoreCase = true) },
        )
    }

    fun vehicleAlerts(since: ZonedDateTime): List<String> =
        snapshot(since).filter { it.kind == InboxKind.VEHICLE }.map { "${it.app}: ${it.text.take(120)}" }

    fun snapshot(since: ZonedDateTime): List<Entry> = synchronized(entries) { entries.values.filter { it.at.isAfter(since) } }

    /** Best match for "reply to Priya", or the latest repliable chat. */
    fun findRepliable(name: String?, since: ZonedDateTime): Entry? {
        val candidates = snapshot(since).filter { it.reply != null }.sortedByDescending { it.at }
        if (name.isNullOrBlank()) return candidates.firstOrNull()
        return candidates.firstOrNull { it.from.contains(name, ignoreCase = true) }
            ?: candidates.firstOrNull { e -> name.split(' ').any { w -> w.length > 2 && e.from.contains(w, ignoreCase = true) } }
    }

    /** Send a reply through the app's own notification action (WhatsApp, Messages, Teams, ...). */
    fun sendReply(context: Context, entry: Entry, text: String): Boolean {
        val action = entry.reply ?: return false
        val inputs = action.remoteInputs ?: return false
        return runCatching {
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(context, 0, intent)
            synchronized(entries) { entries.remove(entry.key) }
            true
        }.getOrDefault(false)
    }

    /** Forget messages once they've been read out. */
    fun markHandled(keys: Collection<String>) = synchronized(entries) { keys.forEach { entries.remove(it) } }
}
