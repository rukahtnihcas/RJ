package org.terra.app.domain

import org.terra.app.model.EcoLink
import org.terra.app.model.Habitat
import org.terra.app.model.HabitatType
import org.terra.app.model.LifeGroup
import org.terra.app.model.Observation
import org.terra.app.model.Season
import org.terra.app.model.Species

enum class CodexLevel(val label: String) {
    UNSEEN("Undiscovered"), SIGHTED("Sighted"), CONNECTED("Connected"), COMPLETE("Web complete")
}

data class NodeState(val link: EcoLink, val satisfied: Boolean, val satisfiedBy: String?, val viaHabitat: Boolean = false) {
    /** "Monarch raises its young on Common Milkweed", or "Red-tailed Hawk documented in Riverside Meadow". */
    fun phrase(name: String) = if (viaHabitat) "$name documented in $satisfiedBy" else "$name ${link.type.verb} $satisfiedBy"
}

data class CodexEntry(
    val species: Species,
    val observations: List<Observation>,
    val nodes: List<NodeState>,
    val seasonsSeen: Set<Season>,
    /** How many distinct seasons are needed for the "seasonal presence" node (0 = no such node). */
    val seasonsNeeded: Int,
    val stagesSeen: Set<String>,
    val phenotypesSeen: Set<String>
) {
    val seasonNodeDone get() = seasonsNeeded == 0 || seasonsSeen.size >= seasonsNeeded
    val totalNodes get() = nodes.size + if (seasonsNeeded > 0) 1 else 0
    val doneNodes get() = nodes.count { it.satisfied } + if (seasonsNeeded > 0 && seasonNodeDone) 1 else 0
    val level: CodexLevel get() = when {
        observations.isEmpty() -> CodexLevel.UNSEEN
        doneNodes == totalNodes -> CodexLevel.COMPLETE
        doneNodes > 0 -> CodexLevel.CONNECTED
        else -> CodexLevel.SIGHTED
    }
}

/**
 * Trophic chains: an entry is completed not by re-catching the same species but by
 * documenting what it depends on — partners found nearby, the habitat it uses,
 * and its presence across seasons.
 */
object CodexEngine {
    /** A partner counts as "nearby" within this distance of any sighting of the focal species. */
    const val NEARBY_M = 1500.0

    fun entry(
        species: Species, all: List<Observation>, habitats: Map<Long, Habitat>, catalog: Catalog
    ): CodexEntry {
        val own = all.filter { it.speciesId == species.id }
        val others = all.filter { it.speciesId != species.id }
        val nodes = species.links.map { link ->
            val hit = if (own.isEmpty()) null else link.targets.firstNotNullOfOrNull { t -> satisfy(t, own, others, habitats, catalog)?.let { t to it } }
            NodeState(link, hit != null, hit?.second, hit?.first?.startsWith("habitat:") == true)
        }
        val seasons = own.map { Phenology.season(it.timestamp, it.lat) }.toSet()
        return CodexEntry(
            species, own.sortedByDescending { it.timestamp }, nodes, seasons,
            if (species.seasons.size >= 2) 2 else 0,
            own.mapNotNull { it.lifeStage }.toSet(), own.mapNotNull { it.phenotype }.toSet()
        )
    }

    fun entries(catalog: Catalog, all: List<Observation>, habitats: List<Habitat>): List<CodexEntry> {
        val map = habitats.associateBy { it.id }
        return catalog.species.map { entry(it, all, map, catalog) }
    }

    private fun near(a: Observation, b: Observation) = Geo.distanceM(a.lat, a.lon, b.lat, b.lon) <= NEARBY_M

    private fun satisfy(
        target: String, own: List<Observation>, others: List<Observation>,
        habitats: Map<Long, Habitat>, catalog: Catalog
    ): String? = when {
        target.startsWith("habitat:") -> {
            val type = HabitatType.valueOf(target.removePrefix("habitat:"))
            own.firstNotNullOfOrNull { o -> o.habitatId?.let { habitats[it] }?.takeIf { it.type == type }?.name }
        }
        target.startsWith("group:") -> {
            val group = LifeGroup.valueOf(target.removePrefix("group:"))
            others.firstOrNull { o -> o.group == group && own.any { near(it, o) } }?.displayName
        }
        else -> others.firstOrNull { o -> o.speciesId == target && own.any { near(it, o) } }
            ?.let { catalog[target]?.common ?: it.displayName }
    }

    /** Human label for a link target, hiding the names of species the player has not yet found. */
    fun targetLabel(target: String, catalog: Catalog, discovered: Set<String>): String = when {
        target.startsWith("habitat:") -> HabitatType.valueOf(target.removePrefix("habitat:")).label.lowercase() + " habitat"
        target.startsWith("group:") -> "any of the " + LifeGroup.valueOf(target.removePrefix("group:")).label.lowercase()
        target in discovered -> catalog[target]?.common ?: target
        else -> "an undiscovered ${catalog[target]?.group?.label?.lowercase()?.trimEnd('s') ?: "species"}"
    }
}
