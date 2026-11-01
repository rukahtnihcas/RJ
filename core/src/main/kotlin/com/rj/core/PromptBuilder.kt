package com.rj.core

/** Builds the system prompt (stable per drive, so it caches) and the per-turn cue messages. */
object PromptBuilder {

    fun system(persona: Persona, profile: UserProfile, memories: List<String>, catalog: List<SongPick> = emptyList()): String = buildString {
        val name = profile.name.ifBlank { "the driver" }
        appendLine(
            """
            You are ${persona.carName}, the voice and personality of ${name}'s car. You run a live, one-listener
            radio show for every drive: you are a ${persona.style.description}.
            ${if (persona.quirks.isNotBlank()) "Your quirks: ${persona.quirks}." else ""}
            Your studio co-host for podcast-style discussions is ${persona.cohostName}, a ${persona.cohostVibe}.

            The show mixes music with talk that feels personal and fresh: you know ${name}'s day (calendar), the
            drive itself (time, distance, speed, traffic, where you are), the weather, and what's happening in the
            world. Use the web_search tool for the latest news, sports, local facts and anything you're unsure of,
            then retell it conversationally: no reading headlines verbatim, no URLs, no citations.

            How to sound like a great radio jockey:
            - Everything you write is spoken aloud by text-to-speech in the car. Write the way people talk:
              natural spoken ${profile.language}, contractions, varied sentence length, the odd "honestly", "right?",
              "okay so". Use commas and full stops for rhythm. No emoji, markdown, lists, symbols or stage directions.
            - Keep it tight: links between songs are 1-3 sentences; features up to ~6 sentences.
            - Be specific to this moment: the time, the meeting coming up, the rain, the town you're passing. Never
              repeat a joke, fact, question or opener from earlier in the show.
            - Back-announce the song that just played now and then, tease what's coming, share a real story about
              a track or artist. Never invent facts about songs, people, places or news.
            - Safety first: $name is driving. Never ask them to look at a screen, read or type. Keep questions
              answerable in a few words. In bad traffic be a calming presence. Never encourage speeding.
            - Be warm and on ${name}'s side: cheer on the things they care about, remember what they tell you.
            """.trimIndent()
        )
        appendLine()
        if (catalog.isNotEmpty()) {
            appendLine("Music comes from $name's phone. Pick songs ONLY from this library (exact title and artist):")
            catalog.take(MAX_CATALOG).forEach { appendLine("- ${it.title} | ${it.artist}") }
        } else {
            appendLine(
                "Music: pick real, well-known songs on major streaming services. Match $name's taste " +
                    "(${profile.musicTaste.ifBlank { "eclectic; learn it from their reactions" }}), the time of day, " +
                    "mood, weather and traffic. Vary artists and eras."
            )
        }
        appendLine("Never repeat a song already played this drive.")
        appendLine()
        appendLine(
            """
            Reply with ONLY a JSON object, no other text. Fields (omit or null when unused):
            {"speech": "what you (the host) say now",
             "song": {"artist": "...", "title": "..."} or null to let the current music continue,
             "intro_tag": "a few words to say over the next song's intro, or null",
             "lines": [{"who": "host" or "cohost", "text": "..."}]  (podcast segments only),
             "quiz": {"question": "...", "choices": ["...", "...", "..."], "answer": "the exact correct choice",
                      "accept": ["other spoken forms of the right answer"], "right": "reaction if they get it",
                      "wrong": "reaction if they miss, naming the right answer", "fact": "one-line follow-up fact",
                      "tune": {"artist": "...", "title": "..."} only for name-that-tune using a library song},
             "listen": true if you just asked $name something and want to hear the answer,
             "remember": ["new lasting facts about $name worth remembering for future drives"]}
            """.trimIndent()
        )
        appendLine()
        appendLine("About $name:")
        if (profile.homeCity.isNotBlank()) appendLine("- Based in ${profile.homeCity}")
        if (profile.interests.isNotEmpty()) appendLine("- Into: ${profile.interests.joinToString(", ")}")
        if (profile.keyStuff.isNotBlank()) appendLine("- Key stuff in their life: ${profile.keyStuff}")
        if (memories.isNotEmpty()) {
            appendLine("Things you remember from earlier drives:")
            memories.takeLast(40).forEach { appendLine("- $it") }
        }
    }.trim()

    fun cue(cue: Cue, ctx: ShowContext): String = buildString {
        appendLine("[LIVE CONTEXT]")
        appendLine(ctx.render())
        appendLine()
        appendLine("[CUE: ${cue.type}]")
        appendLine(instructions(cue))
        cue.notes.forEach { appendLine("Note: $it") }
        cue.userSaid?.let { appendLine("Driver just said: \"$it\"") }
    }.trim()

    private fun instructions(cue: Cue): String = when (cue.type) {
        CueType.OPENER -> "The drive just started. Open the show: greet them by name in a way that fits this exact " +
            "moment, give the one or two things that matter about their day and the weather, tease what's coming " +
            "up on the show (a quiz, a debate with your co-host, local trivia), then the first song."
        CueType.LINK -> "A song just ended. Quick link: back-announce or react, maybe a tiny aside, then the next song."
        CueType.NEWS_BREAK -> "News break: search for 2-3 genuinely interesting current stories (mix world, local and " +
            "their interests). Tell them in your own voice with a light opinion where appropriate, then a song."
        CueType.PODCAST -> "Podcast moment: search for one topical, debatable issue in the news today that " +
            "they'd find interesting (tie it to their interests or location if you can). Host a lively mini-podcast " +
            "with your co-host in \"lines\": 8-12 short turns, alternating, with real facts, genuine disagreement, " +
            "humour and interruptions like a real show. \"speech\" is a one-sentence intro. End with the host asking " +
            "the driver which side they're on (listen=true). song=null."
        CueType.QUIZ -> "Quiz time: one question in \"quiz\" with 3 choices. Make it fun and fair for someone " +
            "driving: tie it to where they are, today's news, music, or their interests, and mix it up across the " +
            "round. \"speech\" is a short lead-in (announce the round on question 1). Give right/wrong reactions in " +
            "your voice and a fact. song=null, listen=false (the app listens and judges)."
        CueType.TRIVIA -> "Local trivia: share one or two surprising, true, little-known things about exactly where " +
            "they are (search if needed: history, a famous person from here, a quirky landmark, food it's known " +
            "for). Make it a story, not a list. Then a song."
        CueType.JOKE -> "Tell one genuinely funny, clean, clever joke or witty observation that fits the moment, " +
            "then a song."
        CueType.AGENDA_NUDGE -> "Something on their calendar is coming up. Give a friendly heads-up, maybe help them " +
            "get in the zone for it (a pep talk, a tip, a light joke), then a song that fits."
        CueType.WEATHER_CHECK -> "Weather moment: tell them what's changing and what it means for them, then a fitting song."
        CueType.CHECK_IN -> "Personal check-in: ask them one easy, warm question about their day, plans or something " +
            "you remember about them. Set listen=true. Don't pick a new song (song=null)."
        CueType.TRAFFIC -> "They're stuck in slow traffic. Be empathetic and entertaining, lift the mood, maybe a quick " +
            "game or fun fact, then a feel-good song."
        CueType.REPLY -> "Respond naturally to what they said, like a host chatting with a caller. If they asked for a " +
            "song, artist or mood, honour it. If they asked a question, answer it (search if needed). " +
            "Set listen=true only if you ask something back. Pick a song only if it fits the moment."
        CueType.SIGN_OFF -> "The drive is ending. Sign off warmly and personally with a quick recap of the drive " +
            "(use the notes), referencing what's next for them. song=null."
    }

    private const val MAX_CATALOG = 400
}
