package com.rj.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

data class SongPick(val artist: String, val title: String) {
    val query: String get() = "$title $artist"
    override fun toString() = "$title – $artist"
}

/** One "on-air" moment produced by the brain. */
data class Segment(
    /** What the RJ says before the next song. */
    val speech: String,
    /** The next song to play, or null to keep the current music going. */
    val song: SongPick? = null,
    /** Short line to talk over the song's intro (radio-style, music ducked). */
    val introTag: String? = null,
    /** RJ asked the driver a question: open the mic after speaking. */
    val listen: Boolean = false,
    /** New long-term facts about the driver worth remembering. */
    val remember: List<String> = emptyList(),
) {
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
            return Segment(speech = prose.ifBlank { "" })
        }

        private fun fromJson(n: JsonNode): Segment? {
            val speech = n.path("speech").asText("").trim()
            val songNode = n.path("song")
            val song = if (songNode.isObject) {
                val artist = songNode.path("artist").asText("").trim()
                val title = songNode.path("title").asText("").trim()
                if (artist.isNotEmpty() && title.isNotEmpty()) SongPick(artist, title) else null
            } else null
            if (speech.isEmpty() && song == null) return null
            return Segment(
                speech = speech,
                song = song,
                introTag = n.path("intro_tag").asText("").trim().ifEmpty { null },
                listen = n.path("listen").asBoolean(false),
                remember = n.path("remember").takeIf { it.isArray }?.mapNotNull { it.asText().trim().ifEmpty { null } }
                    ?: emptyList(),
            )
        }
    }
}
