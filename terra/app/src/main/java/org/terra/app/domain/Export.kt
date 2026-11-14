package org.terra.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.terra.app.model.Detection
import org.terra.app.model.Habitat
import org.terra.app.model.Landmark
import org.terra.app.model.LandmarkEntry
import org.terra.app.model.Observation
import org.terra.app.model.StewardAction
import org.terra.app.model.StillnessSession
import java.time.Instant

@Serializable
data class Backup(
    val version: Int = 1,
    val exportedAt: Long,
    val observations: List<Observation>,
    val habitats: List<Habitat>,
    val actions: List<StewardAction>,
    val landmarks: List<Landmark>,
    val entries: List<LandmarkEntry>,
    val sessions: List<StillnessSession>,
    val detections: List<Detection>
)

object Exporter {
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Open biodiversity data as GeoJSON with Darwin Core field names.
     * [research] = false produces the public layer: sensitive species are snapped to a coarse
     * grid and flagged. [research] = true keeps true coordinates, for vetted researchers only.
     */
    fun geoJson(observations: List<Observation>, catalog: Catalog, research: Boolean): JsonObject = buildJsonObject {
        put("type", "FeatureCollection")
        put("license", "CC-BY 4.0")
        put("audience", if (research) "vetted-researchers-only" else "public")
        put("features", buildJsonArray {
            for (o in observations) {
                val sensitive = o.sensitive || catalog[o.speciesId]?.sensitive == true
                val p = if (research) GeoFuzzer.Point(o.lat, o.lon) else GeoFuzzer.publicPoint(o.lat, o.lon, sensitive)
                add(buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("geometry") {
                        put("type", "Point")
                        putJsonArray("coordinates") { add(kotlinx.serialization.json.JsonPrimitive(p.lon)); add(kotlinx.serialization.json.JsonPrimitive(p.lat)) }
                    }
                    putJsonObject("properties") {
                        put("vernacularName", o.displayName)
                        catalog[o.speciesId]?.let { put("scientificName", it.scientific) }
                        put("eventDate", Instant.ofEpochMilli(o.timestamp).toString())
                        put("basisOfRecord", if (o.method.name == "PHOTO" || o.method.name == "MANUAL") "HumanObservation" else "MachineObservation")
                        put("samplingProtocol", o.method.label)
                        o.lifeStage?.let { put("lifeStage", it) }
                        val uncertainty = if (sensitive && !research) GeoFuzzer.UNCERTAINTY_M else o.accuracyM?.toInt()
                        uncertainty?.let { put("coordinateUncertaintyInMeters", it) }
                        if (sensitive) put("informationWithheld", if (research) "none" else "coordinates generalised to a ${GeoFuzzer.CELL_DEG}° grid to protect a sensitive species")
                    }
                })
            }
        })
    }

    fun backup(w: World, now: Long): String = json.encodeToString(
        Backup.serializer(),
        Backup(1, now, w.observations, w.habitats, w.actions, w.landmarks, w.entries, w.sessions, w.detections)
    )

    fun parseBackup(text: String): Backup = json.decodeFromString(Backup.serializer(), text)
}
