package org.terra.app

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.terra.app.Fixtures.LAT
import org.terra.app.Fixtures.LON
import org.terra.app.Fixtures.at
import org.terra.app.Fixtures.catalog
import org.terra.app.Fixtures.obs
import org.terra.app.domain.AcousticMatcher
import org.terra.app.domain.BadgeEngine
import org.terra.app.domain.BountyEngine
import org.terra.app.domain.Catalog
import org.terra.app.domain.CodexEngine
import org.terra.app.domain.CodexLevel
import org.terra.app.domain.Exporter
import org.terra.app.domain.Geo
import org.terra.app.domain.GeoFuzzer
import org.terra.app.domain.HabitatEngine
import org.terra.app.domain.Overpass
import org.terra.app.domain.Phenology
import org.terra.app.domain.World
import org.terra.app.model.ActionKind
import org.terra.app.model.HabitatType
import org.terra.app.model.LifeGroup
import org.terra.app.model.Region
import org.terra.app.model.Season
import org.terra.app.model.StewardAction
import org.terra.app.model.StillnessSession
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DomainTest {
    private val habitats = listOf(Fixtures.meadow, Fixtures.wood, Fixtures.pond)
    private val habitatMap = habitats.associateBy { it.id }

    // ── catalog ──
    @Test fun catalogIsConsistent() {
        assertTrue(catalog.species.size >= 85)
        assertEquals(catalog.species.size, catalog.species.map { it.id }.toSet().size)
        for (s in catalog.species) {
            assertTrue(s.id, s.links.isNotEmpty())
            for (l in s.links) for (t in l.targets) when {
                t.startsWith("group:") -> LifeGroup.valueOf(t.removePrefix("group:"))
                t.startsWith("habitat:") -> HabitatType.valueOf(t.removePrefix("habitat:"))
                else -> assertNotNull("${s.id} -> $t", catalog[t])
            }
            s.acoustic?.let { assertTrue(s.id, it.lowHz < it.highHz && it.rateMin <= it.rateMax && it.highHz <= 11000) }
        }
        for (r in Region.entries) assertTrue("$r has too few species", catalog.inRegion(r).size >= 20)
    }

    @Test fun suggestionsFollowTheCameraAndThePlace() {
        val s = catalog.suggest(listOf("Bird" to 0.9f, "Sky" to 0.5f), Region.NA, Season.SUMMER, HabitatType.GARDEN)
        assertTrue(s.isNotEmpty())
        assertTrue(s.all { it.species.group == LifeGroup.BIRD })
        assertTrue("local birds first", s.take(5).all { it.species.occursIn(Region.NA) })
        val india = catalog.suggest(listOf("Bird" to 0.9f), Region.AS, Season.SUMMER, null).take(5).map { it.species.id }
        assertTrue(india.toString(), india.none { it == "american_robin" })
        assertEquals(LifeGroup.FUNGUS, Catalog.groupOf("Mushroom"))
        assertEquals(LifeGroup.INSECT, Catalog.groupOf("Butterfly"))
        assertNull(Catalog.groupOf("Building"))
        assertNull("'plant' inside another word must not match", Catalog.groupOf("Planter"))
    }

    // ── geography, seasons, poaching protection ──
    @Test fun regions() {
        assertEquals(Region.NA, Region.of(43.65, -79.38))
        assertEquals(Region.SA, Region.of(-23.5, -46.6))
        assertEquals(Region.EU, Region.of(51.5, -0.1))
        assertEquals(Region.AF, Region.of(-1.3, 36.8))
        assertEquals(Region.AS, Region.of(12.97, 77.59))
        assertEquals(Region.AS, Region.of(35.7, 139.7))
        assertEquals(Region.OC, Region.of(-33.9, 151.2))
        assertEquals(Region.OC, Region.of(-41.3, 174.8))
    }

    @Test fun seasonsFlipAcrossTheEquator() {
        assertEquals(Season.SPRING, Phenology.season(4, 45.0))
        assertEquals(Season.AUTUMN, Phenology.season(4, -34.0))
        assertEquals(Season.WINTER, Phenology.season(1, 45.0))
        assertEquals(Season.SUMMER, Phenology.season(1, -34.0))
        val now = at(2026, 10, 14)
        assertTrue(Phenology.seasonStart(now) <= now && now < Phenology.seasonEnd(now))
        assertEquals(at(2026, 12, 1, 0), Phenology.seasonEnd(now))
        assertEquals(at(2027, 3, 1, 0), Phenology.seasonEnd(at(2027, 1, 15)))
        assertEquals(at(2026, 12, 1, 0), Phenology.seasonStart(at(2027, 1, 15)))
    }

    @Test fun sensitiveCoordinatesCannotBeRecovered() {
        val a = GeoFuzzer.obscure(43.6532, -79.3832)
        val b = GeoFuzzer.obscure(43.7011, -79.2504)
        assertEquals("every record in a cell collapses to one point", a, b)
        assertTrue(Geo.distanceM(43.6532, -79.3832, a.lat, a.lon) < GeoFuzzer.UNCERTAINTY_M)
        assertTrue("must actually move", Geo.distanceM(43.6532, -79.3832, a.lat, a.lon) > 500)
        assertEquals(GeoFuzzer.Point(43.6532, -79.3832), GeoFuzzer.publicPoint(43.6532, -79.3832, false))
        val south = GeoFuzzer.obscure(-33.91, 151.21)
        assertTrue(abs(south.lat + 33.9) < 0.11 && abs(south.lon - 151.3) < 0.11)
    }

    @Test fun publicExportHidesWhatResearchExportKeeps() {
        val w = Fixtures.world()
        fun coords(research: Boolean) = Exporter.geoJson(w.observations, catalog, research)["features"]!!.jsonArray.associate {
            val p = it.jsonObject["properties"]!!.jsonObject
            val c = it.jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
            p["vernacularName"]!!.jsonPrimitive.content to (c[1].jsonPrimitive.double to c[0].jsonPrimitive.double)
        }
        val pub = coords(false)
        val res = coords(true)
        val trueLat = LAT - 0.0043
        assertEquals(trueLat, res.getValue("Peregrine Falcon").first, 1e-9)
        assertTrue(abs(pub.getValue("Peregrine Falcon").first - trueLat) > 0.01)
        assertEquals("ordinary species are published exactly", res.getValue("Turkey Tail"), pub.getValue("Turkey Tail"))
        val text = Exporter.geoJson(w.observations, catalog, false).toString()
        assertFalse("true latitude must not leak anywhere in the public file", text.contains(trueLat.toString().take(8)))
    }

    @Test fun backupRoundTrips() {
        val w = Fixtures.world()
        val b = Exporter.parseBackup(Exporter.backup(w, 123))
        assertEquals(w.observations, b.observations)
        assertEquals(w.entries, b.entries)
        assertEquals(w.actions, b.actions)
    }

    // ── codex / trophic chains ──
    @Test fun aPartnerMustBeFoundNearby() {
        val monarch = catalog["monarch"]!!
        val m = obs("monarch", at(2026, 7, 1))
        val far = obs("common_milkweed", at(2026, 7, 1), lat = LAT + 0.5)
        val near = obs("common_milkweed", at(2026, 7, 1), lat = LAT + 0.005)
        val host = { e: org.terra.app.domain.CodexEntry -> e.nodes.first { it.link.type.name == "HOST_PLANT" } }
        assertEquals(CodexLevel.UNSEEN, CodexEngine.entry(monarch, listOf(near), habitatMap, catalog).level)
        assertFalse("nodes stay locked until the species itself is seen", host(CodexEngine.entry(monarch, listOf(near), habitatMap, catalog)).satisfied)
        assertEquals(CodexLevel.SIGHTED, CodexEngine.entry(monarch, listOf(m), habitatMap, catalog).level)
        assertFalse(host(CodexEngine.entry(monarch, listOf(m, far), habitatMap, catalog)).satisfied)
        val e = CodexEngine.entry(monarch, listOf(m, near), habitatMap, catalog)
        assertTrue(host(e).satisfied)
        assertEquals("Common Milkweed", host(e).satisfiedBy)
        assertEquals(CodexLevel.CONNECTED, e.level)
    }

    @Test fun completingAWebTakesPartnersAndSeasonsNotRepeats() {
        val monarch = catalog["monarch"]!!
        val ten = (1..10).map { obs("monarch", at(2026, 7, it)) }
        assertEquals("ten sightings in one season complete nothing", CodexLevel.SIGHTED, CodexEngine.entry(monarch, ten, habitatMap, catalog).level)
        val e = Fixtures.world().let { CodexEngine.entry(monarch, it.observations, habitatMap, catalog) }
        assertEquals(setOf(Season.SUMMER, Season.AUTUMN), e.seasonsSeen)
        assertEquals(CodexLevel.COMPLETE, e.level)
        assertEquals(setOf("caterpillar", "adult"), e.stagesSeen)
    }

    @Test fun habitatNodesNeedTheSpeciesInThatHabitat() {
        val hawk = catalog["red_tailed_hawk"]!!
        val inMeadow = obs("red_tailed_hawk", at(2026, 9, 1), habitat = 1)
        val inWood = obs("red_tailed_hawk", at(2026, 9, 1), habitat = 2)
        fun meadowNode(o: org.terra.app.model.Observation) = CodexEngine.entry(hawk, listOf(o), habitatMap, catalog).nodes.first { "habitat:MEADOW" in it.link.targets }
        assertTrue(meadowNode(inMeadow).satisfied)
        assertEquals("Riverside Meadow", meadowNode(inMeadow).satisfiedBy)
        assertFalse(meadowNode(inWood).satisfied)
        val e = CodexEngine.entry(hawk, listOf(inMeadow, obs("hawk-self", "red_tailed_hawk")), habitatMap, catalog)
        assertFalse("a species cannot be its own partner", e.nodes.first { "group:TREE" in it.link.targets }.satisfied)
    }

    private fun obs(@Suppress("UNUSED_PARAMETER") tag: String, species: String) = Fixtures.obs(species, at(2026, 9, 2))

    // ── habitat health & stewardship ──
    @Test fun healthRisesWithNativeLifeAndFallsWithInvasives() {
        val now = at(2026, 10, 14)
        fun score(w: World, id: Long = 2) = HabitatEngine.health(habitats.first { it.id == id }, w.observations, w.actions, catalog, now)
        val empty = score(World())
        assertEquals(15, empty.score)
        val one = score(World(listOf(Fixtures.obs("turkey_tail", now, habitat = 2))))
        assertTrue(one.score > empty.score)
        val three = score(World(listOf("turkey_tail", "white_oak", "black_capped_chickadee").map { Fixtures.obs(it, now, habitat = 2) }))
        assertTrue(three.score > one.score)
        val repeats = score(World((1..10).map { Fixtures.obs("turkey_tail", now, habitat = 2) }))
        assertEquals("re-logging one species does not farm points", one.score, repeats.score)
        val invaded = score(World(listOf("turkey_tail", "garlic_mustard").map { Fixtures.obs(it, now, habitat = 2) }))
        assertTrue(invaded.score < one.score)
        assertEquals(1, invaded.invasiveSpecies)
        val removal = StewardAction(1, 2, ActionKind.REMOVE_INVASIVE, "", "b", LAT, LON, now - 7_200_000, "a", LAT, LON, now)
        val restored = score(World(listOf("turkey_tail", "garlic_mustard").map { Fixtures.obs(it, now, habitat = 2) }, actions = listOf(removal)))
        assertTrue(restored.score > one.score)
        assertTrue(LifeGroup.BIRD in one.missing && LifeGroup.FUNGUS !in one.missing)
    }

    @Test fun stewardshipNeedsTheSamePlaceAndElapsedTime() {
        val t = at(2026, 5, 1)
        val before = StewardAction(1, 1, ActionKind.CLEANUP, "", "b", LAT, LON, t)
        assertFalse(HabitatEngine.check(before).hasAfter)
        assertTrue(HabitatEngine.check(before.copy(afterPhoto = "a", afterLat = LAT + 0.0002, afterLon = LON, afterAt = t + 3_600_000)).verified)
        assertFalse("elsewhere", HabitatEngine.check(before.copy(afterPhoto = "a", afterLat = LAT + 0.01, afterLon = LON, afterAt = t + 3_600_000)).verified)
        assertFalse("too soon", HabitatEngine.check(before.copy(afterPhoto = "a", afterLat = LAT, afterLon = LON, afterAt = t + 60_000)).verified)
        val done = (1..3).map { before.copy(id = it.toLong(), afterPhoto = "a", afterLat = LAT, afterLon = LON, afterAt = t + 3_600_000) }
        assertTrue(HabitatEngine.health(Fixtures.meadow, emptyList(), done, catalog, t).isSteward)
        assertEquals(Fixtures.pond, HabitatEngine.containing(habitats + Fixtures.meadow.copy(id = 9, lat = LAT - 0.004, lon = LON + 0.003, radiusM = 900.0), LAT - 0.004, LON + 0.003))
    }

    // ── bounties & badges ──
    @Test fun bountiesFollowTheRealSeason() {
        val w = Fixtures.world()
        val autumn = BountyEngine.current(w, catalog, LAT, LON, at(2026, 10, 14))
        assertTrue(autumn.any { it.bounty.id == "fungal_foray" })
        assertTrue(autumn.none { it.bounty.id == "spring_bloom" })
        assertEquals(1, autumn.first { it.bounty.id == "fungal_foray" }.done)
        assertEquals("the spring garlic mustard record is outside this season's window", 0, autumn.first { it.bounty.id == "invasive_front" }.done)
        assertEquals(1, autumn.first { it.bounty.id == "quiet_hours" }.done)
        val southern = BountyEngine.current(w, catalog, -33.9, 151.2, at(2026, 10, 14))
        assertTrue("October is spring in Sydney", southern.any { it.bounty.id == "spring_bloom" })
        val spring = BountyEngine.current(w, catalog, LAT, LON, at(2026, 5, 25))
        assertEquals(1, spring.first { it.bounty.id == "invasive_front" }.done)
        assertEquals(1, spring.first { it.bounty.id == "after_rain" }.done)
    }

    @Test fun badgesRewardKindsOfAttention() {
        val w = Fixtures.world()
        val entries = CodexEngine.entries(catalog, w.observations, w.habitats)
        val b = BadgeEngine.badges(w, catalog, entries).associate { it.id to it.earned }
        for (id in listOf("first_light", "morph", "heritage", "web_weaver", "night_ear", "still_water", "first_care", "keeper")) assertTrue(id, b.getValue(id))
        for (id in listOf("four_seasons", "deep_stillness", "steward", "full_cycle")) assertFalse(id, b.getValue(id))
        assertTrue(BadgeEngine.badges(World(), catalog, CodexEngine.entries(catalog, emptyList(), emptyList())).none { it.earned })
        val five = World(sessions = (1..5L).map { StillnessSession(it, it, 600, completed = true) })
        assertTrue(BadgeEngine.badges(five, catalog, emptyList()).first { it.id == "deep_stillness" }.earned)
    }

    // ── acoustics ──
    private fun tone(hz: Double, pulsesPerSec: Double, duty: Double = 0.4, seconds: Int = 5, noise: Double = 0.002, harmonics: Boolean = false): FloatArray {
        val rate = 22050
        val rnd = java.util.Random(7)
        return FloatArray(rate * seconds) { i ->
            val t = i.toDouble() / rate
            val gate = if (pulsesPerSec <= 0) 1.0 else if ((t * pulsesPerSec) % 1.0 < duty) 1.0 else 0.0
            var s = sin(2 * PI * hz * t)
            if (harmonics) s = (s + 0.9 * sin(2 * PI * hz * 1.37 * t) + 0.8 * sin(2 * PI * hz * 1.83 * t) + 0.8 * sin(2 * PI * hz * 2.6 * t) + 0.7 * sin(2 * PI * hz * 3.1 * t)) / 4
            (0.3 * gate * s + noise * rnd.nextGaussian()).toFloat()
        }
    }

    @Test fun fftFindsAPureTone() {
        val f = AcousticMatcher.analyze(tone(3000.0, 0.0), 22050)!!
        assertEquals(3000f, f.peakHz, 30f)
        assertTrue(f.tonal)
        assertEquals(0f, f.rate, 0.01f)
    }

    @Test fun pulseRateIsCounted() {
        val f = AcousticMatcher.analyze(tone(3000.0, 2.0), 22050)!!
        assertEquals(2f, f.rate, 0.3f)
        assertEquals(3000f, f.peakHz, 30f)
        assertTrue(f.contrastDb > 20)
        assertEquals(8f, AcousticMatcher.analyze(tone(1500.0, 8.0), 22050)!!.rate, 0.8f)
    }

    @Test fun silenceAndShortClipsAreRejected() {
        assertNull(AcousticMatcher.analyze(FloatArray(22050 * 3), 22050))
        assertNull(AcousticMatcher.analyze(FloatArray(2000) { 0.1f }, 22050))
    }

    @Test fun callsRankThePlausibleSpeciesFirst() {
        val na = catalog.inRegion(Region.NA)
        val peeper = AcousticMatcher.rank(AcousticMatcher.analyze(tone(3000.0, 1.6), 22050)!!, na)
        assertEquals(peeper.map { it.species.id }.toString(), "spring_peeper", peeper.first().species.id)
        val low = AcousticMatcher.rank(AcousticMatcher.analyze(tone(350.0, 1.5), 22050)!!, na).map { it.species.id }
        assertTrue(low.toString(), low.take(3).containsAll(listOf("great_horned_owl", "american_bullfrog")))
        assertTrue(low.toString(), "spring_peeper" !in low && "annual_cicada" !in low)
        val cricket = AcousticMatcher.rank(AcousticMatcher.analyze(tone(4600.0, 3.0), 22050)!!, na).map { it.species.id }
        assertEquals(cricket.toString(), "field_cricket", cricket.first())
        val harsh = AcousticMatcher.analyze(tone(1800.0, 3.0, harmonics = true), 22050)!!
        assertFalse("a stack of inharmonic partials is not a whistle", harsh.tonal)
        assertTrue(AcousticMatcher.describe(AcousticMatcher.analyze(tone(3000.0, 1.6), 22050)!!).contains("high-pitched"))
    }

    // ── OpenStreetMap ──
    @Test fun overpassResultsBecomeHabitats() {
        val json = """{"elements":[
          {"type":"way","id":101,"bounds":{"minlat":43.650,"minlon":-79.390,"maxlat":43.654,"maxlon":-79.384},"tags":{"leisure":"park","name":"Grange Park"}},
          {"type":"relation","id":101,"bounds":{"minlat":43.660,"minlon":-79.400,"maxlat":43.662,"maxlon":-79.398},"tags":{"natural":"wetland"}},
          {"type":"way","id":102,"bounds":{"minlat":43.65,"minlon":-79.39,"maxlat":43.65001,"maxlon":-79.38999},"tags":{"natural":"water"}},
          {"type":"way","id":103,"bounds":{"minlat":43.65,"minlon":-79.39,"maxlat":43.66,"maxlon":-79.38},"tags":{"building":"yes"}},
          {"type":"way","id":104,"tags":{"leisure":"park"}}]}"""
        val h = Overpass.parse(json, 5)
        assertEquals(2, h.size)
        assertEquals("Grange Park", h[0].name)
        assertEquals(HabitatType.PARK, h[0].type)
        assertEquals(43.652, h[0].lat, 1e-6)
        assertTrue(h[0].radiusM in 200.0..300.0)
        assertEquals("Unnamed wetland", h[1].name)
        assertTrue("ways and relations with the same number stay distinct", h[0].osmId != h[1].osmId)
        assertTrue(Overpass.query(1.0, 2.0, 1500).contains("(around:1500,1.0,2.0)"))
    }

    @Test fun derivingTheWholeJournalWorks() {
        val w = Fixtures.world()
        val d = TerraViewModel.derive(w, catalog, org.terra.app.sensing.Fix(LAT, LON, 5f, 0), at(2026, 10, 14))
        assertEquals(Region.NA, d.region)
        assertEquals(Season.AUTUMN, d.season)
        assertEquals(12, d.discovered.size)
        assertEquals(3, d.health.size)
        assertTrue(d.health.getValue(1).score > d.health.getValue(3).score)
    }
}
