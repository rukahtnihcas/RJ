package com.rj.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

data class SongPick(val artist: String, val title: String) {
    val query: String get() = "$title $artist"
    override fun toString() = "$title – $artist"
}

enum class Speaker { HOST, COHOST }

/** One line of a two-voice podcast-style segment. */
data class SpokenLine(val speaker: Speaker, val text: String)

/** A voice quiz question. [answer] is the canonical correct answer. */
data class QuizQuestion(
    val question: String,
    val choices: List<String> = emptyList(),
    val answer: String,
    val accept: List<String> = emptyList(),
    val rightLine: String? = null,
    val wrongLine: String? = null,
    val fact: String? = null,
    /** "Name that tune": play the start of this song, then ask. */
    val tune: SongPick? = null,
)

/** One "on-air" moment produced by the brain. */
data class Segment(
    /** What the host says first. */
    val speech: String,
    /** The next song to play, or null to keep the current music going. */
    val song: SongPick? = null,
    /** Short line to talk over the song's intro (radio-style, music ducked). */
    val introTag: String? = null,
    /** RJ asked the driver a question: open the mic after speaking. */
    val listen: Boolean = false,
    /** New long-term facts about the driver worth remembering. */
    val remember: List<String> = emptyList(),
    /** Two-host discussion, spoken after [speech]. */
    val lines: List<SpokenLine> = emptyList(),
    /** A quiz question to ask after [speech]. */
    val quiz: QuizQuestion? = null,
) {
    /** Everything this segment will say, in order (used to pre-render voice audio). */
    fun spokenParts(): List<SpokenLine> = buildList {
        if (speech.isNotBlank()) add(SpokenLine(Speaker.HOST, speech))
        addAll(lines)
        quiz?.let { add(SpokenLine(Speaker.HOST, QuizJudge.spokenQuestion(it))) }
    }

    companion object {
        private val mapper = ObjectMapper()

        /**
         * Lenient parse of the model's reply. Expects a JSON object, but tolerates code fences,
         * leading chatter, or plain prose (which is then treated as speech).
         */
        fun parse(raw: String): Segment {
            val text = raw.trim()
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) {
                runCatching { mapper.readTree(text.substring(start, end + 1)) }.getOrNull()?.let { node ->
                    fromJson(node)?.let { return it }
                }
            }
            val prose = text.removePrefix("```").removeSuffix("```").trim()
            return Segment(speech = prose)
        }

        private fun JsonNode.str(field: String): String? = path(field).takeIf { it.isTextual }?.asText()?.trim()?.ifEmpty { null }
        private fun JsonNode.strings(field: String): List<String> =
            path(field).takeIf { it.isArray }?.mapNotNull { it.asText().trim().ifEmpty { null } } ?: emptyList()

        private fun song(n: JsonNode): SongPick? {
            if (!n.isObject) return null
            val artist = n.str("artist") ?: return null
            val title = n.str("title") ?: return null
            return SongPick(artist, title)
        }

        private fun fromJson(n: JsonNode): Segment? {
            val speech = n.str("speech") ?: ""
            val lines = n.path("lines").takeIf { it.isArray }?.mapNotNull { l ->
                val t = l.str("text") ?: return@mapNotNull null
                val who = if (l.str("who")?.lowercase() == "cohost") Speaker.COHOST else Speaker.HOST
                SpokenLine(who, t)
            } ?: emptyList()
            val quiz = n.path("quiz").takeIf { it.isObject }?.let { q ->
                val question = q.str("question") ?: return@let null
                val answer = q.str("answer") ?: return@let null
                QuizQuestion(
                    question = question,
                    choices = q.strings("choices"),
                    answer = answer,
                    accept = q.strings("accept"),
                    rightLine = q.str("right"),
                    wrongLine = q.str("wrong"),
                    fact = q.str("fact"),
                    tune = song(q.path("tune")),
                )
            }
            val song = song(n.path("song"))
            if (speech.isEmpty() && song == null && lines.isEmpty() && quiz == null) return null
            return Segment(
                speech = speech,
                song = song,
                introTag = n.str("intro_tag"),
                listen = n.path("listen").asBoolean(false),
                remember = n.strings("remember"),
                lines = lines,
                quiz = quiz,
            )
        }
    }
}
