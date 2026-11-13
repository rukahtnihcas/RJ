package earth.ripple.core

import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private fun match(vararg l: Pair<String, Float>) = KnowledgeBase.match(l.map { Label(it.first, it.second) }).assessment.id

    @Test fun rubricAnchors() {
        assertEquals(0.0, Rubric.carbonFromKg(0.005))
        assertEquals(-1.0, Rubric.carbonFromKg(0.1))
        assertEquals(-3.0, Rubric.carbonFromKg(10.0))
        assertEquals(-5.0, Rubric.carbonFromKg(50_000.0))
        assertEquals(-3.5, Rubric.waterFromLitres(2700.0))
        assertEquals(0.0, Rubric.waterFromLitres(0.5))
        assertEquals("+3", Rubric.format(3.0)); assertEquals("−2.5", Rubric.format(-2.5)); assertEquals("0", Rubric.format(0.2))
    }

    @Test fun everyEntryIsWellFormed() {
        val all = KnowledgeBase.all
        assertTrue(all.size >= 50, "expected a broad library, got ${all.size}")
        assertEquals(all.size, all.map { it.id }.toSet().size, "duplicate ids")
        for (a in all) {
            assertEquals(5, a.scores.size, a.id)
            for (s in a.scores) {
                assertTrue(s.score in -5.0..5.0, "${a.id} score out of range")
                assertEquals(s.score, Rubric.snap(s.score), "${a.id} not in half steps")
                assertTrue(s.why.isNotBlank(), "${a.id} missing why")
            }
            assertTrue(a.headline.length in 40..260, "${a.id} headline length ${a.headline.length}")
            assertTrue(a.unit.isNotBlank(), a.id)
            assertTrue(a.alternatives.size >= 2, "${a.id} needs alternatives")
            for (alt in a.alternatives) {
                assertEquals(5, alt.scores.size, "${a.id}/${alt.name}")
                assertTrue(alt.scores.all { it in -5.0..5.0 })
                // A "better choice" must not be worse overall than the thing it replaces.
                assertTrue(alt.scores.average() >= a.average - 0.01, "${a.id}/${alt.name} is worse than the original")
            }
            assertTrue(a.stages.all { it.weight in 0..3 }, a.id)
            if (a.id != "unknown") assertTrue(a.stages.any { it.weight >= 2 }, "${a.id} has no significant stage")
            assertTrue(a.questions.all { it.a.isNotBlank() }, "${a.id} offline question without an answer")
            // Round-trips through storage.
            assertEquals(a, Json.decodeFromString(Assessment.serializer(), Json.encodeToString(Assessment.serializer(), a)))
        }
    }

    /** Metrics quoted for carbon and water must agree with the score the rubric gives them, within a point. */
    @Test fun headlineNumbersAgreeWithScores() {
        fun check(id: String, d: Dimension, expected: Double) {
            val s = KnowledgeBase.byId(id)!!.score(d).score
            assertTrue(abs(s - expected) <= 1.0, "$id ${d.name}: scored $s, rubric says $expected")
        }
        check("tshirt", Dimension.CARBON, Rubric.carbonFromKg(7.0)); check("tshirt", Dimension.WATER, Rubric.waterFromLitres(2700.0))
        check("jeans", Dimension.CARBON, Rubric.carbonFromKg(33.0)); check("jeans", Dimension.WATER, Rubric.waterFromLitres(3800.0))
        check("phone", Dimension.CARBON, Rubric.carbonFromKg(70.0)); check("phone", Dimension.WATER, Rubric.waterFromLitres(12000.0))
        check("beef-burger", Dimension.CARBON, Rubric.carbonFromKg(6.0)); check("beef-burger", Dimension.WATER, Rubric.waterFromLitres(2400.0))
        check("coffee", Dimension.WATER, Rubric.waterFromLitres(130.0)); check("laptop", Dimension.CARBON, Rubric.carbonFromKg(275.0))
        check("car", Dimension.CARBON, Rubric.carbonFromKg(3000.0)); check("plane", Dimension.CARBON, Rubric.carbonFromKg(1300.0))
        check("water-bottle", Dimension.CARBON, Rubric.carbonFromKg(0.08)); check("dog", Dimension.CARBON, Rubric.carbonFromKg(770.0))
        check("tap", Dimension.WATER, Rubric.waterFromLitres(50000.0)); check("egg", Dimension.WATER, Rubric.waterFromLitres(400.0))
    }

    @Test fun matchesWhatTheCameraSays() {
        assertEquals("jeans", match("Jeans" to 0.9f, "Clothing" to 0.8f))
        assertEquals("phone", match("Mobile phone" to 0.85f, "Gadget" to 0.7f))
        assertEquals("dog", match("Dog" to 0.95f, "Pet" to 0.6f))
        assertEquals("tree", match("Tree" to 0.8f, "Plant" to 0.75f))
        assertEquals("plant", match("Flowerpot" to 0.8f, "Plant" to 0.8f))
        assertEquals("water-bottle", match("Water bottle" to 0.7f, "Water" to 0.6f))
        assertEquals("human", match("Smile" to 0.9f, "Hair" to 0.8f))
        assertEquals("beef-burger", match("Cheeseburger" to 0.8f, "Food" to 0.9f, "Fast food" to 0.7f))
        assertEquals("car", match("Vehicle" to 0.9f, "Car" to 0.85f))
        assertEquals("bicycle", match("Bicycle" to 0.9f, "Vehicle" to 0.5f))
        assertEquals("coffee", match("Coffee" to 0.8f, "Cup" to 0.7f))
        assertEquals("mug", match("Cup" to 0.8f, "Tableware" to 0.7f))
        // Coarse categories from the object detector still land somewhere sensible.
        assertEquals("meal", match("Food" to 0.6f))
        assertEquals("generic-fashion", match("Fashion good" to 0.7f))
        assertEquals("generic-home", match("Home good" to 0.7f))
        assertEquals("unknown", match("Xyzzy" to 0.9f))
        assertEquals("unknown", match())
        // Whole-word matching only.
        assertFalse(match("Watermelon" to 0.9f) == "wild")
    }

    @Test fun parsesRealProductRecord() {
        val body = javaClass.classLoader!!.getResource("off_nutella.json")!!.readText()
        val a = assertNotNull(OpenFoodFacts.parse(body, "3017620422003"))
        assertTrue(a.name.contains("Nutella"))
        assertEquals(Tier.PRODUCT_DATA, a.tier)
        assertEquals(Confidence.HIGH, a.score(Dimension.CARBON).confidence)
        assertTrue(a.score(Dimension.CARBON).score <= -2.0, "carbon ${a.score(Dimension.CARBON)}")
        assertTrue(a.score(Dimension.HEALTH).score <= -2.5, "Nutri-Score E must read as unhealthy")
        assertTrue(a.score(Dimension.NATURE).score <= -3.5, "low Eco-Score with palm oil")
        assertTrue(a.alternatives.isNotEmpty() && a.stages.any { it.weight == 3 })
        println("OFF → ${a.name} ${a.values} | ${a.headline}")
        assertNull(OpenFoodFacts.parse("""{"status":0}""", "0"))
        assertNull(OpenFoodFacts.parse("not json", "0"))
    }

    @Test fun parsesAiResponseAndClampsIt() {
        val text = """{"identified":true,"name":"Oak tree","emoji":"🌳","kind":"LIVING","unit":"one year","headline":"h",
          "scores":{"carbon":{"score":3.2,"why":"w","metric":"≈ 20 kg","confidence":"MEDIUM"},
                    "water":{"score":9,"why":"w","metric":"","confidence":"LOW"},
                    "health":{"score":4,"why":"w","metric":"","confidence":"HIGH"},
                    "nature":{"score":5,"why":"w","metric":"","confidence":"HIGH"},
                    "social":{"score":-7,"why":"w","metric":"","confidence":"nonsense"}},
          "stages":[{"name":"Growth","weight":7,"note":""}],
          "dependencies":[{"name":"Fungi","note":"n"}],
          "alternatives":[{"name":"Protect it","why":"y","effort":"EASY","scores":{"carbon":3,"water":3,"health":4,"nature":5,"social":3},"co2_kg_per_year":0,"water_l_per_year":-4}],
          "questions":["Why?"],"sources":["IPCC"]}"""
        val a = assertNotNull(AiProtocol.parse(text, "x"))
        assertEquals(listOf(3.0, 5.0, 4.0, 5.0, -5.0), a.values)
        assertEquals(Confidence.LOW, a.score(Dimension.SOCIAL).confidence)
        assertEquals(3, a.stages.single().weight)
        assertEquals(0.0, a.alternatives.single().waterLPerYear)
        assertEquals(Tier.AI, a.tier)
        assertNull(AiProtocol.parse("I can't help with that", "x"))
        // The schema we send must itself be valid JSON.
        Json.parseToJsonElement(AiProtocol.schema)
        assertTrue(AiProtocol.systemPrompt.contains("−5") && AiProtocol.systemPrompt.length > 2000)
    }

    @Test fun gameRewardsUnderstandingAndAction() {
        val shirt = KnowledgeBase.byId("tshirt")!!
        val tree = KnowledgeBase.byId("tree")!!
        var p = Profile()
        var o = Game.onScan(p, shirt, "2026-11-13", false); p = o.profile
        assertEquals(1, p.scans); assertEquals(1, p.streak); assertTrue("first" in p.badges && "thirsty" in p.badges)
        val before = p.points
        o = Game.onScan(p, shirt, "2026-11-13", false); p = o.profile
        assertEquals(10, p.points - before - (if (o.rewards.any { it.text.startsWith("Daily") }) 40 else 0), "a repeat scan earns only the base points")
        o = Game.onScan(p, tree, "2026-11-14", false); p = o.profile
        assertEquals(2, p.streak); assertTrue("giver" in p.badges)
        p = Game.onScan(p, tree, "2026-11-16", false).profile
        assertEquals(1, p.streak, "a missed day resets the streak"); assertEquals(2, p.bestStreak)

        assertTrue(Game.isCorrectGuess(shirt, Dimension.WATER)); assertFalse(Game.isCorrectGuess(shirt, Dimension.HEALTH))
        assertTrue(Game.isCorrectGuess(tree, Dimension.NATURE))

        val alt = shirt.alternatives.first()
        o = Game.onPledge(p, shirt, alt, "2026-11-16"); p = o.profile
        assertEquals(1, p.pledges.size); assertTrue("swap" in p.badges)
        assertTrue(Game.onPledge(p, shirt, alt, "2026-11-16").rewards.isEmpty(), "pledging twice does nothing")
        assertEquals(alt.co2KgPerYear, p.co2PledgedKg)

        assertEquals("Seed", Game.level(0).name); assertEquals("Sprout", Game.level(60).name); assertNull(Game.level(99999).next)
        // Every quest can actually be completed by something in the offline library (or a barcode).
        for (d in 0 until 10) {
            val q = Game.quest(java.time.LocalDate.parse("2026-11-13").plusDays(d.toLong()).toString())
            assertTrue(KnowledgeBase.all.any { q.test(it, false) } || q.test(shirt, true), "quest ${q.id} is impossible")
        }
    }

    @Test fun radarGeometry() {
        assertTrue(Radar.radius(-5.0) < Radar.ZERO && Radar.ZERO < Radar.radius(5.0))
        assertEquals(1.0, Radar.radius(5.0), 1e-9)
        val top = Radar.point(0, 1.0)
        assertEquals(0.0, top.first, 1e-9); assertEquals(-1.0, top.second, 1e-9)
        assertEquals(5, Radar.polygon(listOf(0.0, 1.0, 2.0, 3.0, 4.0)).size)
    }
}
