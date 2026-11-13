package earth.ripple.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Turns an Open Food Facts product record (scanned by barcode) into an assessment.
 * Carbon comes from the product's own life-cycle data (Agribalyse, via the Eco-Score), health from
 * Nutri-Score and NOVA, nature from the Eco-Score grade and labels. Freshwater and social are not
 * in the database, so they are estimated from the product's category and flagged as low confidence.
 */
object OpenFoodFacts {
    const val FIELDS = "product_name,brands,quantity,product_quantity,ecoscore_grade,ecoscore_score,nutriscore_grade," +
        "nova_group,ecoscore_data,packagings,labels_tags,categories_tags,ingredients_analysis_tags"

    fun url(barcode: String): String = "https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=$FIELDS"

    private val json = Json { ignoreUnknownKeys = true }

    /** Litres of water per kilo of product, by category keyword. Water Footprint Network averages. */
    private val waterPerKg: List<Pair<String, Double>> = listOf(
        "chocolate" to 17000.0, "cocoa" to 17000.0, "coffee" to 18900.0, "beef" to 15400.0, "olive-oil" to 14400.0,
        "nuts" to 9000.0, "tea" to 8900.0, "lamb" to 10400.0, "pork" to 6000.0, "butter" to 5500.0,
        "chicken" to 4300.0, "poultry" to 4300.0, "meat" to 6000.0, "egg" to 3300.0, "cheese" to 3200.0,
        "rice" to 2500.0, "pasta" to 1850.0, "sugar" to 1800.0, "bread" to 1600.0, "cereal" to 1600.0,
        "biscuit" to 1800.0, "milk" to 1000.0, "yogurt" to 1000.0, "dairy" to 1000.0, "wine" to 870.0,
        "fruit" to 960.0, "juice" to 1000.0, "beer" to 300.0, "vegetable" to 320.0, "soda" to 350.0,
        "beverage" to 350.0, "water" to 3.0,
    )

    /** Ingredients whose supply chains carry well-documented labour risks. */
    private val socialRisk = listOf("chocolate", "cocoa", "coffee", "tea", "palm", "sugar", "banana", "seafood", "fish", "hazelnut")

    private val fairLabels = listOf("fair-trade", "fairtrade", "rainforest-alliance", "utz", "max-havelaar", "fair-for-life")

    /** Returns null when the barcode is not in the database or the record is too empty to use. */
    fun parse(body: String, barcode: String): Assessment? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val p = root["product"] as? JsonObject ?: return null
        val name = p.str("product_name")?.takeIf { it.isNotBlank() } ?: return null
        val brand = p.str("brands")?.split(',')?.firstOrNull()?.trim().orEmpty()
        val categories = p.tags("categories_tags")
        val labels = p.tags("labels_tags")
        val analysis = p.tags("ingredients_analysis_tags")
        val catText = categories.joinToString(" ")
        val grams = p.num("product_quantity")?.takeIf { it > 0 }
        val kg = (grams ?: 100.0) / 1000.0
        val packLabel = if (grams != null) p.str("quantity")?.takeIf { it.isNotBlank() } ?: "${grams.toInt()} g" else "100 g"

        // Carbon: measured for this product's food type.
        val agri = (p["ecoscore_data"] as? JsonObject)?.get("agribalyse") as? JsonObject
        val co2PerKg = agri?.num("co2_total")
        val carbon = if (co2PerKg != null) {
            val total = co2PerKg * kg
            DimensionScore(
                Rubric.carbonFromKg(total),
                "Life-cycle data for this type of food gives ${fmt(co2PerKg)} kg CO₂e per kilo. " + hotspot(agri),
                "≈ ${fmt(total)} kg CO₂e", Confidence.HIGH,
            )
        } else DimensionScore(-2.0, "No life-cycle data for this product; shown as a typical packaged food.", "", Confidence.LOW)

        // Freshwater: estimated from category.
        val waterHit = waterPerKg.firstOrNull { (k, _) -> catText.contains(k) }
        val water = if (waterHit != null) {
            val litres = waterHit.second * kg
            DimensionScore(
                Rubric.waterFromLitres(litres),
                "Estimated from the average water footprint of ${waterHit.first.replace('-', ' ')} products. Not specific to this brand.",
                "≈ ${fmt(litres)} L", Confidence.LOW,
            )
        } else DimensionScore(-2.0, "Not in the product database; shown as a typical packaged food.", "", Confidence.LOW)

        // Health: Nutri-Score, adjusted for ultra-processing.
        val nutri = p.str("nutriscore_grade")?.lowercase()
        val nova = p.num("nova_group")?.toInt()
        val nutriScore = mapOf("a" to 3.0, "b" to 1.5, "c" to 0.0, "d" to -1.5, "e" to -3.0)[nutri]
        val health = if (nutriScore != null) {
            val adj = nutriScore - (if (nova == 4) 1.0 else 0.0)
            DimensionScore(
                Rubric.snap(adj),
                "Nutri-Score ${nutri!!.uppercase()}" + when (nova) {
                    4 -> ", and classed as ultra-processed (NOVA 4), which is linked to worse long-term health."
                    1 -> ", unprocessed or minimally processed (NOVA 1)."
                    null -> "."
                    else -> " (NOVA $nova)."
                },
                "Nutri-Score ${nutri.uppercase()}", Confidence.HIGH,
            )
        } else DimensionScore(if (nova == 4) -1.5 else 0.0,
            if (nova == 4) "No Nutri-Score, but classed as ultra-processed (NOVA 4)." else "No nutrition grade available for this product.",
            "", Confidence.LOW)

        // Nature: Eco-Score grade, palm oil, organic.
        val eco = p.str("ecoscore_grade")?.lowercase()
        val ecoScore = mapOf("a-plus" to 0.5, "a" to 0.0, "b" to -1.0, "c" to -2.0, "d" to -3.0, "e" to -4.0, "f" to -4.5)[eco]
        val palm = analysis.any { it.endsWith(":palm-oil") }
        val organic = labels.any { it.contains("organic") || it.contains("bio") }
        val nature = if (ecoScore != null) {
            val adj = ecoScore - (if (palm) 0.5 else 0.0) + (if (organic) 0.5 else 0.0)
            DimensionScore(
                Rubric.snap(adj),
                "Eco-Score ${eco!!.uppercase().replace("-PLUS", "+")}: a life-cycle score covering land use, pollution and biodiversity." +
                    (if (palm) " Contains palm oil, a driver of tropical deforestation unless certified." else "") +
                    (if (organic) " Certified organic, which avoids synthetic pesticides." else ""),
                "Eco-Score ${eco.uppercase().replace("-PLUS", "+")}", Confidence.HIGH,
            )
        } else DimensionScore(if (palm) -3.0 else -2.0,
            "No Eco-Score for this product." + (if (palm) " Contains palm oil." else ""), "", Confidence.LOW)

        // Social: certification or known-risk ingredients.
        val fair = labels.any { l -> fairLabels.any { l.contains(it) } }
        val risk = socialRisk.firstOrNull { catText.contains(it) || (it == "palm" && palm) }
        val social = when {
            fair -> DimensionScore(1.5, "Carries a fair-trade or similar certification, which sets minimum prices and labour standards for producers.", "Certified", Confidence.MEDIUM)
            risk != null -> DimensionScore(-2.5, "Contains ${if (risk == "palm") "palm oil" else risk}, whose supply chains have well-documented problems with low farmer incomes or child and forced labour. No certification is listed for this product.", "", Confidence.LOW)
            else -> DimensionScore(-0.5, "No specific information about working conditions in this product's supply chain.", "", Confidence.LOW)
        }

        val scores = listOf(carbon, water, health, nature, social)
        val weakest = Dimension.entries.minBy { scores[it.ordinal].score }
        val display = if (brand.isNotEmpty() && !name.contains(brand, ignoreCase = true)) "$name ($brand)" else name

        return Assessment(
            id = "off-$barcode",
            name = display,
            emoji = "🛒",
            kind = Kind.FOOD,
            unit = "one pack ($packLabel)",
            headline = headline(display, scores, weakest, eco, nutri, palm),
            scores = scores,
            stages = stages(agri),
            dependencies = buildList {
                if (palm) add(Dependency("Palm oil plantations", "Mostly in Indonesia and Malaysia, on former rainforest."))
                if (risk != null && risk != "palm") add(Dependency("${risk.replaceFirstChar { it.uppercase() }} farmers", "Often smallholders earning below a living income."))
                add(Dependency("Farmland and fertiliser", "Farming is where most of any food's footprint lies."))
            },
            alternatives = alternatives(scores, weakest, palm, fair, nova),
            questions = listOf(
                QA("What is the Eco-Score?", "A grade from A to F based on a life-cycle assessment of the food type (carbon, water pollution, land use and more), adjusted for packaging, origin, labels and threatened species such as palm-oil-linked deforestation."),
                QA("How reliable is this?", "Carbon, health and nature come from this product's record in Open Food Facts, a public database. Freshwater and social scores are estimated from the product category and are much less certain."),
            ),
            sources = listOf("Open Food Facts (barcode $barcode)", "ADEME Agribalyse", "Nutri-Score", "NOVA classification", "Water Footprint Network"),
            tier = Tier.PRODUCT_DATA,
        )
    }

    private fun headline(name: String, s: List<DimensionScore>, weakest: Dimension, eco: String?, nutri: String?, palm: Boolean): String {
        val w = s[weakest.ordinal]
        return when {
            weakest == Dimension.CARBON && w.metric.isNotEmpty() -> "Making this pack emitted ${w.metric.removePrefix("≈ ")}. Its heaviest impact is on the climate."
            weakest == Dimension.WATER && w.metric.isNotEmpty() -> "This pack took ${w.metric.removePrefix("≈ ")} of water to produce, almost all of it on the farm."
            weakest == Dimension.HEALTH && nutri != null -> "Nutri-Score ${nutri.uppercase()}: the biggest cost of this one may be to you."
            weakest == Dimension.NATURE && palm -> "Contains palm oil and scores Eco-Score ${eco?.uppercase() ?: "low"}: its heaviest impact is on forests and wildlife."
            weakest == Dimension.NATURE && eco != null -> "Eco-Score ${eco.uppercase()}: its heaviest impact is on land and wildlife."
            weakest == Dimension.SOCIAL -> "The hidden cost here is human: the farmers behind its main ingredient often earn below a living income."
            else -> "$name, scored from its barcode record."
        }
    }

    private fun hotspot(agri: JsonObject): String {
        val parts = listOf("agriculture" to "farming", "processing" to "processing", "packaging" to "packaging",
            "transportation" to "transport", "distribution" to "distribution", "consumption" to "use at home")
            .mapNotNull { (k, label) -> agri.num("co2_$k")?.let { label to it } }
        val total = parts.sumOf { it.second }
        val top = parts.maxByOrNull { it.second } ?: return ""
        if (total <= 0) return ""
        return "About ${(100 * top.second / total).toInt()}% comes from ${top.first}."
    }

    private fun stages(agri: JsonObject?): List<Stage> {
        val keys = listOf("agriculture" to "Farming", "processing" to "Processing", "packaging" to "Packaging",
            "transportation" to "Transport", "distribution" to "Shops & storage", "consumption" to "At home")
        if (agri == null) return keys.map { Stage(it.second, 1) }
        val vals = keys.map { (k, label) -> label to (agri.num("co2_$k") ?: 0.0) }
        val max = vals.maxOf { it.second }.takeIf { it > 0 } ?: return keys.map { Stage(it.second, 1) }
        return vals.map { (label, v) ->
            val share = v / max
            Stage(label, when { share > 0.6 -> 3; share > 0.2 -> 2; share > 0.02 -> 1; else -> 0 }, "${fmt(v)} kg CO₂e per kg")
        }
    }

    private fun alternatives(s: List<DimensionScore>, weakest: Dimension, palm: Boolean, fair: Boolean, nova: Int?): List<Alternative> {
        val base = s.map { it.score }
        fun shifted(vararg delta: Pair<Dimension, Double>): List<Double> =
            base.toMutableList().also { for ((d, v) in delta) it[d.ordinal] = Rubric.snap(minOf(it[d.ordinal] + v, 3.0).coerceAtLeast(it[d.ordinal])) }
        return buildList {
            if (base[Dimension.NATURE.ordinal] <= -2 || base[Dimension.CARBON.ordinal] <= -2)
                add(Alternative("Same shelf, Eco-Score A or B", "Scan two or three neighbours: similar products often differ by several grades.",
                    shifted(Dimension.NATURE to 2.0, Dimension.CARBON to 1.0, Dimension.WATER to 0.5)))
            if (!fair && base[Dimension.SOCIAL.ordinal] <= -2)
                add(Alternative("A fair-trade certified version", "Guarantees producers a minimum price and bans the worst labour practices.",
                    shifted(Dimension.SOCIAL to 3.0)))
            if (palm) add(Alternative("Palm-oil-free or RSPO-certified", "Removes the deforestation risk.", shifted(Dimension.NATURE to 1.5)))
            if (nova == 4 || base[Dimension.HEALTH.ordinal] <= -1.5)
                add(Alternative("A less processed option", "Fewer ingredients, usually a better Nutri-Score.", shifted(Dimension.HEALTH to 2.5), Effort.EASY))
            if (isEmpty() || weakest == Dimension.CARBON)
                add(Alternative("Finish the pack", "Food that is thrown away has all of the footprint and none of the benefit.", shifted(Dimension.CARBON to 0.5, Dimension.WATER to 0.5)))
        }.take(4)
    }

    private fun fmt(v: Double): String = when {
        v >= 1000 -> "%,d".format(v.toLong())
        v >= 100 -> v.toInt().toString()
        v >= 10 -> "%.0f".format(v)
        v >= 1 -> "%.1f".format(v)
        else -> "%.2f".format(v)
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.intOrNull?.toDouble() ?: it.contentOrNull?.toDoubleOrNull() }
    private fun JsonObject.tags(k: String): List<String> =
        (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() } ?: emptyList()
}
