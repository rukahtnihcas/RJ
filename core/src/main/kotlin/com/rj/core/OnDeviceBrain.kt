package com.rj.core

/** A small language model running on the phone (Gemini Nano, Gemma 4...). Blocking; call off the main thread. */
fun interface LocalLlm {
    fun generate(system: String, prompt: String): String
}

/** The on-device model's reply couldn't be used; the caller falls back to the scripted host. */
class UnusableReply(message: String) : IllegalStateException(message)

/**
 * An RJ powered by an on-device model. Small models have short context windows and slip more often
 * than Claude, so this keeps prompts compact (no conversation history, a trimmed briefing), has no web
 * search (news comes from the headlines the app already fetched), and checks every reply: songs must
 * come from the library when one is in use, quizzes must be answerable, podcasts must actually have
 * two voices. Anything unusable throws [UnusableReply] so the scripted [OfflineBrain] steps in.
 */
class OnDeviceBrain(
    private val llm: LocalLlm,
    private val maxBriefingChars: Int = 1_800,
) : RjBrain {
    private var system = ""
    private var catalog: List<SongPick> = emptyList()
    private val recentLines = ArrayDeque<String>()

    override fun startShow(persona: Persona, profile: UserProfile, memories: List<String>, catalog: List<SongPick>) {
        this.catalog = catalog
        system = compactSystem(persona, profile, memories)
        recentLines.clear()
    }

    override fun next(cue: Cue, ctx: ShowContext): Segment {
        val raw = llm.generate(system, compactCue(cue, ctx))
        val seg = sanitize(Segment.parse(raw), cue)
        seg.speech.takeIf { it.isNotBlank() }?.let {
            recentLines.addLast(it.take(120))
            while (recentLines.size > 4) recentLines.removeFirst()
        }
        return seg
    }

    internal fun compactSystem(persona: Persona, profile: UserProfile, memories: List<String>): String = buildString {
        val name = profile.name.ifBlank { "the driver" }
        appendLine("You are ${persona.carName}, the voice of $name's car, hosting a live one-listener radio show.")
        appendLine("Style: ${persona.style.description}. ${persona.quirks}".trim())
        appendLine("Co-host for debates: ${persona.cohostName}, a ${persona.cohostVibe}.")
        appendLine("Everything is read aloud while $name drives: short, warm, natural spoken ${profile.language}. " +
            "No emoji, lists, markdown or stage directions. Never invent facts; if unsure, keep it light.")
        appendLine("Never name the next song in speech; put its intro in intro_tag.")
        if (profile.interests.isNotEmpty()) appendLine("$name likes: ${profile.interests.joinToString()}.")
        if (profile.musicTaste.isNotBlank()) appendLine("Music taste: ${profile.musicTaste}.")
        if (profile.keyStuff.isNotBlank()) appendLine("Key stuff: ${profile.keyStuff.take(300)}")
        memories.takeLast(8).forEach { appendLine("Remember: $it") }
        appendLine()
        appendLine("Reply with ONLY one JSON object:")
        appendLine("""{"speech":"...","song":{"artist":"...","title":"..."} or null,"intro_tag":"..." or null,""" +
            """"listen":false,"lines":[{"who":"host|cohost","text":"..."}],""" +
            """"quiz":{"question":"...","choices":["a","b","c"],"answer":"exact choice","fact":"..."} or null}""")
        append("Only include lines for debates and quiz for quizzes.")
    }.trim()

    internal fun compactCue(cue: Cue, ctx: ShowContext): String = buildString {
        appendLine("NOW:")
        appendLine(ctx.render().take(maxBriefingChars))
        if (catalog.isNotEmpty()) {
            // A small, fresh sample: small models can't hold a whole library.
            val played = ctx.recentSongs.toSet()
            val pool = catalog.filter { it.toString() !in played }.shuffled().take(25)
            appendLine("Pick songs ONLY from: " + pool.joinToString("; ") { "${it.title} | ${it.artist}" })
        }
        if (recentLines.isNotEmpty()) appendLine("You recently said (don't repeat): " + recentLines.joinToString(" / "))
        appendLine()
        appendLine("TASK: " + task(cue))
        cue.notes.forEach { appendLine("Note: $it") }
        cue.userSaid?.let { appendLine("Driver said: \"$it\"") }
    }.trim()

    private fun task(cue: Cue): String = when (cue.type) {
        CueType.OPENER -> "Open the show: greet them for this moment, mention their day and the weather, pick the first song."
        CueType.LINK -> "One or two sentences between songs, then pick the next song."
        CueType.NEWS_BREAK -> "Chat about two of the headlines listed above in your own words, then a song."
        CueType.PODCAST -> "Write a lively 8-line debate in lines between host and cohost on one headline or a fun everyday " +
            "question. speech is a one-line intro. End with the host asking the driver's view. listen=true, song=null."
        CueType.QUIZ -> "Ask one fair, fun quiz question with 3 choices in quiz. speech is a short lead-in. song=null."
        CueType.TRIVIA -> "Share one fun, well-known fact about where they are (or a general fact if unsure), then a song."
        CueType.JOKE -> "Tell one clean, clever joke, then a song."
        CueType.AGENDA_NUDGE -> "Friendly heads-up about the upcoming calendar item, maybe a pep talk, then a song."
        CueType.WEATHER_CHECK -> "Tell them what the weather is doing and what it means for them, then a song."
        CueType.CHECK_IN -> "Ask one easy, warm question about their day. listen=true, song=null."
        CueType.TRAFFIC -> "They're stuck in traffic: lift the mood, then a feel-good song."
        CueType.INBOX -> "Quick friendly roundup of who messaged (no reading out), then a song."
        CueType.RUNNING_LATE -> "Gently tell them they'll be late for their next meeting, keep them calm. song=null."
        CueType.VEHICLE_ALERT -> "Mention the car issue in the notes, practically and calmly, then a song."
        CueType.TAKE_A_BREAK -> "Kindly suggest a short break at the next safe place, then an upbeat song."
        CueType.WELLBEING -> "Calm things down with a slow breath together, no numbers, then a soothing song."
        CueType.REPLY -> "Reply naturally to what the driver said. If they asked for a song or mood, pick one."
        CueType.SIGN_OFF -> "Sign off warmly with a one-line recap from the notes. song=null."
    }

    /** Clean up and validate a small model's reply. */
    internal fun sanitize(seg: Segment, cue: Cue): Segment {
        fun clean(s: String) = s
            .replace(Regex("[*_#`~>|]+"), "")
            .replace(Regex("[\\p{So}\\p{Cn}]"), "") // emoji and other symbols TTS would read out
            .replace(Regex("\\s+"), " ")
            .trim()
        val speech = clean(seg.speech).take(700)
        val song = seg.song?.let { s ->
            if (catalog.isEmpty()) s
            else catalog.firstOrNull { QuizJudge.normalize(it.title) == QuizJudge.normalize(s.title) }
        }
        val lines = seg.lines.map { it.copy(text = clean(it.text)) }.filter { it.text.isNotBlank() }.take(14)
        val quiz = seg.quiz?.let { q ->
            val choices = q.choices.map(::clean).filter { it.isNotBlank() }.take(3)
            val answer = choices.firstOrNull { QuizJudge.normalize(it) == QuizJudge.normalize(q.answer) }
            if (choices.size < 2 || answer == null) null
            else q.copy(question = clean(q.question), choices = choices, answer = answer, tune = null)
        }
        when (cue.type) {
            CueType.QUIZ -> if (quiz == null) throw UnusableReply("quiz without a valid answer")
            CueType.PODCAST -> if (lines.size < 4 || lines.none { it.speaker == Speaker.COHOST }) throw UnusableReply("debate too short")
            else -> if (speech.isBlank() && song == null) throw UnusableReply("empty reply")
        }
        // Looks like the model echoed the format instead of talking.
        if (speech.startsWith("{") || speech.contains("\"speech\"")) throw UnusableReply("malformed reply")
        return seg.copy(
            speech = speech,
            song = song,
            introTag = seg.introTag?.let(::clean)?.takeIf { song != null },
            lines = if (cue.type == CueType.PODCAST) lines else emptyList(),
            quiz = if (cue.type == CueType.QUIZ) quiz else null,
            offer = null, // actions stay with Claude or explicit voice commands
            remember = emptyList(),
        )
    }
}
