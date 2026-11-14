package org.terra.app.domain

import org.terra.app.model.Detection
import org.terra.app.model.Habitat
import org.terra.app.model.Landmark
import org.terra.app.model.LandmarkEntry
import org.terra.app.model.LifeGroup
import org.terra.app.model.ObsMethod
import org.terra.app.model.Observation
import org.terra.app.model.Region
import org.terra.app.model.Season
import org.terra.app.model.StewardAction
import org.terra.app.model.StillnessSession

/** Everything the player has recorded — the input to every engine. */
data class World(
    val observations: List<Observation> = emptyList(),
    val habitats: List<Habitat> = emptyList(),
    val actions: List<StewardAction> = emptyList(),
    val landmarks: List<Landmark> = emptyList(),
    val entries: List<LandmarkEntry> = emptyList(),
    val sessions: List<StillnessSession> = emptyList(),
    val detections: List<Detection> = emptyList()
)

data class Bounty(
    val id: String,
    val title: String,
    val issuer: String,
    val brief: String,
    val why: String,
    /** null = open all year; otherwise only offered in these seasons. */
    val seasons: Set<Season>?,
    val goal: Int,
    val bioBlitz: Boolean = false,
    val count: (World, Catalog, Region, LongRange) -> Int
)

data class BountyProgress(val bounty: Bounty, val done: Int, val endsAt: Long) {
    val complete get() = done >= bounty.goal
    val fraction get() = (done.toFloat() / bounty.goal).coerceIn(0f, 1f)
}

/**
 * Bounties are briefs written by ecologists. The app ships with a seasonal sample set so the
 * mechanic works offline; a partner feed can supply regional ones through the same model.
 * Every bounty expires with the real season — never with an artificial timer.
 */
object BountyEngine {
    private fun obs(w: World, window: LongRange, pred: (Observation) -> Boolean) =
        w.observations.filter { it.timestamp in window && pred(it) }

    private fun distinct(w: World, window: LongRange, pred: (Observation) -> Boolean) =
        obs(w, window, pred).map { it.speciesId }.distinct().size

    val all: List<Bounty> = listOf(
        Bounty("invasive_front", "Invasive front line", "Sample brief · municipal arborist",
            "Record 3 sightings of species that are invasive in your region.",
            "Early detection is the cheapest form of control. Each dated, located record tells land managers where the front is.",
            null, 3) { w, c, r, win -> obs(w, win) { c[it.speciesId]?.isInvasiveIn(r) == true }.size },
        Bounty("canopy_census", "Canopy census", "Sample brief · urban forestry office",
            "Log 8 trees along streets or in parks.",
            "Tree cover cools cities by several degrees. Mapping it shows which streets are heat islands.",
            null, 8) { w, _, _, win -> obs(w, win) { it.group == LifeGroup.TREE }.size },
        Bounty("quiet_hours", "Quiet hours", "TERRA field desk",
            "Complete 2 Stillness sits this season.",
            "Shy animals emerge when human noise subsides. Ten quiet minutes records what a walk-through never will.",
            null, 2) { w, _, _, win -> w.sessions.count { it.completed && it.startedAt in win } },
        Bounty("spring_bloom", "Before the migration peaks", "Sample brief · pollinator ecologist",
            "Map 6 different flowering plants while spring migrants are arriving.",
            "Migrants and emerging queens need nectar on arrival. Bloom maps reveal the gaps along their route.",
            setOf(Season.SPRING), 6, bioBlitz = true) { w, c, r, win ->
            distinct(w, win) { it.group == LifeGroup.PLANT && c[it.speciesId]?.isInvasiveIn(r) != true } },
        Bounty("after_rain", "After the rain", "Sample brief · amphibian monitoring group",
            "Log 4 amphibian records — by sight or by ear.",
            "Amphibians breathe through their skin and are the first to vanish when water quality drops.",
            setOf(Season.SPRING, Season.SUMMER), 4) { w, _, _, win -> obs(w, win) { it.group == LifeGroup.AMPHIBIAN }.size },
        Bounty("first_arrivals", "First arrivals", "Sample brief · bird observatory",
            "Record 6 different bird species this spring.",
            "Arrival dates are among the longest-running climate records we have.",
            setOf(Season.SPRING), 6) { w, _, _, win -> distinct(w, win) { it.group == LifeGroup.BIRD } },
        Bounty("night_shift", "Night shift", "Sample brief · nocturnal ecology lab",
            "Make 4 records after dark.",
            "Half of all animal life is active at night and almost none of it is mapped.",
            setOf(Season.SUMMER), 4) { w, _, _, win -> obs(w, win) { Phenology.isNight(it.timestamp) }.size },
        Bounty("pollinator_watch", "Pollinator watch", "Sample brief · conservation trust",
            "Record 5 different insects in gardens, parks or meadows.",
            "Insect numbers are falling fast and unevenly. Local counts show where planting helps.",
            setOf(Season.SUMMER), 5, bioBlitz = true) { w, _, _, win -> distinct(w, win) { it.group == LifeGroup.INSECT } },
        Bounty("fungal_foray", "Fungal foray", "Sample brief · mycological society",
            "Find 4 different fungi or lichens.",
            "Fungi are the least-recorded kingdom. Most fruit for only a few days a year.",
            setOf(Season.AUTUMN), 4, bioBlitz = true) { w, _, _, win -> distinct(w, win) { it.group == LifeGroup.FUNGUS } },
        Bounty("seed_carriers", "Seeds on the move", "Sample brief · restoration ecologist",
            "Log 4 plants or trees in seed, pod, berry or acorn stage.",
            "How a seed travels decides where the next forest grows.",
            setOf(Season.AUTUMN), 4) { w, _, _, win ->
            obs(w, win) { o -> listOf("seed", "pod", "berr", "acorn", "fluff", "samara").any { o.lifeStage?.contains(it, true) == true || o.phenotype?.contains(it, true) == true } }.size },
        Bounty("trackers", "Tracker's challenge", "Sample brief · mammal survey",
            "Record 3 mammals — tracks, dreys and droppings count.",
            "Most mammals are known only by their signs. Snow and mud are the survey sheets.",
            setOf(Season.WINTER), 3) { w, _, _, win -> obs(w, win) { it.group == LifeGroup.MAMMAL }.size },
        Bounty("evergreen", "Bark & lichen", "Sample brief · air-quality study",
            "Log 3 lichens or winter fungi and 3 trees.",
            "Lichens are living air-quality meters, readable all winter.",
            setOf(Season.WINTER), 6) { w, _, _, win ->
            minOf(3, obs(w, win) { it.group == LifeGroup.FUNGUS }.size) + minOf(3, obs(w, win) { it.group == LifeGroup.TREE }.size) },
        Bounty("winter_residents", "Those who stay", "Sample brief · bird observatory",
            "Record 5 different bird species this winter.",
            "Winter counts show which birds a neighbourhood can feed through the lean months.",
            setOf(Season.WINTER), 5) { w, _, _, win -> distinct(w, win) { it.group == LifeGroup.BIRD } }
    )

    fun current(world: World, catalog: Catalog, lat: Double, lon: Double, now: Long): List<BountyProgress> {
        val season = Phenology.season(now, lat)
        val window = Phenology.seasonStart(now) until Phenology.seasonEnd(now)
        val region = Region.of(lat, lon)
        return all.filter { it.seasons == null || season in it.seasons }
            .map { BountyProgress(it, it.count(world, catalog, region, window), window.last + 1) }
            .sortedBy { it.bounty.seasons == null }
    }
}

data class Badge(val id: String, val name: String, val glyph: String, val how: String, val earned: Boolean)

/** Badges mark kinds of attention — morphs, life stages, patience, care — never volume. */
object BadgeEngine {
    private const val YEAR_MS = 365L * 86_400_000L

    fun badges(w: World, catalog: Catalog, entries: List<CodexEntry>): List<Badge> {
        val obs = w.observations
        fun b(id: String, name: String, glyph: String, how: String, earned: Boolean) = Badge(id, name, glyph, how, earned)
        val fullCycle = entries.any { it.species.stages.size >= 3 && it.stagesSeen.containsAll(it.species.stages) }
        val fourSeasons = entries.any { it.seasonsSeen.size == 4 }
        val verified = w.actions.filter { HabitatEngine.check(it).verified }
        val stewardOf = verified.groupBy { it.habitatId }.any { it.value.size >= HabitatEngine.STEWARD_ACTIONS }
        val keeper = w.landmarks.any { lm ->
            val times = w.entries.filter { it.landmarkId == lm.id }.map { it.timestamp }
            times.isNotEmpty() && times.max() - lm.adoptedAt >= YEAR_MS
        }
        return listOf(
            b("first_light", "First light", "🌅", "Log your first observation", obs.isNotEmpty()),
            b("morph", "Morph hunter", "🎨", "Record a seasonal morph or rare colour form", obs.any { it.phenotype != null }),
            b("full_cycle", "Full cycle", "🔄", "Document every life stage of one species", fullCycle),
            b("heritage", "Heritage witness", "🏛", "Record an ancient or heritage tree", obs.any { it.heritage }),
            b("four_seasons", "Four seasons", "🍂", "See one species in all four seasons", fourSeasons),
            b("web_weaver", "Web weaver", "🕸", "Complete a species' full ecological web", entries.any { it.level == CodexLevel.COMPLETE }),
            b("night_ear", "Night ear", "🌙", "Identify something by sound after dark",
                obs.any { it.method != ObsMethod.PHOTO && it.method != ObsMethod.MANUAL && Phenology.isNight(it.timestamp) }),
            b("dawn_chorus", "Chorus", "🎶", "Identify 5 species by ear",
                obs.filter { it.method == ObsMethod.ACOUSTIC || it.method == ObsMethod.STILLNESS }.map { it.speciesId }.distinct().size >= 5),
            b("still_water", "Still water", "🪷", "Complete a 10-minute Stillness sit", w.sessions.any { it.completed }),
            b("deep_stillness", "Deep stillness", "🧘", "Complete 5 Stillness sits", w.sessions.count { it.completed } >= 5),
            b("cartographer", "Cartographer", "🗺", "Set foot in 5 habitats", w.habitats.count { it.firstVisitedAt != null } >= 5),
            b("first_care", "First care", "🌱", "Complete a verified stewardship action", verified.isNotEmpty()),
            b("steward", "Steward", "🛡", "Become Steward of a habitat (3 verified actions)", stewardOf),
            b("keeper", "Keeper of time", "⏳", "Tend a legacy landmark for a full year", keeper)
        )
    }
}
