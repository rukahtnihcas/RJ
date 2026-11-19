package com.rj.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Tokens for a signed-in Microsoft 365 / Outlook.com account. Persisted by the app. */
data class MsTokens(val accessToken: String, val refreshToken: String, val expiresAt: Instant) {
    fun expired(now: Instant = Instant.now()) = now.isAfter(expiresAt.minusSeconds(120))
}

/**
 * Outlook calendar and mail through Microsoft Graph, signed in with the device-code flow
 * (the phone shows a short code; you enter it at microsoft.com/devicelogin on any device).
 * Needs a free Azure "app registration" client ID with public client flows enabled (see README).
 */
class MicrosoftGraph(
    private val clientId: String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(20)).build(),
    private val loginBase: String = "https://login.microsoftonline.com/common/oauth2/v2.0",
    private val graphBase: String = "https://graph.microsoft.com/v1.0",
) {
    private val mapper = ObjectMapper()

    data class DeviceCode(val userCode: String, val deviceCode: String, val verificationUri: String, val intervalSec: Int, val expiresInSec: Int)

    sealed interface Poll {
        data object Pending : Poll
        data class Done(val tokens: MsTokens) : Poll
        data class Failed(val reason: String) : Poll
    }

    private fun post(url: String, form: Map<String, String>): JsonNode {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
            return mapper.readTree(r.body!!.string())
        }
    }

    fun startDeviceLogin(): DeviceCode {
        val j = post("$loginBase/devicecode", mapOf("client_id" to clientId, "scope" to SCOPES))
        j.path("error").takeIf { !it.isMissingNode }?.let { error(j.path("error_description").asText(it.asText())) }
        return DeviceCode(
            userCode = j["user_code"].asText(),
            deviceCode = j["device_code"].asText(),
            verificationUri = j.path("verification_uri").asText("https://microsoft.com/devicelogin"),
            intervalSec = j.path("interval").asInt(5),
            expiresInSec = j.path("expires_in").asInt(900),
        )
    }

    fun pollDeviceLogin(code: DeviceCode): Poll {
        val j = post("$loginBase/token", mapOf(
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            "client_id" to clientId,
            "device_code" to code.deviceCode,
        ))
        return when (val err = j.path("error").asText("")) {
            "" -> Poll.Done(tokens(j, previousRefresh = null))
            "authorization_pending", "slow_down" -> Poll.Pending
            else -> Poll.Failed(j.path("error_description").asText(err))
        }
    }

    fun refresh(t: MsTokens): MsTokens {
        val j = post("$loginBase/token", mapOf(
            "grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to t.refreshToken, "scope" to SCOPES,
        ))
        if (j.has("error")) error(j.path("error_description").asText(j["error"].asText()))
        return tokens(j, t.refreshToken)
    }

    private fun tokens(j: JsonNode, previousRefresh: String?) = MsTokens(
        accessToken = j["access_token"].asText(),
        refreshToken = j.path("refresh_token").asText(previousRefresh ?: ""),
        expiresAt = Instant.now().plusSeconds(j.path("expires_in").asLong(3600)),
    )

    private fun get(t: MsTokens, url: String): JsonNode {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${t.accessToken}")
            .header("Prefer", "outlook.timezone=\"UTC\"")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("Graph HTTP ${r.code}")
            return mapper.readTree(r.body!!.string())
        }
    }

    /** Today's (and tomorrow morning's) Outlook events in the driver's zone. */
    fun calendar(t: MsTokens, now: ZonedDateTime): List<CalendarEvent> {
        val from = now.minusHours(2).toInstant()
        val to = now.toLocalDate().plusDays(1).atTime(12, 0).atZone(now.zone).toInstant()
        val url = "$graphBase/me/calendarView".toHttpUrl().newBuilder()
            .addQueryParameter("startDateTime", from.toString())
            .addQueryParameter("endDateTime", to.toString())
            .addQueryParameter("\$select", "subject,start,end,location,organizer,isAllDay,isCancelled,showAs")
            .addQueryParameter("\$orderby", "start/dateTime")
            .addQueryParameter("\$top", "25")
            .build().toString()
        return parseEvents(get(t, url), now)
    }

    /** Recent unread inbox email (sender + subject + preview). */
    fun unreadMail(t: MsTokens, since: Instant, max: Int = 10): List<InboxItem> {
        val url = "$graphBase/me/mailFolders/inbox/messages".toHttpUrl().newBuilder()
            .addQueryParameter("\$filter", "receivedDateTime ge ${DateTimeFormatter.ISO_INSTANT.format(since)} and isRead eq false")
            .addQueryParameter("\$orderby", "receivedDateTime desc")
            .addQueryParameter("\$select", "id,subject,from,receivedDateTime,importance,bodyPreview,inferenceClassification")
            .addQueryParameter("\$top", max.toString())
            .build().toString()
        return parseMail(get(t, url))
    }

    fun sendMail(t: MsTokens, to: String, subject: String, body: String) {
        val payload = mapper.writeValueAsString(mapOf(
            "message" to mapOf(
                "subject" to subject,
                "body" to mapOf("contentType" to "Text", "content" to body),
                "toRecipients" to listOf(mapOf("emailAddress" to mapOf("address" to to))),
            ),
            "saveToSentItems" to true,
        ))
        val req = Request.Builder().url("$graphBase/me/sendMail")
            .header("Authorization", "Bearer ${t.accessToken}")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { r -> if (!r.isSuccessful) error("sendMail HTTP ${r.code}") }
    }

    companion object {
        const val SCOPES = "offline_access User.Read Calendars.Read Mail.Read Mail.Send"

        private fun utc(n: JsonNode): Instant? = n.path("dateTime").asText("").takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalDateTime.parse(it.take(19)).toInstant(ZoneOffset.UTC) }.getOrNull()
        }

        fun parseEvents(root: JsonNode, now: ZonedDateTime): List<CalendarEvent> = root.path("value").mapNotNull { e ->
            if (e.path("isCancelled").asBoolean(false)) return@mapNotNull null
            if (e.path("showAs").asText("") == "free" && e.path("isAllDay").asBoolean(false)) return@mapNotNull null
            val start = utc(e.path("start")) ?: return@mapNotNull null
            val end = utc(e.path("end")) ?: start
            val org = e.path("organizer").path("emailAddress")
            CalendarEvent(
                title = e.path("subject").asText("(untitled)").ifBlank { "(untitled)" },
                start = start.atZone(now.zone),
                end = end.atZone(now.zone),
                location = e.path("location").path("displayName").asText("").ifBlank { null },
                allDay = e.path("isAllDay").asBoolean(false),
                organizer = org.path("name").asText("").ifBlank { null },
                organizerEmail = org.path("address").asText("").ifBlank { null },
                source = "outlook",
            )
        }

        fun parseMail(root: JsonNode): List<InboxItem> = root.path("value").mapNotNull { m ->
            // Skip Outlook's "Other" (non-focused) mail: newsletters, promos.
            if (m.path("inferenceClassification").asText("focused") == "other") return@mapNotNull null
            val at = runCatching { Instant.parse(m.path("receivedDateTime").asText()) }.getOrNull() ?: return@mapNotNull null
            val from = m.path("from").path("emailAddress")
            val subject = m.path("subject").asText("").ifBlank { "(no subject)" }
            val preview = m.path("bodyPreview").asText("").replace(Regex("\\s+"), " ").take(140)
            InboxItem(
                id = "outlook:" + m.path("id").asText(subject),
                kind = InboxKind.EMAIL,
                app = "Outlook",
                from = from.path("name").asText("").ifBlank { from.path("address").asText("someone") },
                text = "$subject${if (preview.isNotBlank()) " — $preview" else ""}",
                at = at.atZone(ZoneOffset.UTC),
                vip = m.path("importance").asText("") == "high",
            )
        }
    }
}
