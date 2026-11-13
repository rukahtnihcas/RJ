package earth.ripple.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The phrases the on-device vision model can recognise, each pre-embedded at build time by the
 * model's text encoder (see tools/recognizer.py). On the phone, a photo's embedding is compared
 * with all of them; phrases that point to the same library entry add up.
 */
class Vocabulary(val ids: List<String>, val phrases: List<String>, private val embeds: FloatArray, val dim: Int) {
    init {
        require(ids.size == phrases.size && embeds.size == ids.size * dim) { "vocabulary and embeddings do not line up" }
    }

    /** One possible identity: a library entry, how likely it is, and the phrase that matched best. */
    data class Guess(val id: String, val probability: Float, val phrase: String) {
        /** "a pair of jeans" → "Jeans", "a cup of coffee" → "Cup of coffee". */
        val display: String get() = displayName(phrase)
    }

    fun rank(image: FloatArray, limit: Int = 5): List<Guess> {
        require(image.size == dim) { "image embedding has ${image.size} values, expected $dim" }
        var n = 0f
        for (v in image) n += v * v
        val inv = 1f / sqrt(n).coerceAtLeast(1e-12f)
        val sims = FloatArray(ids.size)
        var best = Float.NEGATIVE_INFINITY
        for (i in ids.indices) {
            var s = 0f
            val o = i * dim
            for (d in 0 until dim) s += embeds[o + d] * image[d]
            sims[i] = s * inv
            if (sims[i] > best) best = sims[i]
        }
        // Softmax with CLIP's usual temperature, then add up per library entry.
        val total = DoubleArray(1)
        val p = DoubleArray(ids.size) { exp(((sims[it] - best) * SCALE).toDouble()).also { v -> total[0] += v } }
        val perId = LinkedHashMap<String, Double>()
        val bestPhrase = HashMap<String, Int>()
        for (i in ids.indices) {
            perId[ids[i]] = (perId[ids[i]] ?: 0.0) + p[i] / total[0]
            val cur = bestPhrase[ids[i]]
            if (cur == null || sims[i] > sims[cur]) bestPhrase[ids[i]] = i
        }
        return perId.entries.sortedByDescending { it.value }.take(limit)
            .map { Guess(it.key, it.value.toFloat(), phrases[bestPhrase[it.key]!!]) }
    }

    companion object {
        const val SCALE = 100f

        /** [labels] is the tab-separated id/phrase list; [embeddings] is little-endian float32, row per phrase. */
        fun load(labels: String, embeddings: ByteArray, dim: Int): Vocabulary {
            val rows = labels.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
            val buf = ByteBuffer.wrap(embeddings).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val arr = FloatArray(buf.remaining()).also { buf.get(it) }
            return Vocabulary(rows.map { it[0].trim() }, rows.map { it[1].trim() }, arr, dim)
        }

        fun displayName(phrase: String): String {
            var p = phrase.trim()
            for (lead in listOf("a pair of ", "a bunch of ", "a pack of ", "a photo of ", "some ", "an ", "a ", "the ")) {
                if (p.startsWith(lead)) { p = p.removePrefix(lead); break }
            }
            return p.replaceFirstChar { it.uppercase() }
        }
    }
}

/** The library entry to show for a recognised object, named after what was actually seen when the entry is a broad category. */
fun KnowledgeBase.fromGuess(g: Vocabulary.Guess): Assessment? {
    val a = byId(g.id) ?: return null
    return if (a.generic) a.copy(name = g.display, generic = false, headline = a.headline) else a
}
