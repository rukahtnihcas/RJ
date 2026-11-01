package com.rj.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.InputStream
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZonedDateTime
import javax.xml.parsers.DocumentBuilderFactory

private val http = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(15)).build()

/** Free, key-less weather from Open-Meteo. */
class WeatherClient(private val client: OkHttpClient = http) {
    fun fetch(lat: Double, lon: Double, place: String?): WeatherSnapshot? = runCatching {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", "%.4f".format(java.util.Locale.US, lat))
            .addQueryParameter("longitude", "%.4f".format(java.util.Locale.US, lon))
            .addQueryParameter("current", "temperature_2m,apparent_temperature,weather_code,wind_speed_10m")
            .addQueryParameter("hourly", "precipitation_probability")
            .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min,sunset")
            .addQueryParameter("forecast_days", "1")
            .addQueryParameter("timezone", "auto")
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            parse(r.body!!.string(), place)
        }
    }.getOrNull()

    companion object {
        private val mapper = ObjectMapper()

        fun parse(json: String, place: String?): WeatherSnapshot {
            val root = mapper.readTree(json)
            val cur = root.path("current")
            val daily = root.path("daily")
            val curTime = cur.path("time").asText("")
            return WeatherSnapshot(
                place = place,
                temperatureC = cur.path("temperature_2m").asDouble(),
                feelsLikeC = cur.path("apparent_temperature").doubleOrNull(),
                description = describe(cur.path("weather_code").asInt(-1)),
                windKph = cur.path("wind_speed_10m").doubleOrNull(),
                precipitationChanceNext3h = rainNext3h(root.path("hourly"), curTime),
                highC = daily.path("temperature_2m_max").firstDouble(),
                lowC = daily.path("temperature_2m_min").firstDouble(),
                sunset = daily.path("sunset").path(0).asText("").substringAfter('T').ifEmpty { null },
            )
        }

        private fun rainNext3h(hourly: JsonNode, curTime: String): Int? {
            val times = hourly.path("time").takeIf { it.isArray } ?: return null
            val probs = hourly.path("precipitation_probability")
            val now = runCatching { LocalDateTime.parse(curTime) }.getOrNull() ?: return null
            var best: Int? = null
            for (i in 0 until times.size()) {
                val t = runCatching { LocalDateTime.parse(times[i].asText()) }.getOrNull() ?: continue
                val hour = now.withMinute(0).withSecond(0).withNano(0)
                if (!t.isBefore(hour) && t.isBefore(hour.plusHours(3))) {
                    val p = probs.path(i)
                    if (p.isNumber) best = maxOf(best ?: 0, p.asInt())
                }
            }
            return best
        }

        private fun JsonNode.doubleOrNull() = if (isNumber) asDouble() else null
        private fun JsonNode.firstDouble() = path(0).doubleOrNull()

        /** WMO weather interpretation codes. */
        fun describe(code: Int): String = when (code) {
            0 -> "clear sky"
            1 -> "mostly clear"
            2 -> "partly cloudy"
            3 -> "overcast"
            45, 48 -> "foggy"
            51, 53, 55 -> "drizzle"
            56, 57 -> "freezing drizzle"
            61 -> "light rain"
            63 -> "rain"
            65 -> "heavy rain"
            66, 67 -> "freezing rain"
            71, 73, 75, 77 -> "snow"
            80, 81 -> "rain showers"
            82 -> "violent rain showers"
            85, 86 -> "snow showers"
            95 -> "thunderstorms"
            96, 99 -> "thunderstorms with hail"
            else -> "unknown conditions"
        }
    }
}

/** Key-less headlines from Google News RSS: top stories for the locale plus one feed per interest. */
class NewsClient(private val client: OkHttpClient = http) {
    fun fetch(language: String, country: String, interests: List<String>, perTopic: Int = 3): List<NewsItem> {
        val hl = "$language-$country"
        val ceid = "$country:$language"
        val out = mutableListOf<NewsItem>()
        get("https://news.google.com/rss?hl=$hl&gl=$country&ceid=$ceid")?.let { out += it.take(6) }
        interests.take(4).forEach { topic ->
            val url = "https://news.google.com/rss/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$topic when:1d")
                .addQueryParameter("hl", hl).addQueryParameter("gl", country).addQueryParameter("ceid", ceid)
                .build().toString()
            get(url)?.let { items -> out += items.take(perTopic).map { it.copy(topic = topic) } }
        }
        return out.distinctBy { it.title }
    }

    private fun get(url: String): List<NewsItem>? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else parseRss(r.body!!.byteStream())
        }
    }.getOrNull()

    companion object {
        fun parseRss(input: InputStream): List<NewsItem> {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            val doc = factory.newDocumentBuilder().parse(input)
            val items = doc.getElementsByTagName("item")
            return (0 until items.length).mapNotNull { i ->
                val el = items.item(i) as? Element ?: return@mapNotNull null
                val rawTitle = el.text("title") ?: return@mapNotNull null
                val source = el.text("source")
                // Google News titles look like "Headline - Source"; drop the duplicated suffix.
                val title = if (source != null) rawTitle.removeSuffix(" - $source") else rawTitle
                NewsItem(title = title.trim(), source = source)
            }
        }

        private fun Element.text(tag: String): String? =
            getElementsByTagName(tag).item(0)?.textContent?.trim()?.ifEmpty { null }
    }
}

/** Small helper so the app can refresh feeds on a cadence rather than every segment. */
class Cached<T>(private val ttl: Duration, private val load: () -> T?) {
    private var value: T? = null
    private var at: ZonedDateTime? = null

    @Synchronized
    fun get(now: ZonedDateTime = ZonedDateTime.now()): T? {
        val stale = at == null || Duration.between(at, now) > ttl
        if (stale) load()?.let { value = it; at = now }
        return value
    }

    @Synchronized
    fun invalidate() { at = null }
}
