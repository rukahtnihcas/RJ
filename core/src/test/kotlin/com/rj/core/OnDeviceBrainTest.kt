package com.rj.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnDeviceBrainTest {
    private val now = ZonedDateTime.of(2026, 11, 22, 8, 0, 0, 0, ZoneId.of("Europe/London"))
    private val ctx = ShowContext(now = now, profile = UserProfile(name = "Sam"), drive = null,
        headlines = listOf(NewsItem("Big local story", "BBC", scope = NewsScope.LOCAL)))
    private val library = listOf(SongPick("Queen", "Under Pressure"), SongPick("Oasis", "Wonderwall"))

    private fun brain(reply: String, catalog: List<SongPick> = emptyList(), seen: MutableList<String>? = null) =
        OnDeviceBrain({ sys, prompt -> seen?.apply { add(sys); add(prompt) }; reply }).apply {
            startShow(Persona(carName = "Bolt"), UserProfile(name = "Sam"), emptyList(), catalog)
        }

    @Test fun usesCompactPromptAndCleansSpeech() {
        val seen = mutableListOf<String>()
        val seg = brain("""Sure! {"speech":"**Morning** Sam 🚗 lovely day","song":{"artist":"Oasis","title":"Wonderwall"},"intro_tag":"Here's Oasis"}""",
            library, seen).next(Cue(CueType.OPENER), ctx)
        assertEquals("Morning Sam lovely day", seg.speech)
        assertEquals("Wonderwall", seg.song?.title)
        assertTrue("You are Bolt" in seen[0] && "Pick songs ONLY from" in seen[1] && "Big local story" in seen[1])
        assertTrue(seen[1].length < 4_000)
    }

    @Test fun dropsSongsNotInTheLibrary() {
        val seg = brain("""{"speech":"Next up","song":{"artist":"Nobody","title":"Made Up Song"}}""", library).next(Cue(CueType.LINK), ctx)
        assertNull(seg.song)
    }

    @Test fun rejectsUnusableReplies() {
        assertFailsWith<UnusableReply> { brain("""{"speech":"Quiz!","quiz":{"question":"Q?","choices":["a","b"],"answer":"zzz"}}""").next(Cue(CueType.QUIZ), ctx) }
        assertFailsWith<UnusableReply> { brain("""{"speech":"Debate","lines":[{"who":"host","text":"hi"}]}""").next(Cue(CueType.PODCAST), ctx) }
        assertFailsWith<UnusableReply> { brain("").next(Cue(CueType.LINK), ctx) }
        val q = brain("""{"speech":"Quiz!","quiz":{"question":"Capital of France?","choices":["Paris","Rome","Oslo"],"answer":"paris"}}""")
            .next(Cue(CueType.QUIZ), ctx).quiz!!
        assertEquals("Paris", q.answer)
    }
}
