package earth.ripple.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Everything the AI tier needs that is not network code: the instructions, the output schema and
 * the parser. Kept free of any SDK so it can be tested on its own.
 */
object AiProtocol {
    const val DEFAULT_MODEL = "claude-opus-5-5"

    private val keys = listOf("carbon", "water", "health", "nature", "social")

    val systemPrompt: String = """
You are the analysis engine of Ripple, a phone app. A person points their camera at something —
any object, food, product, plant, animal or place — and you tell them what it does to the world
across five dimensions. People use this to get curious about sustainability and to make better
choices, so what you write must be accurate, specific and worth repeating to a friend.

You receive a photo, plus hints: labels from a small on-device vision model (often wrong or vague;
trust the photo over the hints) and sometimes a product database record or the user's country.

WHAT TO DO
1. Identify the main subject as specifically as the photo allows: material, type, brand if legible.
   If you genuinely cannot tell, set identified=false and assess the broad category you can see.
2. Score it on the rubric below. Think about the whole life cycle and supply chain, not only use.
3. Write for a curious non-expert. Plain words, concrete numbers, no jargon, no moralising.

${Rubric.text}

FIELDS
- name: 2–5 words, what a person would call it ("Cotton T-shirt", "Oak tree", "Diesel SUV").
- emoji: one emoji for it.
- unit: the functional unit you scored, as a short phrase.
- headline: ONE or two sentences, the single most surprising true fact about this thing's impact,
  ideally with a number and an everyday comparison. This is what people share. Do not open with
  "Did you know". Do not exaggerate; if a figure is contested, pick the conservative one.
- scores.*.why: one or two sentences each, specific to this object, naming the mechanism.
- scores.*.metric: a physical quantity with unit and "≈" if you know a published one, else "".
- stages: 4–7 life-cycle stages in order, weight 0–3 (3 = the hotspot), with a few words of note.
- dependencies: 2–4 things this object silently depends on — an ecosystem, a mineral, a group of
  workers, another species — each with one sentence on why that matters.
- alternatives: 2–4 better choices the person could realistically make, most effective first.
  Include at least one that costs nothing (use it longer, repair, share, choose differently next time).
  Give each its own five scores on the same rubric, an effort level, and a rough yearly saving for
  a typical person in kg CO₂e and litres of water (0 if unknown). For living things and places the
  "alternatives" are ways to care for or protect it.
- critical: the 1–3 materials, ingredients or processes INSIDE this object that carry most of its
  impact and that a buyer should be mindful of (e.g. "Cobalt in the battery", "Cotton growing",
  "Palm oil", "Chrome tanning"). One sentence each on why it matters.
- look_for: 4–7 things to check when choosing or buying one. Lead with independently audited
  certifications, ratings and labels that genuinely apply to this product type (e.g. GOTS,
  Fairtrade, FSC, MSC, EU energy label, repairability score, TCO Certified), each with what it
  actually guarantees, and set trust=TRUSTED. Then include 1–3 common claims on this kind of
  product that sound good but prove little ("eco-friendly", "carbon neutral", "vegan leather",
  own-brand schemes) with why, and set trust=CAUTION. If a label or claim is visible in the photo,
  name it and say what it is worth. Only real schemes; never invent a certification.
- questions: 3 short follow-up questions a curious person would ask next about THIS object.
- sources: 2–5 named studies, datasets or organisations your figures rest on. Only real ones.

PEOPLE
If the main subject is a person, never assess or judge that individual. Name it "A human", score
the world-average lifestyle for one year on carbon, freshwater and nature, set health and social
to 0 with the explanation that those are up to them, and make the alternatives the highest-impact
personal choices. Do not describe the person's appearance or guess anything about them.
""".trim()

    val chatSystemPrompt: String = """
You are the guide inside Ripple, an app that shows the sustainability impact of whatever a person
points their camera at. The person is looking at an assessment (given below as JSON) and asks a
follow-up question. Answer in 60–130 words of plain prose: no headings, no bullet lists, no
markdown. Be concrete, use numbers where you are confident of them, explain mechanisms, and say
plainly when something is uncertain or debated. Connect the answer to what the person can do when
that is natural, without lecturing. If the question is unrelated to the object or to sustainability,
answer briefly and steer back.
""".trim()

    /** JSON Schema for the structured response. Every object is closed and every field required. */
    val schema: String = run {
        fun obj(props: String, required: List<String>) =
            """{"type":"object","properties":{$props},"required":[${required.joinToString(",") { "\"$it\"" }}],"additionalProperties":false}"""
        val score = obj(
            """"score":{"type":"number"},"why":{"type":"string"},"metric":{"type":"string"},"confidence":{"type":"string","enum":["LOW","MEDIUM","HIGH"]}""",
            listOf("score", "why", "metric", "confidence"),
        )
        val five = obj(keys.joinToString(",") { "\"$it\":{\"type\":\"number\"}" }, keys)
        val scores = obj(keys.joinToString(",") { "\"$it\":$score" }, keys)
        val stage = obj(""""name":{"type":"string"},"weight":{"type":"integer"},"note":{"type":"string"}""", listOf("name", "weight", "note"))
        val dep = obj(""""name":{"type":"string"},"note":{"type":"string"}""", listOf("name", "note"))
        val alt = obj(
            """"name":{"type":"string"},"why":{"type":"string"},"effort":{"type":"string","enum":["EASY","MEDIUM","BIG"]},"scores":$five,"co2_kg_per_year":{"type":"number"},"water_l_per_year":{"type":"number"}""",
            listOf("name", "why", "effort", "scores", "co2_kg_per_year", "water_l_per_year"),
        )
        val hotspot = obj(""""name":{"type":"string"},"why":{"type":"string"}""", listOf("name", "why"))
        val signal = obj(
            """"name":{"type":"string"},"meaning":{"type":"string"},"trust":{"type":"string","enum":["TRUSTED","CAUTION"]}""",
            listOf("name", "meaning", "trust"),
        )
        fun arr(item: String) = """{"type":"array","items":$item}"""
        obj(
            """"identified":{"type":"boolean"},"name":{"type":"string"},"emoji":{"type":"string"},""" +
                """"kind":{"type":"string","enum":[${Kind.entries.joinToString(",") { "\"${it.name}\"" }}]},""" +
                """"unit":{"type":"string"},"headline":{"type":"string"},"scores":$scores,""" +
                """"stages":${arr(stage)},"dependencies":${arr(dep)},"alternatives":${arr(alt)},"critical":${arr(hotspot)},"look_for":${arr(signal)},""" +
                """"questions":${arr("""{"type":"string"}""")},"sources":${arr("""{"type":"string"}""")}""",
            listOf("identified", "name", "emoji", "kind", "unit", "headline", "scores", "stages", "dependencies", "alternatives", "critical", "look_for", "questions", "sources"),
        )
    }

    /** The text that accompanies the photo. */
    fun userPrompt(labels: List<Label>, coarse: String?, productRecord: String?, country: String?): String = buildString {
        append("Assess the main subject of this photo.\n")
        if (labels.isNotEmpty()) append("On-device labels (unreliable): ")
            .append(labels.take(6).joinToString(", ") { "${it.text} ${(it.confidence * 100).toInt()}%" }).append('\n')
        if (!coarse.isNullOrBlank()) append("On-device coarse category: ").append(coarse).append('\n')
        if (!country.isNullOrBlank()) append("User's country code: ").append(country).append(" (use for grid mix, water stress and locally available alternatives)\n")
        if (!productRecord.isNullOrBlank()) append("A barcode was read. Product database record (reliable for identity and listed grades):\n")
            .append(productRecord).append('\n')
    }

    fun chatContext(a: Assessment): String = Json.encodeToString(Assessment.serializer(), a)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses the model's JSON. Scores are clamped and snapped; malformed output returns null. */
    fun parse(text: String, id: String): Assessment? = runCatching {
        val o = json.parseToJsonElement(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).jsonObject
        val s = o["scores"] as JsonObject
        Assessment(
            id = id,
            name = o.str("name").ifBlank { "Something" },
            emoji = o.str("emoji").ifBlank { "◎" },
            kind = runCatching { Kind.valueOf(o.str("kind")) }.getOrDefault(Kind.OTHER),
            unit = o.str("unit"),
            headline = o.str("headline"),
            scores = keys.map { k ->
                val d = s[k] as JsonObject
                DimensionScore(
                    score = Rubric.snap(d.num("score")),
                    why = d.str("why"),
                    metric = d.str("metric"),
                    confidence = runCatching { Confidence.valueOf(d.str("confidence")) }.getOrDefault(Confidence.LOW),
                )
            },
            stages = o.arr("stages").map { Stage(it.str("name"), it.num("weight").toInt().coerceIn(0, 3), it.str("note")) },
            dependencies = o.arr("dependencies").map { Dependency(it.str("name"), it.str("note")) },
            alternatives = o.arr("alternatives").map { alt ->
                val sc = alt["scores"] as JsonObject
                Alternative(
                    name = alt.str("name"),
                    why = alt.str("why"),
                    scores = keys.map { Rubric.snap(sc.num(it)) },
                    effort = runCatching { Effort.valueOf(alt.str("effort")) }.getOrDefault(Effort.MEDIUM),
                    co2KgPerYear = alt.num("co2_kg_per_year").coerceAtLeast(0.0),
                    waterLPerYear = alt.num("water_l_per_year").coerceAtLeast(0.0),
                )
            },
            critical = o.arr("critical").map { Hotspot(it.str("name"), it.str("why")) },
            lookFor = o.arr("look_for").map { Signal(it.str("name"), it.str("meaning"), it.str("trust") != "CAUTION") },
            questions = (o["questions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.map { QA(it) } ?: emptyList(),
            sources = (o["sources"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            tier = Tier.AI,
            generic = (o["identified"] as? JsonPrimitive)?.booleanOrNull == false,
        )
    }.getOrNull()

    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
    private fun JsonObject.num(k: String): Double = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: 0.0
    private fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
}
