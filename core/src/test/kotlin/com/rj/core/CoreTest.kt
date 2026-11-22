package com.rj.core

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmentTest {
    @Test fun parsesJsonReply() {
        val s = Segment.parse(
            """{"speech":"Morning Sam!","song":{"artist":"Queen","title":"Don't Stop Me Now"},
               "intro_tag":"Turn it up","listen":false,"remember":["Sam has a demo on Friday"]}"""
        )
        assertEquals("Morning Sam!", s.speech)
        assertEquals(SongPick("Queen", "Don't Stop Me Now"), s.song)
        assertEquals("Turn it up", s.introTag)
        assertEquals(listOf("Sam has a demo on Friday"), s.remember)
    }

    @Test fun toleratesFencesAndChatter() {
        val s = Segment.parse("Here you go:\n```json\n{\"speech\":\"Hi\",\"song\":null,\"listen\":true}\n```")
        assertEquals("Hi", s.speech)
        assertNull(s.song)
        assertTrue(s.listen)
    }

    @Test fun plainProseBecomesSpeech() {
        assertEquals("Just talking.", Segment.parse("Just talking.").speech)
    }

    @Test fun incompleteSongIsDropped() {
        assertNull(Segment.parse("""{"speech":"x","song":{"artist":"","title":"T"}}""").song)
    }
}

class PlannerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val t0 = ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, zone)

    private fun ctx(now: ZonedDateTime, events: List<CalendarEvent> = emptyList(), drive: DriveState? = null) =
        ShowContext(now = now, profile = UserProfile(name = "Sam"), drive = drive, events = events)

    @Test fun opensThenLinksThenRotatesFeatures() {
        val p = ShowPlanner()
        assertEquals(CueType.OPENER, p.next(ctx(t0)).type)
        assertEquals(CueType.LINK, p.next(ctx(t0.plusMinutes(4))).type)
        assertEquals(CueType.LINK, p.next(ctx(t0.plusMinutes(8))).type)
        // 16 minutes in, two songs since the last talk break: news is due.
        assertEquals(CueType.NEWS_BREAK, p.next(ctx(t0.plusMinutes(16))).type)
        assertEquals(CueType.LINK, p.next(ctx(t0.plusMinutes(20))).type)
    }

    @Test fun nudgesUpcomingEventOnce() {
        val p = ShowPlanner()
        val meeting = CalendarEvent("Design review", t0.plusHours(3), t0.plusHours(4))
        p.next(ctx(t0, listOf(meeting)))
        val cue = p.next(ctx(t0.plusMinutes(100), listOf(meeting)))
        assertEquals(CueType.AGENDA_NUDGE, cue.type)
        assertTrue(cue.notes.single().contains("Design review"))
        assertEquals(CueType.LINK, p.next(ctx(t0.plusMinutes(104), listOf(meeting))).type)
    }

    @Test fun reactsToTraffic() {
        val p = ShowPlanner()
        p.next(ctx(t0))
        val jam = DriveState(startedAt = t0, distanceKm = 3.0, crawlingFor = Duration.ofMinutes(3))
        assertEquals(CueType.TRAFFIC, p.next(ctx(t0.plusMinutes(5), drive = jam)).type)
        // Not again straight away.
        assertEquals(CueType.LINK, p.next(ctx(t0.plusMinutes(8), drive = jam)).type)
    }
}

class ContextAndFeedsTest {
    @Test fun rendersBriefing() {
        val zone = ZoneId.of("Europe/London")
        val now = ZonedDateTime.of(2026, 11, 2, 8, 30, 0, 0, zone)
        val text = ShowContext(
            now = now,
            profile = UserProfile(name = "Sam"),
            drive = DriveState(startedAt = now.minusMinutes(12), elapsed = Duration.ofMinutes(12), distanceKm = 7.4,
                speedKph = 48.0, averageSpeedKph = 37.0, locality = "Camden"),
            events = listOf(CalendarEvent("Standup", now.plusMinutes(45), now.plusMinutes(60), "Office")),
            headlines = listOf(NewsItem("Big news", "BBC")),
        ).render()
        assertTrue("Monday 2 November, 08:30 (morning)" in text, text)
        assertTrue("passing through Camden" in text, text)
        assertTrue("Standup (09:15-09:30, in 45 min) @ Office" in text, text)
        assertTrue("- Big news [BBC]" in text, text)
    }

    @Test fun parsesOpenMeteo() {
        val w = WeatherClient.parse(javaClass.getResource("/weather.json")!!.readText(), "Bengaluru")
        assertEquals("light rain", w.description)
        assertEquals(23.4, w.temperatureC)
        assertEquals(70, w.precipitationChanceNext3h) // 08:00..10:00 window, not 07:00 or 11:00
        assertEquals("17:52", w.sunset)
        assertEquals(28.9, w.highC)
    }

    @Test fun parsesGoogleNewsRss() {
        val items = NewsClient.parseRss(javaClass.getResourceAsStream("/news.xml")!!)
        assertEquals(3, items.size)
        assertEquals("Monsoon exits early, cities brace for warm November", items[0].title)
        assertEquals("The Daily Bugle", items[0].source)
        assertNull(items[2].source)
    }

    @Test fun promptMentionsPersonaAndProfile() {
        val sys = PromptBuilder.system(
            Persona(carName = "Bolt", style = Persona.Style.SIDEKICK),
            UserProfile(name = "Sam", keyStuff = "training for a half marathon"),
            listOf("loves 90s Britpop"),
        )
        assertTrue("You are Bolt" in sys)
        assertTrue("half marathon" in sys)
        assertTrue("loves 90s Britpop" in sys)
    }
}
