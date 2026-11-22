package com.rj.core

import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Who the driver is, and the "key stuff" the RJ should keep in mind. Edited in Settings. */
data class UserProfile(
    val name: String = "",
    val homeCity: String = "",
    val interests: List<String> = emptyList(),
    val musicTaste: String = "",
    /** Free text: family, goals, ongoing projects, things to cheer on or remind about. */
    val keyStuff: String = "",
    /** e.g. "English", "English with a sprinkle of Hindi", "Spanish". */
    val language: String = "English",
)

data class CalendarEvent(
    val title: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val location: String? = null,
    val allDay: Boolean = false,
    val organizer: String? = null,
    val organizerEmail: String? = null,
    /** "phone" (Android calendar) or "outlook" (Microsoft 365). */
    val source: String = "phone",
)

data class WeatherSnapshot(
    val place: String?,
    val temperatureC: Double,
    val feelsLikeC: Double?,
    val description: String,
    val windKph: Double?,
    val precipitationChanceNext3h: Int?,
    val highC: Double?,
    val lowC: Double?,
    val sunset: String?,
)

enum class NewsScope(val label: String) { LOCAL("Local"), NATIONAL("National"), WORLD("World"), TOPIC("Your interests") }

data class NewsItem(val title: String, val source: String?, val topic: String? = null, val scope: NewsScope = NewsScope.TOPIC)

data class NowPlaying(val artist: String?, val title: String?, val app: String?)

/** Live state of the drive, produced by the on-device location tracker. */
data class DriveState(
    val startedAt: ZonedDateTime,
    val elapsed: Duration = Duration.ZERO,
    val distanceKm: Double = 0.0,
    val speedKph: Double = 0.0,
    val averageSpeedKph: Double = 0.0,
    /** How long we have been crawling (<8 km/h) since we last moved properly. */
    val crawlingFor: Duration = Duration.ZERO,
    val locality: String? = null,
    val destination: String? = null,
) {
    val inSlowTraffic: Boolean get() = crawlingFor >= Duration.ofSeconds(90) && distanceKm > 0.3
}

/** Everything the RJ knows at the moment it is asked to talk. */
data class ShowContext(
    val now: ZonedDateTime,
    val profile: UserProfile,
    val drive: DriveState?,
    val events: List<CalendarEvent> = emptyList(),
    val weather: WeatherSnapshot? = null,
    val headlines: List<NewsItem> = emptyList(),
    val nowPlaying: NowPlaying? = null,
    val memories: List<String> = emptyList(),
    val recentSongs: List<String> = emptyList(),
    val online: Boolean = true,
    /** Long-run history, e.g. "drive #14", "best quiz score 3/3". */
    val history: List<String> = emptyList(),
    val inbox: List<InboxItem> = emptyList(),
    val health: HealthSnapshot? = null,
    val vehicle: VehicleStatus? = null,
    val traffic: TrafficInfo? = null,
    val destinationWeather: WeatherSnapshot? = null,
    /** Usual duration of this trip, learned from past drives. */
    val usualTripMinutes: Int? = null,
) {
    fun nextEvent(): CalendarEvent? =
        events.filter { !it.allDay && it.end.isAfter(now) }.minByOrNull { it.start }

    /** Compact, human-readable briefing that goes into each prompt. */
    fun render(): String = buildString {
        val time = DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm")
        appendLine("Local time: ${now.format(time)} (${partOfDay(now)})")

        drive?.let { d ->
            append("Drive: ${d.elapsed.toMinutes()} min in, ${"%.1f".format(d.distanceKm)} km, ")
            append("now ${d.speedKph.toInt()} km/h (avg ${d.averageSpeedKph.toInt()})")
            d.locality?.let { append(", passing through $it") }
            d.destination?.let { append(", heading to $it") }
            if (d.inSlowTraffic) append(". Stuck in slow traffic for ${d.crawlingFor.toMinutes()} min")
            appendLine()
        }

        weather?.let { w ->
            append("Weather${w.place?.let { " in $it" } ?: ""}: ${w.description}, ${w.temperatureC.toInt()}°C")
            w.feelsLikeC?.let { append(" (feels ${it.toInt()}°C)") }
            if (w.highC != null && w.lowC != null) append(", high ${w.highC.toInt()} / low ${w.lowC.toInt()}")
            w.precipitationChanceNext3h?.let { append(", ${it}% chance of rain in the next 3h") }
            w.windKph?.let { append(", wind ${it.toInt()} km/h") }
            w.sunset?.let { append(", sunset $it") }
            appendLine()
        }

        traffic?.let { t ->
            val hm = DateTimeFormatter.ofPattern("HH:mm")
            append("Route to ${t.destination}: ${"%.1f".format(t.remainingKm)} km left, arriving ~${t.arrival(now).format(hm)}")
            if (t.delayMinutes >= 2) append(" (${t.delayMinutes} min slower than an empty road)") else append(" (traffic is light)")
            usualTripMinutes?.let { append("; this trip usually takes $it min") }
            t.flowNote?.let { append(". Road now: $it") }
            if (t.incidents.isNotEmpty()) append(". Incidents nearby: ${t.incidents.take(3).joinToString("; ")}")
            appendLine()
            nextEvent()?.let { e ->
                val late = Duration.between(e.start, t.arrival(now)).toMinutes()
                if (late > 2 && e.start.isAfter(now)) appendLine("At this rate they'll be about $late min late for '${e.title}' (${e.start.format(hm)}).")
            }
        }
        destinationWeather?.let { w ->
            appendLine("Weather at destination: ${w.description}, ${w.temperatureC.toInt()}°C" +
                (w.precipitationChanceNext3h?.let { ", $it% chance of rain" } ?: ""))
        }

        if (events.isNotEmpty()) {
            appendLine("Calendar:")
            val hm = DateTimeFormatter.ofPattern("HH:mm")
            events.sortedBy { it.start }.take(8).forEach { e ->
                val whenText = if (e.allDay) "all day" else {
                    val mins = Duration.between(now, e.start).toMinutes()
                    val rel = when {
                        e.end.isBefore(now) -> "done"
                        mins <= 0 -> "happening now"
                        mins < 120 -> "in $mins min"
                        else -> "at ${e.start.format(hm)}"
                    }
                    "${e.start.format(hm)}-${e.end.format(hm)}, $rel"
                }
                appendLine("- ${e.title} ($whenText)${e.location?.let { " @ $it" } ?: ""}" +
                    (e.organizer?.let { " [organiser: $it]" } ?: ""))
            }
        }

        if (headlines.isNotEmpty()) {
            headlines.groupBy { it.scope }.toSortedMap().forEach { (scope, items) ->
                appendLine("${scope.label} news:")
                items.take(5).forEach { h ->
                    appendLine("- ${h.title}${h.source?.let { " [$it]" } ?: ""}${h.topic?.let { " (#$it)" } ?: ""}")
                }
            }
        }

        if (inbox.isNotEmpty()) {
            appendLine("New for the driver (not yet mentioned):")
            inbox.sortedByDescending { it.at }.take(8).forEach { m ->
                val mins = Duration.between(m.at, now).toMinutes()
                append("- ${m.app} ${m.kind.name.lowercase()} from ${m.from}")
                if (m.count > 1) append(" (${m.count})")
                if (m.vip) append(" [VIP]")
                append(", ${if (mins < 1) "just now" else "$mins min ago"}")
                m.text?.let { append(": \"${it.take(160)}\"") }
                if (m.canReply) append(" [can reply by voice]")
                appendLine()
            }
        }
        health?.render(now)?.let { appendLine("Wellbeing (Samsung Health / watch): $it") }
        vehicle?.render()?.let { appendLine("Car status: $it") }

        nowPlaying?.let { np ->
            if (np.title != null) appendLine("Currently playing: ${np.title}${np.artist?.let { " by $it" } ?: ""}")
        }
        if (!online) appendLine("No internet connection right now.")
        if (history.isNotEmpty()) appendLine("History together: ${history.joinToString("; ")}")
        if (recentSongs.isNotEmpty()) appendLine("Already played this drive: ${recentSongs.takeLast(15).joinToString("; ")}")
    }.trim()

    companion object {
        fun partOfDay(t: ZonedDateTime): String = when (t.hour) {
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "late night"
        }
    }
}
