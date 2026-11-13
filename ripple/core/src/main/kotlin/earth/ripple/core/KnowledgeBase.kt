package earth.ripple.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A label the on-device vision model produced, with its confidence in 0..1. */
data class Label(val text: String, val confidence: Float)

@Serializable
internal data class KbEntry(
    val id: String,
    val name: String,
    val emoji: String,
    val kind: Kind,
    val labels: List<String>,
    val unit: String,
    val headline: String,
    val s: List<Double>,
    val why: List<String>,
    val m: List<String>,
    val stages: List<String> = emptyList(),
    val deps: List<String> = emptyList(),
    val alts: List<String> = emptyList(),
    val qa: List<String> = emptyList(),
    val src: List<String> = emptyList(),
    val generic: Boolean = false,
) {
    fun toAssessment(): Assessment = Assessment(
        id = id,
        name = name,
        emoji = emoji,
        kind = kind,
        unit = unit,
        headline = headline,
        scores = s.indices.map { i ->
            DimensionScore(
                score = Rubric.snap(s[i]),
                why = why[i],
                metric = m[i],
                // A published quantity backs the score; otherwise it is a judgement from the literature.
                confidence = if (generic) Confidence.LOW else if (m[i].isNotEmpty()) Confidence.MEDIUM else Confidence.LOW,
            )
        },
        stages = stages.map { val p = it.split('|'); Stage(p[0], p[1].toInt(), p.getOrElse(2) { "" }) },
        dependencies = deps.map { val p = it.split('|'); Dependency(p[0], p.getOrElse(1) { "" }) },
        alternatives = alts.map {
            val p = it.split('|')
            Alternative(
                name = p[0],
                why = p[1],
                scores = p[2].split(',').map { v -> Rubric.snap(v.toDouble()) },
                effort = Effort.valueOf(p[3]),
                co2KgPerYear = p[4].toDouble(),
                waterLPerYear = p[5].toDouble(),
            )
        },
        questions = qa.map { val p = it.split('|'); QA(p[0], p.getOrElse(1) { "" }) },
        sources = src,
        tier = Tier.OFFLINE,
        generic = generic,
    )
}

/**
 * The bundled, offline library of common objects. It answers instantly and without a network,
 * and is what the user sees first; richer sources replace it when they arrive.
 */
object KnowledgeBase {
    private val json = Json { ignoreUnknownKeys = true }

    private val entries: List<KbEntry> by lazy {
        listOf("kb_food.json", "kb_things.json", "kb_living.json").flatMap { file ->
            val text = KnowledgeBase::class.java.classLoader!!.getResourceAsStream(file)!!
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            json.decodeFromString<List<KbEntry>>(text)
        }
    }

    val all: List<Assessment> by lazy { entries.map { it.toAssessment() } }

    fun byId(id: String): Assessment? = entries.firstOrNull { it.id == id }?.toAssessment()

    val unknown: Assessment get() = byId("unknown")!!

    data class Match(val assessment: Assessment, val strength: Float)

    /**
     * Picks the best entry for what the camera saw. Specific entries beat generic ones, an exact
     * label beats a partial one, and labels listed earlier in an entry count as more characteristic.
     */
    fun match(labels: List<Label>): Match {
        var best: KbEntry? = null
        var bestScore = 0f
        for (entry in entries) {
            var score = 0f
            for (label in labels) {
                val seen = label.text.trim().lowercase()
                if (seen.isEmpty()) continue
                entry.labels.forEachIndexed { index, own ->
                    val quality = when {
                        seen == own -> 1.0f
                        containsPhrase(seen, own) -> 0.8f
                        seen.length >= 4 && containsPhrase(own, seen) -> 0.55f
                        else -> 0f
                    }
                    if (quality > 0f) {
                        val position = 1f - 0.2f * (index.toFloat() / entry.labels.size)
                        val s = label.confidence * quality * position * (if (entry.generic) 0.5f else 1f)
                        if (s > score) score = s
                    }
                }
            }
            if (score > bestScore) { bestScore = score; best = entry }
        }
        return if (best == null || bestScore < 0.2f) Match(unknown, 0f) else Match(best.toAssessment(), bestScore)
    }

    /** True when [needle] occurs in [haystack] on word boundaries ("water" in "water bottle", not in "watermelon"). */
    private fun containsPhrase(haystack: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val i = haystack.indexOf(needle, from)
            if (i < 0) return false
            val before = i == 0 || !haystack[i - 1].isLetterOrDigit()
            val end = i + needle.length
            val after = end == haystack.length || !haystack[end].isLetterOrDigit()
            if (before && after) return true
            from = i + 1
        }
    }
}
