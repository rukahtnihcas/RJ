package org.terra.app.domain

import kotlinx.serialization.json.Json
import org.terra.app.model.HabitatType
import org.terra.app.model.LifeGroup
import org.terra.app.model.Region
import org.terra.app.model.Season
import org.terra.app.model.Species

class Catalog(val species: List<Species>) {
    private val byId = species.associateBy { it.id }
    operator fun get(id: String): Species? = byId[id]

    fun inRegion(region: Region) = species.filter { it.occursIn(region) }

    data class Suggestion(val species: Species, val score: Float, val why: String)

    /**
     * Turn generic image labels ("Bird 0.82", "Flower 0.4") into a ranked short-list.
     * The camera narrows the search; place, season and habitat order it; the observer decides.
     */
    fun suggest(
        labels: List<Pair<String, Float>>, region: Region, season: Season,
        habitat: HabitatType?, limit: Int = 12
    ): List<Suggestion> {
        val groups = groupsSeen(labels)
        return species.mapNotNull { s ->
            var score = 0f
            val why = ArrayList<String>()
            val visual = labels.filter { (text, _) -> s.visual.any { text.contains(it, true) } }
                .maxOfOrNull { it.second }
            if (visual != null) { score += 1f + visual; why += "looks right" }
            else if (groups[s.group] != null) { score += 0.6f * groups.getValue(s.group) }
            if (score == 0f && labels.isNotEmpty()) return@mapNotNull null
            if (s.occursIn(region)) { score += 0.8f; why += "found in ${region.label}" } else score -= 1.5f
            if (season in s.seasons) { score += 0.3f; why += "in season" }
            if (habitat != null && habitat in s.habitats) { score += 0.3f; why += "fits this habitat" }
            if (score <= 0f) null else Suggestion(s, score, why.joinToString(" · "))
        }.sortedByDescending { it.score }.take(limit)
    }

    fun search(query: String, region: Region?): List<Species> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return species.filter { it.common.contains(q, true) || it.scientific.contains(q, true) }
            .sortedBy { if (region != null && it.occursIn(region)) 0 else 1 }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String) = Catalog(json.decodeFromString<List<Species>>(text))

        /**
         * Maps the vocabulary of the bundled on-device labeller onto life groups. That model knows
         * birds, plants, trees and insects; it has no words for fungi, frogs, reptiles or most
         * mammals, so for those the observer picks the group by hand.
         */
        private val keywords: List<Pair<LifeGroup, List<String>>> = listOf(
            LifeGroup.BIRD to listOf("bird", "waterfowl", "duck", "penguin", "goose", "owl", "eagle", "parrot", "pigeon"),
            LifeGroup.FUNGUS to listOf("mushroom", "fungus", "lichen", "moss"),
            LifeGroup.TREE to listOf("tree", "trunk", "branch", "twig", "forest", "jungle"),
            LifeGroup.PLANT to listOf("flower", "petal", "plant", "flora", "leaf", "grass", "garden", "prairie", "vegetable"),
            LifeGroup.INSECT to listOf("insect", "butterfly", "larva", "pest", "moth", "bee", "beetle", "spider", "dragonfly"),
            LifeGroup.AMPHIBIAN to listOf("frog", "toad", "amphibian"),
            LifeGroup.REPTILE to listOf("reptile", "lizard", "snake", "turtle", "crocodile"),
            LifeGroup.MAMMAL to listOf("mammal", "squirrel", "gerbil", "fur", "dog", "cat", "pet", "bear", "horse", "cattle", "monkey", "primate")
        )

        fun groupOf(label: String): LifeGroup? {
            val words = label.lowercase().split(' ', '-', '/')
            return keywords.firstOrNull { (_, keys) -> words.any { w -> keys.any { w == it || w == it + "s" } } }?.first
        }

        /** Strongest confidence per life group across all labels. */
        fun groupsSeen(labels: List<Pair<String, Float>>): Map<LifeGroup, Float> {
            val out = HashMap<LifeGroup, Float>()
            for ((text, conf) in labels) {
                val g = groupOf(text) ?: continue
                if (conf > (out[g] ?: 0f)) out[g] = conf
            }
            return out
        }
    }
}
