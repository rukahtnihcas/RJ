package com.rj.core

import java.time.Duration
import java.time.ZonedDateTime

/** What kind of moment the RJ is asked to produce next. */
enum class CueType {
    OPENER,        // start of the drive
    LINK,          // between songs: back-announce + next pick
    NEWS_BREAK,    // a couple of topical stories, with web search
    PODCAST,       // two-host discussion of a topical issue
    QUIZ,          // one voice-quiz question (the engine runs a round of several)
    TRIVIA,        // something fun and true about where we are right now
    JOKE,          // a quick laugh
    AGENDA_NUDGE,  // something on the calendar is coming up
    WEATHER_CHECK, // weather turned / rain coming
    CHECK_IN,      // ask the driver something personal and listen
    TRAFFIC,       // empathise & entertain in a jam
    REPLY,         // driver said something
    SIGN_OFF,      // drive is ending
}

data class Cue(val type: CueType, val notes: List<String> = emptyList(), val userSaid: String? = null)

/**
 * Pure, testable rotation logic: decides what the next segment should be about so the show has rhythm
 * (music, chat, news, a podcast, a quiz, local trivia, personal moments) instead of the same patter.
 */
class ShowPlanner(
    private val agendaLookahead: Duration = Duration.ofMinutes(90),
) {
    /** A rotating feature: how often it recurs and when it first becomes due. */
    private data class Feature(val type: CueType, val every: Duration, val firstAfter: Duration, val onlineOnly: Boolean = false)

    private val features = listOf(
        Feature(CueType.NEWS_BREAK, Duration.ofMinutes(18), Duration.ofMinutes(10), onlineOnly = true),
        Feature(CueType.TRIVIA, Duration.ofMinutes(20), Duration.ofMinutes(14)),
        Feature(CueType.QUIZ, Duration.ofMinutes(24), Duration.ofMinutes(18)),
        Feature(CueType.PODCAST, Duration.ofMinutes(30), Duration.ofMinutes(25)),
        Feature(CueType.CHECK_IN, Duration.ofMinutes(28), Duration.ofMinutes(32)),
        Feature(CueType.JOKE, Duration.ofMinutes(35), Duration.ofMinutes(40)),
    )

    private var startedAt: ZonedDateTime? = null
    private val lastRun = mutableMapOf<CueType, ZonedDateTime>()
    private var lastTraffic: ZonedDateTime? = null
    private var lastWeatherDesc: String? = null
    private var lastRainAlert: ZonedDateTime? = null
    private var lastLocality: String? = null
    private val nudgedEvents = mutableSetOf<String>()
    var segmentsSinceTalkBreak = 0
        private set

    fun reset() {
        startedAt = null; lastRun.clear(); lastTraffic = null; lastWeatherDesc = null; lastRainAlert = null
        lastLocality = null; nudgedEvents.clear(); segmentsSinceTalkBreak = 0
    }

    /** The driver asked for a feature directly: count it as run so the rotation doesn't repeat it soon. */
    fun markRun(type: CueType, now: ZonedDateTime) { lastRun[type] = now; segmentsSinceTalkBreak = 0 }

    fun next(ctx: ShowContext): Cue {
        val now = ctx.now
        val start = startedAt
        if (start == null) {
            startedAt = now
            lastWeatherDesc = ctx.weather?.description
            lastLocality = ctx.drive?.locality
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

        // 2. Entering a new town is the perfect trivia moment.
        val locality = ctx.drive?.locality
        if (locality != null && locality != lastLocality) {
            val previous = lastLocality
            lastLocality = locality
            val sinceTrivia = lastRun[CueType.TRIVIA]?.let { Duration.between(it, now) } ?: Duration.ofDays(1)
            if (previous != null && sinceTrivia >= Duration.ofMinutes(8)) {
                lastRun[CueType.TRIVIA] = now
                return talk(Cue(CueType.TRIVIA, listOf("we just entered $locality")))
            }
        }

        // 3. Rotating features, at most one talk-heavy break every couple of songs. Most overdue wins.
        if (segmentsSinceTalkBreak >= 2) {
            val due = features
                .filter { ctx.online || !it.onlineOnly }
                .map { f ->
                    val last = lastRun[f.type]
                    val overdue = if (last == null) Duration.between(start.plus(f.firstAfter), now).toMillis().toDouble() / f.every.toMillis() + 1
                    else Duration.between(last, now).toMillis().toDouble() / f.every.toMillis()
                    f to overdue
                }
                .filter { it.second >= 1.0 }
                .maxByOrNull { it.second }
            if (due != null) {
                lastRun[due.first.type] = now
                val notes = if (due.first.type == CueType.TRIVIA && locality != null) listOf("we're in $locality") else emptyList()
                return talk(Cue(due.first.type, notes))
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

    private fun key(e: CalendarEvent) = "${e.title}@${e.start.toEpochSecond()}"
}
