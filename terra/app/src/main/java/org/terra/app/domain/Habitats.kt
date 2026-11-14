package org.terra.app.domain

import org.terra.app.model.ActionKind
import org.terra.app.model.Habitat
import org.terra.app.model.LifeGroup
import org.terra.app.model.Observation
import org.terra.app.model.Region
import org.terra.app.model.StewardAction
import kotlin.math.exp
import kotlin.math.roundToInt

data class ActionCheck(val hasAfter: Boolean, val sameSpot: Boolean, val timeElapsed: Boolean) {
    val verified get() = hasAfter && sameSpot && timeElapsed
}

data class HabitatHealth(
    val score: Int,
    val nativeSpecies: Int,
    val groups: Set<LifeGroup>,
    val invasiveSpecies: Int,
    val verifiedActions: Int,
    val isSteward: Boolean,
    val breakdown: List<Pair<String, Int>>,
    val missing: List<LifeGroup>
)

object HabitatEngine {
    const val SAME_SPOT_M = 60.0
    const val MIN_GAP_MS = 10 * 60 * 1000L
    const val STEWARD_ACTIONS = 3
    private const val DAY = 86_400_000L
    private val expected = listOf(LifeGroup.BIRD, LifeGroup.PLANT, LifeGroup.TREE, LifeGroup.INSECT, LifeGroup.FUNGUS)

    /** Automatic checks on a before/after pair. Peer review would sit on top of these. */
    fun check(a: StewardAction): ActionCheck {
        val after = a.afterAt
        val lat = a.afterLat
        val lon = a.afterLon
        if (a.afterPhoto == null || after == null || lat == null || lon == null) return ActionCheck(false, false, false)
        return ActionCheck(
            true,
            Geo.distanceM(a.beforeLat, a.beforeLon, lat, lon) <= SAME_SPOT_M,
            after - a.beforeAt >= MIN_GAP_MS
        )
    }

    /** The habitat (if any) containing a point; the smallest one wins when they nest. */
    fun containing(habitats: List<Habitat>, lat: Double, lon: Double): Habitat? =
        habitats.filter { Geo.distanceM(it.lat, it.lon, lat, lon) <= it.radiusM }.minByOrNull { it.radiusM }

    /**
     * Health 0–100. It rises as native biodiversity is logged and stewardship is verified,
     * and falls with unaddressed invasive species.
     */
    fun health(
        habitat: Habitat, observations: List<Observation>, actions: List<StewardAction>,
        catalog: Catalog, now: Long
    ): HabitatHealth {
        val region = Region.of(habitat.lat, habitat.lon)
        val here = observations.filter { it.habitatId == habitat.id }
        val bySpecies = here.groupBy { it.speciesId }
        val invasive = bySpecies.keys.count { catalog[it]?.isInvasiveIn(region) == true }
        val native = bySpecies.size - invasive
        val groups = here.filter { catalog[it.speciesId]?.isInvasiveIn(region) != true }.map { it.group }.toSet()
        val mine = actions.filter { it.habitatId == habitat.id }
        val verified = mine.filter { check(it).verified }
        val removals = verified.count { it.kind == ActionKind.REMOVE_INVASIVE }
        val recent = here.any { now - it.timestamp < 30 * DAY } || verified.any { now - (it.afterAt ?: 0) < 30 * DAY }

        val richness = (35 * (1 - exp(-native / 6.0))).roundToInt()
        val diversity = minOf(groups.size, 5) * 4
        val steward = minOf(verified.size, 5) * 5
        val recency = if (recent) 5 else 0
        val pressure = -(maxOf(invasive - removals, 0) * 6).coerceAtMost(24)
        val parts = listOf(
            "Baseline" to 15, "Native species richness" to richness, "Variety of life groups" to diversity,
            "Verified stewardship" to steward, "Recent attention" to recency, "Invasive pressure" to pressure
        )
        return HabitatHealth(
            parts.sumOf { it.second }.coerceIn(0, 100), native, groups, invasive, verified.size,
            verified.size >= STEWARD_ACTIONS, parts, expected.filter { it !in groups }
        )
    }
}
