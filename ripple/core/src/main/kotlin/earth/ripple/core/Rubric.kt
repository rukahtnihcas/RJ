package earth.ripple.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The scoring rules. Everything that produces a score — the bundled knowledge base, the product
 * database mapping and the AI model — is held to this one rubric so that scores are comparable.
 */
object Rubric {
    const val MIN = -5.0
    const val MAX = 5.0

    fun clamp(v: Double): Double = v.coerceIn(MIN, MAX)

    /** Scores are shown in half-point steps: finer than that would claim precision we don't have. */
    fun snap(v: Double): Double = (clamp(v) * 2).roundToInt() / 2.0

    /**
     * Carbon harm is logarithmic: each point is ten times more.
     * 0.01 kg CO₂e → 0, 0.1 kg → −1, 1 kg → −2, 10 kg → −3, 100 kg → −4, ≥ 1 t → −5.
     */
    fun carbonFromKg(kg: Double): Double =
        if (kg <= 0.01) 0.0 else snap(-(log10(kg) + 2.0))

    /**
     * Freshwater harm, same shape, in litres consumed or polluted.
     * 1 L → 0, 10 L → −1, 100 L → −2, 1,000 L → −3, 10,000 L → −4, ≥ 100,000 L → −5.
     */
    fun waterFromLitres(l: Double): Double =
        if (l <= 1.0) 0.0 else snap(-log10(l))

    fun format(v: Double): String {
        val s = snap(v)
        val body = if (s == s.toLong().toDouble()) abs(s).toLong().toString() else abs(s).toString()
        return when {
            s > 0 -> "+$body"
            s < 0 -> "−$body"
            else -> "0"
        }
    }

    fun word(v: Double): String = when {
        v <= -4.0 -> "Severe harm"
        v <= -2.5 -> "Heavy harm"
        v <= -1.0 -> "Some harm"
        v < 0.0 -> "Slight harm"
        v == 0.0 -> "No real impact"
        v < 1.0 -> "Slight good"
        v < 2.5 -> "Some good"
        v < 4.0 -> "Strong good"
        else -> "Regenerative"
    }

    /** One-word overall verdict for an object, from the mean of its five scores. */
    fun verdict(avg: Double): String = when {
        avg <= -3.0 -> "Heavy footprint"
        avg <= -1.5 -> "Costly"
        avg < -0.4 -> "Light footprint"
        avg <= 0.4 -> "Roughly neutral"
        avg < 2.0 -> "Gives back"
        else -> "Life-giving"
    }

    /** The rubric as prose, given verbatim to the AI model so it scores the way the app does. */
    val text: String = """
SCALE
Every dimension is scored from −5 to +5 in steps of 0.5.
  0  = no meaningful impact.
  −5 = severe, well-evidenced harm.   +5 = strongly regenerative or life-supporting.
A score is the net effect on the world of this thing existing and being used the typical way,
compared with it not existing. If the thing's main job is to displace something worse (a bicycle
replacing car trips, a solar panel replacing grid power, a bus replacing cars), credit the
displacement and say so in the explanation. Never give credit for merely being "less bad".

FUNCTIONAL UNIT (state it in `unit`)
  Food and drink: one typical serving, or the pack shown.
  Things you buy once: one item over its whole life, from raw material to disposal.
  Things that run or are used continuously (vehicles, appliances, buildings, taps): one year of typical use, plus a fair share of manufacture.
  Living things and places: one year of that organism or place existing.

CARBON (kg CO₂e, full life cycle) — logarithmic
  0: < 0.01 kg   −1: ~0.1 kg   −2: ~1 kg   −3: ~10 kg   −4: ~100 kg   −5: ≥ 1,000 kg
  Positive scores mirror this for net removal or avoided emissions (+3 ≈ 10 kg removed/avoided a year).

FRESHWATER (litres consumed or polluted, weighted up where water is scarce) — logarithmic
  0: < 1 L   −1: ~10 L   −2: ~100 L   −3: ~1,000 L   −4: ~10,000 L   −5: ≥ 100,000 L
  Positive scores are for things that clean, store, recharge or save freshwater.

HEALTH (the user, workers, neighbours — across the life cycle)
  −5 proven to kill at scale (tobacco)   −3 clear chronic harm (ultra-processed diet, air pollution source)
  −1 minor or uncertain risk   0 none   +1 mild benefit   +3 clear benefit (fresh vegetables, clean water)
  +5 among the best things for health (daily active travel, sanitation)

NATURE & BIODIVERSITY (land use, habitat, pollution, wildlife)
  −5 a leading driver of ecosystem destruction (deforestation-linked beef, bottom trawling)
  −3 significant land/pollution pressure   −1 small pressure   0 none
  +3 supports wildlife or soil   +5 a keystone of its ecosystem (native tree, pollinators, wetland)

SOCIAL (livelihoods, labour rights, equity, community, culture — across the supply chain)
  −5 strongly linked to forced or child labour or conflict   −3 documented exploitation is common
  −1 some risk or unfairness   0 none   +1 mild benefit   +3 clear community benefit
  +5 transformative for wellbeing or opportunity (schools, public transport, books)

HONESTY
Use published life-cycle figures where you know them and give the number in `metric`
(e.g. "≈ 7 kg CO₂e", "≈ 2,700 L"). Leave `metric` empty rather than invent one.
Set confidence HIGH only when the product is specifically identified and well studied, MEDIUM for
a typical item of a well-studied category, LOW for a judgement call.
""".trim()
}
