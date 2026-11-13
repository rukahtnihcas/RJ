package earth.ripple.core

import kotlinx.serialization.Serializable

/** The five lenses every object is viewed through. Order is the order of the chart's axes. */
enum class Dimension(val label: String, val short: String, val glyph: String, val question: String) {
    CARBON("Carbon", "CO₂", "☁", "What does it do to the climate?"),
    WATER("Freshwater", "H₂O", "💧", "How much freshwater does it take or protect?"),
    HEALTH("Health", "Health", "♥", "What does it do to human health?"),
    NATURE("Nature & biodiversity", "Nature", "❀", "What does it do to land, sea and wildlife?"),
    SOCIAL("Social", "People", "☺", "What does it do to people and communities?"),
}

enum class Kind { FOOD, FASHION, ELECTRONICS, HOME, TRANSPORT, LIVING, PLACE, OTHER }

/** Where an assessment came from, from weakest to strongest evidence. */
enum class Tier(val label: String) {
    OFFLINE("On-device estimate"),
    PRODUCT_DATA("Product database"),
    AI("AI-refined"),
}

enum class Confidence { LOW, MEDIUM, HIGH }

enum class Effort(val label: String) { EASY("Easy"), MEDIUM("Some effort"), BIG("Big move") }

@Serializable
data class DimensionScore(
    /** −5 (severe harm) … 0 (no meaningful impact) … +5 (strongly regenerative). */
    val score: Double,
    val why: String,
    /** A physical quantity when one is known, e.g. "≈ 7 kg CO₂e". Empty when the score is a judgement. */
    val metric: String = "",
    val confidence: Confidence = Confidence.LOW,
)

@Serializable
data class Stage(val name: String, /** 0 = negligible … 3 = the hotspot. */ val weight: Int, val note: String = "")

@Serializable
data class Dependency(val name: String, val note: String)

@Serializable
data class Alternative(
    val name: String,
    val why: String,
    /** Five scores in [Dimension] order. */
    val scores: List<Double>,
    val effort: Effort = Effort.EASY,
    /** Rough yearly saving if a typical person makes this switch. Zero when unknown. */
    val co2KgPerYear: Double = 0.0,
    val waterLPerYear: Double = 0.0,
)

/** The one material or process inside the object that carries most of its impact. */
@Serializable
data class Hotspot(val name: String, val why: String)

/**
 * Something to check for when choosing: a certification, rating or label that is independently
 * verified ([trusted]), or a common claim that sounds good and proves little (not trusted).
 */
@Serializable
data class Signal(val name: String, val meaning: String, val trusted: Boolean = true)

@Serializable
data class QA(val q: String, val a: String = "")

@Serializable
data class Assessment(
    val id: String,
    val name: String,
    val emoji: String = "◎",
    val kind: Kind = Kind.OTHER,
    /** What exactly is being scored — without this a score means nothing. */
    val unit: String,
    /** The one sentence someone repeats to a friend. */
    val headline: String,
    /** Five scores in [Dimension] order. */
    val scores: List<DimensionScore>,
    val stages: List<Stage> = emptyList(),
    val dependencies: List<Dependency> = emptyList(),
    val alternatives: List<Alternative> = emptyList(),
    /** What to be mindful of inside this object. */
    val critical: List<Hotspot> = emptyList(),
    /** What to look for, and what to be wary of, when choosing one. */
    val lookFor: List<Signal> = emptyList(),
    val questions: List<QA> = emptyList(),
    val sources: List<String> = emptyList(),
    val tier: Tier = Tier.OFFLINE,
    /** True when we could not tell what the object is and fell back to a broad category. */
    val generic: Boolean = false,
) {
    init { require(scores.size == 5) { "need 5 dimension scores, got ${scores.size}" } }

    fun score(d: Dimension): DimensionScore = scores[d.ordinal]
    val values: List<Double> get() = scores.map { it.score }
    val average: Double get() = values.average()

    /** The dimension where this object matters most, in either direction. */
    val standout: Dimension get() = Dimension.entries.maxBy { kotlin.math.abs(scores[it.ordinal].score) }
    val worst: Dimension get() = Dimension.entries.minBy { scores[it.ordinal].score }
    val best: Dimension get() = Dimension.entries.maxBy { scores[it.ordinal].score }
}
