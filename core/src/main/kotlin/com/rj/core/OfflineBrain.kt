package com.rj.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import kotlin.random.Random

/** Bundled offline content: quizzes, facts, jokes, place trivia, check-ins and scripted mini-podcasts. */
class ContentPack(
    val quiz: List<QuizQuestion>,
    val facts: List<String>,
    val jokes: List<String>,
    val places: Map<String, List<String>>,
    val countries: Map<String, List<String>>,
    val checkIns: List<String>,
    val podcasts: List<Pair<String, List<SpokenLine>>>,
) {
    companion object {
        fun load(): ContentPack {
            val stream = ContentPack::class.java.getResourceAsStream("/rj/offline_pack.json")
                ?: error("offline_pack.json missing from resources")
            val root = stream.use { ObjectMapper().readTree(it) }
            fun JsonNode.strings() = map { it.asText() }
            return ContentPack(
                quiz = root["quiz"].map { q ->
                    QuizQuestion(
                        question = q["q"].asText(),
                        choices = q["choices"]?.strings().orEmpty(),
                        answer = q["answer"].asText(),
                        accept = q["accept"]?.strings().orEmpty(),
                        fact = q["fact"]?.asText(),
                    )
                },
                facts = root["facts"].strings(),
                jokes = root["jokes"].strings(),
                places = root["places"].fields().asSequence().associate { (k, v) -> k to v.strings() },
                countries = root["countries"].fields().asSequence().associate { (k, v) -> k to v.strings() },
                checkIns = root["checkins"].strings(),
                podcasts = root["podcasts"].map { p ->
                    p["topic"].asText() to p["lines"].map { l ->
                        SpokenLine(if (l[0].asText() == "cohost") Speaker.COHOST else Speaker.HOST, l[1].asText())
                    }
                },
            )
        }
    }
}

/**
 * A complete RJ that runs with no network: it still knows the time, your calendar, the drive, the last
 * weather it saw and your music library, and it hosts quizzes, trivia, jokes and scripted podcasts.
 */
class OfflineBrain(
    private val pack: ContentPack = ContentPack.load(),
    private val random: Random = Random.Default,
) : RjBrain {
    private var persona = Persona()
    private var profile = UserProfile()
    private var catalog: List<SongPick> = emptyList()
    private val used = mutableMapOf<String, MutableSet<Int>>()

    private val name get() = profile.name.ifBlank { "my friend" }

    override fun startShow(persona: Persona, profile: UserProfile, memories: List<String>, catalog: List<SongPick>) {
        this.persona = persona; this.profile = profile; this.catalog = catalog
    }

    /** Pick an unused item from a list, cycling once everything has been used. */
    private fun <T> fresh(bucket: String, items: List<T>): T? {
        if (items.isEmpty()) return null
        val seen = used.getOrPut(bucket) { mutableSetOf() }
        if (seen.size >= items.size) seen.clear()
        val i = (items.indices - seen).random(random)
        seen += i
        return items[i]
    }

    private fun pick(vararg options: String) = options[random.nextInt(options.size)]

    override fun next(cue: Cue, ctx: ShowContext): Segment = when (cue.type) {
        CueType.OPENER -> opener(ctx)
        CueType.LINK -> link(ctx)
        CueType.NEWS_BREAK -> withSong(ctx, "No news wire out here, so here's something better. ${fresh("fact", pack.facts)}")
        CueType.PODCAST -> podcast()
        CueType.QUIZ -> quiz(cue, ctx)
        CueType.TRIVIA -> trivia(ctx)
        CueType.JOKE -> withSong(ctx, pick("Okay, joke time. ", "Here's one for you. ", "Brace yourself. ") +
            (fresh("joke", pack.jokes) ?: "") + " " + pick("I'll be here all week.", "Thank you, thank you.", "You're welcome."))
        CueType.AGENDA_NUDGE -> withSong(ctx, "Heads up, $name: ${cue.notes.firstOrNull() ?: "something's coming up on your calendar"}. " +
            pick("You've got this.", "Plenty of time, no stress.", "Let's get you there in a good mood."))
        CueType.WEATHER_CHECK -> withSong(ctx, weatherLine(ctx) ?: "The weather's doing its own thing out there. Drive safe.")
        CueType.CHECK_IN -> Segment(speech = fresh("checkin", pack.checkIns) ?: "How's your day going?", listen = true)
        CueType.TRAFFIC -> withSong(ctx, pick(
            "Traffic's crawling, $name, but we're in no rush in here. Deep breath. ",
            "Stuck in a jam? Perfect excuse for more music. ",
        ) + "Here's a fact to pass the time: ${fresh("fact", pack.facts)}")
        CueType.REPLY -> reply(cue, ctx)
        CueType.SIGN_OFF -> Segment(speech = signOff(cue, ctx))
    }

    private fun opener(ctx: ShowContext): Segment {
        val part = ShowContext.partOfDay(ctx.now)
        val greet = when (part) {
            "morning" -> pick("Good morning, $name!", "Morning, $name! Rise and drive.")
            "afternoon" -> pick("Good afternoon, $name!", "Afternoon, $name!")
            "evening" -> pick("Good evening, $name!", "Evening, $name!")
            else -> pick("Well hello, night owl $name.", "Late one tonight, $name.")
        }
        val parts = mutableListOf("$greet It's ${persona.carName}, and you're tuned in to the only show made just for you.")
        ctx.history.firstOrNull()?.let { parts += "That's $it." }
        weatherLine(ctx)?.let { parts += it }
        ctx.nextEvent()?.let { e ->
            val mins = Duration.between(ctx.now, e.start).toMinutes()
            parts += if (mins in 1..180) "You've got ${e.title} in about ${humanMinutes(mins)}." else "Later on: ${e.title}."
        }
        ctx.drive?.destination?.let { parts += "Destination: $it. Let's go." }
        parts += "Coming up: a quiz, some trivia, and my co-host ${persona.cohostName} has opinions."
        return withSong(ctx, parts.joinToString(" "))
    }

    private fun link(ctx: ShowContext): Segment {
        val back = ctx.nowPlaying?.title?.let { t ->
            pick("That was $t${ctx.nowPlaying.artist?.let { " by $it" } ?: ""}. ", "$t, lovely stuff. ", "Mm, $t. ")
        } ?: ""
        val aside = when (random.nextInt(4)) {
            0 -> ctx.drive?.takeIf { it.distanceKm >= 1 }?.let { "${"%.0f".format(it.distanceKm)} kilometres down. " } ?: ""
            1 -> "It's ${(ctx.now.hour % 12).let { if (it == 0) 12 else it }}:${"%02d".format(ctx.now.minute)}. "
            else -> ""
        }
        return withSong(ctx, back + aside)
    }

    private fun withSong(ctx: ShowContext, speech: String): Segment {
        val song = nextSong(ctx)
        val intro = song?.let { pick("Here's ${it.title} by ${it.artist}.", "Next up, ${it.artist}.", "This is ${it.title}.") }
        return Segment(speech = listOfNotNull(speech.trim().ifEmpty { null }, intro).joinToString(" "), song = song)
    }

    private fun nextSong(ctx: ShowContext): SongPick? {
        if (catalog.isEmpty()) return null
        val played = ctx.recentSongs.toSet()
        val options = catalog.filter { it.toString() !in played }.ifEmpty { catalog }
        return options.random(random)
    }

    private fun podcast(): Segment {
        val (topic, lines) = fresh("podcast", pack.podcasts) ?: return Segment(speech = "")
        return Segment(
            speech = pick("Time for the hot seat with ${persona.cohostName}.", "${persona.cohostName}'s back in the studio.") +
                " Today's question: $topic",
            lines = lines + SpokenLine(Speaker.HOST, pick("So what do you reckon, $name? Whose side are you on?",
                "$name, you be the judge. Who's right?")),
            listen = true,
        )
    }

    private fun quiz(cue: Cue, ctx: ShowContext): Segment {
        val first = cue.notes.any { it.contains("question 1 of") }
        val lead = if (first) pick("It's quiz time! Answers out loud, I'm listening.", "Pop quiz, $name! Just shout the answer.")
        else pick("Next question.", "Here we go.", "Right, next one.")
        // Sometimes play name-that-tune from the driver's own library.
        if (catalog.size >= 3 && random.nextInt(4) == 0) {
            val options = catalog.shuffled(random).distinctBy { it.title }.take(3)
            if (options.size == 3) {
                val tune = options[0]
                return Segment(speech = lead, quiz = QuizQuestion(
                    question = "Name that tune! What's this song?",
                    choices = options.shuffled(random).map { it.title },
                    answer = tune.title, fact = "That was ${tune.title} by ${tune.artist}.", tune = tune,
                ))
            }
        }
        val q = fresh("quiz", pack.quiz) ?: return Segment(speech = "Quiz machine's empty. Back to the music.")
        return Segment(speech = lead, quiz = q.copy(choices = q.choices.shuffled(random)))
    }

    private fun trivia(ctx: ShowContext): Segment {
        val locality = ctx.drive?.locality?.lowercase()
        val home = profile.homeCity.lowercase()
        val placeKey = pack.places.keys.firstOrNull { k -> locality?.contains(k) == true }
            ?: pack.places.keys.firstOrNull { k -> home.isNotBlank() && home.contains(k) }
        val fact = placeKey?.let { fresh("place:$it", pack.places.getValue(it)) }
        val speech = if (fact != null) {
            val where = ctx.drive?.locality ?: profile.homeCity
            pick("Local trivia time. We're around $where, so: ", "Did you know? ") + fact
        } else {
            "Trivia time. " + (fresh("fact", pack.facts) ?: "")
        }
        return withSong(ctx, speech)
    }

    private fun reply(cue: Cue, ctx: ShowContext): Segment {
        val said = cue.userSaid.orEmpty()
        val norm = QuizJudge.normalize(said)
        // "play something by X" / "play X": find it in the library.
        if (norm.startsWith("play") || norm.contains("put on")) {
            val words = norm.removePrefix("play").split(' ').filter { it.length > 2 && it !in setOf("something", "song", "some", "by", "the", "put") }
            val match = catalog.maxByOrNull { s ->
                val hay = QuizJudge.normalize("${s.title} ${s.artist}")
                words.count { hay.contains(it) }
            }?.takeIf { s -> words.any { QuizJudge.normalize("${s.title} ${s.artist}").contains(it) } }
            if (match != null) return Segment(speech = pick("You got it.", "Coming right up.", "Great choice.") + " ${match.title}.", song = match)
            return withSong(ctx, "I couldn't find that one on your phone, so here's something else.")
        }
        val ack = pick("Ha, love that.", "Fair enough!", "I hear you.", "Noted.")
        return Segment(speech = "$ack I'm on my offline brain right now, so I'm better at music, quizzes and trivia than deep chat. Say quiz me, or trivia, any time.")
    }

    private fun signOff(cue: Cue, ctx: ShowContext): String = buildList {
        add(pick("And that's the drive, $name.", "We made it, $name."))
        cue.notes.forEach { add(it.trimEnd('.') + ".") }
        ctx.nextEvent()?.let { add("Good luck with ${it.title}.") }
        add(pick("${persona.carName}, signing off.", "Same time next drive?"))
    }.joinToString(" ")

    private fun weatherLine(ctx: ShowContext): String? = ctx.weather?.let { w ->
        buildString {
            append("It's ${w.temperatureC.toInt()} degrees and ${w.description}")
            w.place?.let { append(" in $it") }
            append(".")
            if ((w.precipitationChanceNext3h ?: 0) >= 60) append(" Rain's likely in the next few hours, so wipers at the ready.")
        }
    }

    private fun humanMinutes(m: Long) = when {
        m < 60 -> "$m minutes"
        m % 60 == 0L -> "${m / 60} hour${if (m >= 120) "s" else ""}"
        else -> "${m / 60} hour${if (m >= 120) "s" else ""} and ${m % 60} minutes"
    }
}
