package org.terra.app.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Broad life groups used by the Codex, bio-scans and bounties. */
enum class LifeGroup(val label: String, val glyph: String) {
    BIRD("Birds", "🐦"), PLANT("Wildflowers & herbs", "🌿"), TREE("Trees & shrubs", "🌳"),
    FUNGUS("Fungi & lichens", "🍄"), INSECT("Insects & kin", "🐞"), AMPHIBIAN("Amphibians", "🐸"),
    REPTILE("Reptiles", "🦎"), MAMMAL("Mammals", "🐿"), OTHER("Other life", "🐚")
}

enum class Season(val label: String, val theme: String, val focus: String) {
    SPRING("Spring", "Arrivals & ephemerals",
        "Migrants return, buds break and short-lived wildflowers race the closing canopy."),
    SUMMER("Summer", "Night shift & pollinators",
        "Long evenings bring moths, fireflies, bats and frog choruses. Meadows hum."),
    AUTUMN("Autumn", "Seeds & fungi",
        "Seeds travel by wind, fur and gut. After rain, fungi fruit from the leaf litter."),
    WINTER("Winter", "Tracks & textures",
        "A tracker's season: footprints, bark, evergreen lichens and the birds that stay.")
}

enum class Region(val label: String) {
    NA("North America"), SA("South & Central America"), EU("Europe"), AF("Africa"),
    AS("Asia"), OC("Oceania");

    companion object {
        /** Very coarse continental lookup — enough to say "native here" vs "introduced here". */
        fun of(lat: Double, lon: Double): Region = when {
            lon < -25 && lat >= 13 -> NA
            lon < -25 -> SA
            lon >= 110 && lat < -8 -> OC
            lon >= 165 && lat < 30 -> OC
            lon in -25.0..52.0 && lat < 35 && !(lon > 34 && lat > 12) -> AF
            lon in -25.0..45.0 && lat >= 35 -> EU
            lon in 45.0..60.0 && lat >= 50 -> EU
            else -> AS
        }
    }
}

enum class HabitatType(val label: String, val glyph: String) {
    PARK("Park", "🏞"), WOODLAND("Woodland", "🌲"), WETLAND("Wetland", "🪷"), WATER("Open water", "💧"),
    MEADOW("Meadow & grassland", "🌾"), GARDEN("Garden", "🌼"), CANOPY("Urban canopy", "🌳"),
    SCRUB("Scrub & heath", "🪨")
}

enum class LinkType(val verb: String) {
    HOST_PLANT("raises its young on"), POLLINATES("pollinates"), EATS("feeds on"),
    EATEN_BY("is food for"), SHELTERS_IN("shelters or nests in"), DISPERSES("spreads the seeds of"),
    FUNGAL_PARTNER("trades nutrients with"), DECOMPOSES("breaks down"), COMPETES_WITH("crowds out"),
    BROOD_PARASITE("lays its eggs in the nest of")
}

@Serializable
data class EcoLink(val type: LinkType, val targets: List<String>, val hint: String)

@Serializable
data class Phenotype(val id: String, val name: String, val hint: String)

@Serializable
data class Acoustic(
    val lowHz: Int, val highHz: Int,
    /** Syllables / pulses per second, min–max. */
    val rateMin: Float, val rateMax: Float,
    /** true = pure whistled/tonal notes, false = buzzy, harsh or broadband. */
    val tonal: Boolean,
    val sounds: String
)

@Serializable
data class Species(
    val id: String,
    val common: String,
    val scientific: String,
    val group: LifeGroup,
    val glyph: String,
    val nativeTo: List<Region> = emptyList(),
    val introducedTo: List<Region> = emptyList(),
    /** Regions where it is a damaging invasive worth removing/reporting. */
    val invasiveIn: List<Region> = emptyList(),
    /** Sensitive species: public coordinates are always obscured. */
    val sensitive: Boolean = false,
    val seasons: List<Season> = Season.entries,
    val habitats: List<HabitatType> = emptyList(),
    val stages: List<String> = emptyList(),
    val phenotypes: List<Phenotype> = emptyList(),
    val links: List<EcoLink> = emptyList(),
    val acoustic: Acoustic? = null,
    /** Words a general-purpose image labeller might emit for this organism. */
    val visual: List<String> = emptyList(),
    val blurb: String,
    val deeper: String
) {
    fun occursIn(region: Region) = region in nativeTo || region in introducedTo || region in invasiveIn
    fun isInvasiveIn(region: Region) = region in invasiveIn
    fun standing(region: Region): String = when {
        region in invasiveIn -> "Invasive here"
        region in nativeTo -> "Native here"
        region in introducedTo -> "Introduced here"
        else -> "Not expected here"
    }
}

enum class ObsMethod(val label: String) {
    PHOTO("Bio-scan"), ACOUSTIC("Acoustic sweep"), STILLNESS("Stillness"), MANUAL("Field note")
}

@Serializable
@Entity(tableName = "observations", indices = [Index("speciesId"), Index("habitatId")])
data class Observation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Catalog id, or "custom:<name>" for life not yet in the guide. */
    val speciesId: String,
    val displayName: String,
    val group: LifeGroup,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val timestamp: Long,
    val method: ObsMethod,
    val photoPath: String? = null,
    val lifeStage: String? = null,
    val phenotype: String? = null,
    val heritage: Boolean = false,
    val note: String = "",
    val habitatId: Long? = null,
    val sensitive: Boolean = false
)

enum class HabitatSource { OSM, PLAYER }

@Serializable
@Entity(tableName = "habitats", indices = [Index(value = ["osmId"], unique = true)])
data class Habitat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: HabitatType,
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
    val source: HabitatSource,
    val osmId: Long? = null,
    val firstVisitedAt: Long? = null,
    val createdAt: Long
)

enum class ActionKind(val label: String, val prompt: String, val glyph: String) {
    PLANT_NATIVE("Plant natives", "Planted native pollinator plants, shrubs or trees", "🌱"),
    REMOVE_INVASIVE("Remove invasives", "Cleared an invasive plant such as ivy or knotweed", "✂"),
    CLEANUP("Clean-up", "Removed litter or micro-plastics from land or a waterway", "🧤")
}

@Serializable
@Entity(tableName = "actions", indices = [Index("habitatId")])
data class StewardAction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitatId: Long,
    val kind: ActionKind,
    val note: String = "",
    val beforePhoto: String,
    val beforeLat: Double,
    val beforeLon: Double,
    val beforeAt: Long,
    val afterPhoto: String? = null,
    val afterLat: Double? = null,
    val afterLon: Double? = null,
    val afterAt: Long? = null
)

enum class LandmarkKind(val label: String, val glyph: String) {
    SAPLING("Sapling", "🌱"), OLD_GROWTH("Old-growth tree", "🌳"), HERITAGE("Heritage tree", "🏛"),
    POND("Pond or spring", "💧"), MEADOW_PATCH("Wild patch", "🌾"), OTHER("Other landmark", "📍")
}

@Serializable
@Entity(tableName = "landmarks")
data class Landmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: LandmarkKind,
    val speciesName: String = "",
    val lat: Double,
    val lon: Double,
    val adoptedAt: Long
)

@Serializable
@Entity(tableName = "landmark_entries", indices = [Index("landmarkId")])
data class LandmarkEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val landmarkId: Long,
    val timestamp: Long,
    val photoPath: String? = null,
    val note: String = "",
    val girthCm: Float? = null
)

@Serializable
@Entity(tableName = "stillness_sessions")
data class StillnessSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val durationSec: Int,
    val lat: Double? = null,
    val lon: Double? = null,
    val completed: Boolean = false,
    /** Share of the sit that was genuinely quiet (0–1). */
    val quietFraction: Float = 0f
)

@Serializable
@Entity(tableName = "detections", indices = [Index("sessionId")])
data class Detection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val speciesId: String,
    val score: Float,
    /** 0 = awaiting the player's ear, 1 = confirmed, 2 = dismissed. */
    val resolved: Int = 0
)
