package com.rj.core

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuizJudgeTest {
    private val q = QuizQuestion("Capital of Australia?", listOf("Sydney", "Canberra", "Melbourne"), "Canberra")

    @Test fun acceptsAnswerLetterAndOrdinal() {
        assertTrue(QuizJudge.isCorrect("Canberra", q))
        assertTrue(QuizJudge.isCorrect("I think it's canbera", q)) // recogniser typo
        assertTrue(QuizJudge.isCorrect("B", q))
        assertTrue(QuizJudge.isCorrect("option b", q))
        assertTrue(QuizJudge.isCorrect("the second one", q))
        assertFalse(QuizJudge.isCorrect("Sydney", q))
        assertFalse(QuizJudge.isCorrect("a", q))
        assertFalse(QuizJudge.isCorrect("", q))
    }

    @Test fun namesNumbersAndAliases() {
        val p = QuizQuestion("Who sang it?", answer = "Freddie Mercury")
        assertTrue(QuizJudge.isCorrect("Mercury", p))
        assertFalse(QuizJudge.isCorrect("Freddie", p))
        val n = QuizQuestion("Minutes in a day?", listOf("1240", "1440", "1640"), "1440", accept = listOf("fourteen forty"))
        assertTrue(QuizJudge.isCorrect("1440", n))
        assertTrue(QuizJudge.isCorrect("fourteen forty", n))
        assertFalse(QuizJudge.isCorrect("1640", n))
        val v = QuizQuestion("Mona Lisa?", answer = "Leonardo da Vinci", accept = listOf("da vinci"))
        assertTrue(QuizJudge.isCorrect("da Vinci", v))
    }

    @Test fun spokenQuestionReadsChoices() {
        assertEquals("Capital of Australia? A: Sydney, B: Canberra, or C: Melbourne?", QuizJudge.spokenQuestion(q))
    }

    @Test fun routesIntents() {
        assertEquals(CueType.QUIZ, IntentRouter.route("Quiz me!"))
        assertEquals(CueType.PODCAST, IntentRouter.route("let's have a debate"))
        assertEquals(CueType.TRIVIA, IntentRouter.route("tell me about this place"))
        assertEquals(CueType.JOKE, IntentRouter.route("tell me a joke"))
        assertNull(IntentRouter.route("I had a long day at work"))
    }
}

class SegmentFeaturesTest {
    @Test fun parsesPodcastAndQuiz() {
        val s = Segment.parse(
            """{"speech":"Debate time","lines":[{"who":"host","text":"Yes"},{"who":"cohost","text":"No"}],
               "quiz":{"question":"Q?","choices":["x","y"],"answer":"y","right":"Nice","wrong":"Nope, y","fact":"F"},
               "listen":true}"""
        )
        assertEquals(listOf(Speaker.HOST, Speaker.COHOST), s.lines.map { it.speaker })
        assertEquals("y", s.quiz?.answer)
        assertEquals("Nice", s.quiz?.rightLine)
        assertEquals(4, s.spokenParts().size)
    }
}

class OfflineBrainTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 2, 8, 5, 0, 0, zone)
    private val catalog = listOf(SongPick("A-ha", "Take On Me"), SongPick("Queen", "Under Pressure"), SongPick("Lucky Ali", "O Sanam"))

    private fun brain() = OfflineBrain(random = Random(42)).apply {
        startShow(Persona(carName = "Bolt"), UserProfile(name = "Sam", homeCity = "Pune"), emptyList(), catalog)
    }

    private fun ctx(locality: String? = null) = ShowContext(
        now = now, profile = UserProfile(name = "Sam"), online = false,
        drive = DriveState(startedAt = now, distanceKm = 4.0, locality = locality),
        events = listOf(CalendarEvent("Standup", now.plusMinutes(40), now.plusMinutes(55))),
    )

    @Test fun packLoads() {
        val p = ContentPack.load()
        assertTrue(p.quiz.size >= 40)
        assertTrue(p.podcasts.all { it.second.size >= 4 })
        // Every quiz answer must be one of its choices, or the letter judging breaks.
        p.quiz.forEach { q -> assertTrue(q.answer in q.choices, "bad answer in: ${q.question}") }
    }

    @Test fun everyCueProducesSomething() {
        val b = brain()
        CueType.entries.forEach { t ->
            val seg = b.next(Cue(t, notes = listOf("question 1 of 3"), userSaid = "play some queen"), ctx("Kothrud, Pune"))
            assertTrue(seg.speech.isNotBlank() || seg.quiz != null, "empty segment for $t")
        }
    }

    @Test fun openerUsesContextAndPicksFromLibrary() {
        val seg = brain().next(Cue(CueType.OPENER), ctx())
        assertTrue("Sam" in seg.speech && "Standup" in seg.speech, seg.speech)
        assertNotNull(seg.song)
        assertTrue(seg.song in catalog)
    }

    @Test fun localTriviaAndRequests() {
        val b = brain()
        assertTrue("Peshwa" in b.next(Cue(CueType.TRIVIA), ctx("Kothrud, Pune")).speech ||
            "Oxford of the East" in b.next(Cue(CueType.TRIVIA), ctx("Kothrud, Pune")).speech)
        assertEquals("Under Pressure", b.next(Cue(CueType.REPLY, userSaid = "play some queen"), ctx()).song?.title)
    }

    @Test fun podcastAndQuiz() {
        val b = brain()
        val pod = b.next(Cue(CueType.PODCAST), ctx())
        assertTrue(pod.lines.any { it.speaker == Speaker.COHOST })
        assertTrue(pod.listen)
        repeat(6) { assertNotNull(b.next(Cue(CueType.QUIZ, listOf("question 2 of 3")), ctx()).quiz) }
    }
}

class PlannerFeaturesTest {
    private val t0 = ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, ZoneId.of("UTC"))

    private fun ctx(min: Long, locality: String? = null, online: Boolean = true) = ShowContext(
        now = t0.plusMinutes(min), profile = UserProfile(), online = online,
        drive = DriveState(startedAt = t0, elapsed = Duration.ofMinutes(min), distanceKm = min.toDouble(), locality = locality),
    )

    @Test fun rotatesThroughFeatures() {
        val p = ShowPlanner()
        val seen = mutableListOf<CueType>()
        for (m in 0L..120L step 4) seen += p.next(ctx(m)).type
        listOf(CueType.NEWS_BREAK, CueType.TRIVIA, CueType.QUIZ, CueType.PODCAST, CueType.CHECK_IN).forEach {
            assertTrue(it in seen, "$it never scheduled: $seen")
        }
        // Never two talk features back to back.
        seen.zipWithNext().forEach { (a, b) -> assertFalse(a != CueType.LINK && a != CueType.OPENER && b != CueType.LINK && a == b) }
    }

    @Test fun offlineSkipsNewsAndNewTownTriggersTrivia() {
        val p = ShowPlanner()
        p.next(ctx(0, "Aundh", online = false))
        val types = (1L..60L step 4).map { p.next(ctx(it, "Aundh", online = false)).type }
        assertFalse(CueType.NEWS_BREAK in types)
        assertEquals(CueType.TRIVIA, p.next(ctx(64, "Lonavala", online = false)).type)
    }
}
