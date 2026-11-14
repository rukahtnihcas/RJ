package org.terra.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.terra.app.model.Habitat
import org.terra.app.model.HabitatSource
import org.terra.app.model.HabitatType

/** Charts real green and blue spaces from OpenStreetMap so the game works on any street on Earth. */
object Overpass {
    const val ENDPOINT = "https://overpass-api.de/api/interpreter"

    fun query(lat: Double, lon: Double, radiusM: Int): String {
        val a = "(around:$radiusM,$lat,$lon)"
        return """
            [out:json][timeout:25];
            (
              way[leisure~"^(park|nature_reserve|garden)$"]$a;
              relation[leisure~"^(park|nature_reserve)$"]$a;
              way[natural~"^(wood|wetland|water|scrub|heath|grassland)$"]$a;
              relation[natural~"^(wood|wetland|water)$"]$a;
              way[landuse~"^(forest|meadow|village_green|allotments)$"]$a;
            );
            out tags bb 80;
        """.trimIndent()
    }

    fun typeOf(tags: Map<String, String>): HabitatType? = when {
        tags["natural"] == "wetland" -> HabitatType.WETLAND
        tags["natural"] == "water" -> HabitatType.WATER
        tags["natural"] == "wood" || tags["landuse"] == "forest" -> HabitatType.WOODLAND
        tags["natural"] == "scrub" || tags["natural"] == "heath" -> HabitatType.SCRUB
        tags["natural"] == "grassland" || tags["landuse"] == "meadow" -> HabitatType.MEADOW
        tags["leisure"] == "garden" || tags["landuse"] == "allotments" -> HabitatType.GARDEN
        tags["leisure"] == "park" || tags["leisure"] == "nature_reserve" || tags["landuse"] == "village_green" -> HabitatType.PARK
        else -> null
    }

    fun parse(text: String, now: Long): List<Habitat> {
        val root = Json.parseToJsonElement(text).jsonObject
        val elements = root["elements"]?.jsonArray ?: return emptyList()
        return elements.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val tags = (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: return@mapNotNull null
            val type = typeOf(tags) ?: return@mapNotNull null
            val b = o["bounds"] as? JsonObject ?: return@mapNotNull null
            val minLat = b.getValue("minlat").jsonPrimitive.double
            val maxLat = b.getValue("maxlat").jsonPrimitive.double
            val minLon = b.getValue("minlon").jsonPrimitive.double
            val maxLon = b.getValue("maxlon").jsonPrimitive.double
            val lat = (minLat + maxLat) / 2
            val lon = (minLon + maxLon) / 2
            // Radius of a circle with the bounding box's area: fair for both round ponds and long parks.
            val h = Geo.distanceM(minLat, lon, maxLat, lon)
            val w = Geo.distanceM(lat, minLon, lat, maxLon)
            val radius = kotlin.math.sqrt(h * w / Math.PI)
            if (radius < 12 || radius > 4000) return@mapNotNull null
            val id = o.getValue("id").jsonPrimitive.long
            val sign = if (o["type"]?.jsonPrimitive?.content == "relation") -1 else 1
            Habitat(
                name = tags["name"] ?: "Unnamed ${type.label.lowercase()}", type = type, lat = lat, lon = lon,
                radiusM = radius, source = HabitatSource.OSM, osmId = sign * id, createdAt = now
            )
        }.distinctBy { it.osmId }
    }
}
