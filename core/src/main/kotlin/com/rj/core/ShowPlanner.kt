package com.rj.core

import java.time.Duration
import java.time.ZonedDateTime

/** What kind of moment the RJ is asked to produce next. */
enum class CueType {
    OPENER,        // start of the drive
    LINK,          // between songs: back-announce + next pick
    NEWS_BREAK,    // a couple of topical stories, with web search
    AGENDA_NUDGE,  // something on the calendar is coming up
    WEATHER_CHECK, // weather turned / rain coming / sunset
    LOCAL_COLOUR,  // something fun about where we are right now
    CHECK_IN,      // ask the driver something personal and listen
    TRAFFIC,       // empathise & entertain in a jam
    REPLY,         // driver said something
    SIGN_OFF,      // drive is ending
}

data class Cue(val type: CueType, val notes: List<String> = emptyList(), val userSaid: String? = null) {
    val allowWebSearch: Boolean get() = type in setOf(CueType.OPENER, CueType.NEWS_BREAK, CueType.LOCAL_COLOUR, CueType.REPLY)
}

/**
 * Pure, testable rotation logic: decides what the next segment should be about so the show
 * has rhythm (music, chat, news, personal moments) instead of the same patter every time.
 */
class ShowPlanner(
    private val newsEvery: Duration = Duration.ofMinutes(15),
    private val checkInEvery: Duration = Duration.ofMinutes(20),
    private val localEvery: Duration = Duration.ofMinutes(25),
    private val agendaLookahead: Duration = Duration.ofMinutes(90),
) {
    private var started = false
    private var lastNews: ZonedDateTime? = null
    private var lastCheckIn: ZonedDateTime? = null
    private var lastLocal: ZonedDateTime? = null
    private var lastTraffic: ZonedDateTime? = null
    private var lastWeatherDesc: String? = null
    private var lastRainAlert: ZonedDateTime? = null
    private val nudgedEvents = mutableSetOf<String>()
    var segmentsSinceTalkBreak = 0
        private set

    fun reset() {
        started = false; lastNews = null; lastCheckIn = null; lastLocal = null; lastTraffic = null
        lastWeatherDesc = null; lastRainAlert = null; nudgedEvents.clear(); segmentsSinceTalkBreak = 0
    }

    fun next(ctx: ShowContext): Cue {
        val now = ctx.now
        if (!started) {
            started = true
            lastNews = now; lastCheckIn = now; lastLocal = now
            lastWeatherDesc = ctx.weather?.description
            ctx.nextEvent()?.let { if (Duration.between(now, it.start) <= agendaLookahead) nudgedEvents += key(it) }
            return Cue(CueType.OPENER, openerNotes(ctx))
        }

        // 1. Time-sensitive things first.
        ctx.nextEvent()?.let { e ->
            val until = Duration.between(now, e.start)
            if (!until.isNegative && until <= agendaLookahead && key(e) !in nudgedEvents) {
                nudgedEvents += key(e)
                return talk(Cue(CueType.AGENDA_NUDGE, listOf("'${e.title}' starts in ${until.toMinutes()} min" +
                        (e.location?.let { " at $it" } ?: ""))))
            }
        }

        ctx.drive?.takeIf { it.inSlowTraffic }?.let { d ->
            if (lastTraffic == null || Duration.between(lastTraffic, now) > Duration.ofMinutes(12)) {
                lastTraffic = now
                return talk(Cue(CueType.TRAFFIC, listOf("crawling for ${d.crawlingFor.toMinutes()} min")))
            }
        }

        ctx.weather?.let { w ->
            val rainSoon = (w.precipitationChanceNext3h ?: 0) >= 60
            val changed = lastWeatherDesc != null && lastWeatherDesc != w.description
            lastWeatherDesc = w.description
            if (rainSoon && (lastRainAlert == null || Duration.between(lastRainAlert, now) > Duration.ofHours(2))) {
                lastRainAlert = now
                return talk(Cue(CueType.WEATHER_CHECK, listOf("${w.precipitationChanceNext3h}% chance of rain soon")))
            }
            if (changed) return talk(Cue(CueType.WEATHER_CHECK, listOf("weather just changed to ${w.description}")))
        }

        // 2. Rotating features, at most one talk-heavy break every couple of songs.
        if (segmentsSinceTalkBreak >= 2) {
            if (elapsed(lastNews, now) >= newsEvery) {
                lastNews = now
                return talk(Cue(CueType.NEWS_BREAK))
            }
            if (elapsed(lastCheckIn, now) >= checkInEvery) {
                lastCheckIn = now
                return talk(Cue(CueType.CHECK_IN))
            }
            if (ctx.drive?.locality != null && elapsed(lastLocal, now) >= localEvery) {
                lastLocal = now
                return talk(Cue(CueType.LOCAL_COLOUR, listOf("we're in ${ctx.drive.locality}")))
            }
        }

        segmentsSinceTalkBreak++
        return Cue(CueType.LINK)
    }

    private fun talk(c: Cue): Cue { segmentsSinceTalkBreak = 0; return c }

    private fun openerNotes(ctx: ShowContext): List<String> = buildList {
        add("it's ${ShowContext.partOfDay(ctx.now)} on a ${ctx.now.dayOfWeek.name.lowercase()}")
        ctx.nextEvent()?.let { add("first thing on the agenda: ${it.title}") }
        ctx.weather?.let { add("weather: ${it.description}, ${it.temperatureC.toInt()}°C") }
        ctx.drive?.destination?.let { add("heading to $it") }
    }

    private fun elapsed(since: ZonedDateTime?, now: ZonedDateTime) =
        if (since == null) Duration.ofDays(1) else Duration.between(since, now)

    private fun key(e: CalendarEvent) = "${e.title}@${e.start.toEpochSecond()}"
}
