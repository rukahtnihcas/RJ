package org.terra.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.terra.app.model.LifeGroup
import org.terra.app.model.Observation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Source(val label: String) { INATURALIST("iNaturalist"), EBIRD("eBird · Merlin"), GBIF("GBIF") }

/** A record made by someone else and published through an open biodiversity database. */
data class CommunityRecord(
    val source: Source,
    val id: String,
    val name: String,
    val scientific: String,
    val group: LifeGroup,
    val lat: Double,
    val lon: Double,
    val date: String,
    /** The publisher has already generalised this position to protect the species or the observer. */
    val obscured: Boolean,
    val credit: String,
    val url: String
)

/** A species the community has recorded around a point, with how often. */
data class NearbySpecies(val name: String, val scientific: String, val group: LifeGroup, val count: Int, val taxonId: Long)

/**
 * Reading from, and writing to, the two great open biodiversity commons.
 * iNaturalist accepts individual observations; GBIF aggregates datasets from publishers, and
 * iNaturalist is one of them — so sharing a record with iNaturalist is also the route into GBIF
 * once the community confirms it ("research grade").
 */
object Community {
    const val INAT = "https://api.inaturalist.org/v1"
    const val GBIF = "https://api.gbif.org/v1"
    const val INAT_TOKEN_PAGE = "https://www.inaturalist.org/users/api_token"
    /** GBIF's copy of iNaturalist research-grade observations — skipped to avoid showing records twice. */
    const val GBIF_INAT_DATASET = "50c9509d-22c7-4a22-a47d-8c48425ef4a7"

    /**
     * The eBird Observation Dataset on GBIF. eBird is the database behind Merlin Bird ID; neither
     * offers a key-free API of its own, but eBird publishes its records here, where anyone may read them.
     * It is refreshed periodically rather than live, so the newest sightings lag.
     */
    const val GBIF_EBIRD_DATASET = "4fa7b334-ce0d-4e88-aaae-2e0c138d049e"
    const val INAT_WEB = "https://www.inaturalist.org"
    const val REDIRECT = "terra://inat-auth"

    fun gbifEbirdUrl(lat: Double, lon: Double, radiusKm: Double) = gbifUrl(lat, lon, radiusKm, 300) + "&datasetKey=$GBIF_EBIRD_DATASET"

    fun inatAuthorizeUrl(clientId: String, challenge: String) =
        "$INAT_WEB/oauth/authorize?client_id=$clientId&redirect_uri=${java.net.URLEncoder.encode(REDIRECT, "UTF-8")}&response_type=code&code_challenge=$challenge&code_challenge_method=S256"

    /** PKCE: the challenge is the URL-safe base64 of the SHA-256 of the verifier. */
    fun pkceChallenge(verifier: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Collapses individual records into species with counts, most-recorded first. [names] supplies common names where known. */
    fun speciesOf(records: List<CommunityRecord>, names: Map<String, String> = emptyMap()): List<NearbySpecies> =
        records.groupBy { it.scientific }.map { (sci, list) ->
            val first = list.first()
            NearbySpecies(names[sci.lowercase()] ?: first.name, sci, first.group, list.size, 0)
        }.sortedByDescending { it.count }

    /**
     * Merges what the three sources know about one habitat. A species keeps one row per source, so the
     * habitat page can say who recorded it; eBird is trusted for birds only.
     */
    fun habitatRows(habitatId: Long, inat: List<NearbySpecies>, ebird: List<NearbySpecies>, gbif: List<NearbySpecies>, now: Long): List<org.terra.app.model.HabitatSpecies> {
        fun rows(list: List<NearbySpecies>, source: Source) = list.filter { it.scientific.trim().contains(' ') }
            .map { org.terra.app.model.HabitatSpecies(0, habitatId, it.scientific, it.name, it.group, it.count, source.name, now) }
        return rows(inat, Source.INATURALIST) + rows(ebird.filter { it.group == LifeGroup.BIRD }, Source.EBIRD) + rows(gbif, Source.GBIF)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun f(d: Double) = String.format(Locale.US, "%.5f", d)

    fun inatObservationsUrl(lat: Double, lon: Double, radiusKm: Double) =
        "$INAT/observations?lat=${f(lat)}&lng=${f(lon)}&radius=$radiusKm&per_page=100&order=desc&order_by=observed_on&verifiable=true"

    fun inatSpeciesUrl(lat: Double, lon: Double, radiusKm: Double) =
        "$INAT/observations/species_counts?lat=${f(lat)}&lng=${f(lon)}&radius=$radiusKm&per_page=100&verifiable=true"

    fun inatTaxonUrl(scientific: String) = "$INAT/taxa?q=${java.net.URLEncoder.encode(scientific, "UTF-8")}&per_page=1"

    fun gbifUrl(lat: Double, lon: Double, radiusKm: Double, limit: Int = 100) =
        "$GBIF/occurrence/search?geoDistance=${f(lat)},${f(lon)},${radiusKm}km&hasCoordinate=true&occurrenceStatus=PRESENT&limit=$limit"

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonElement?.num(): Double? = str()?.toDoubleOrNull()

    fun groupOfIconic(iconic: String?): LifeGroup = when (iconic) {
        "Aves" -> LifeGroup.BIRD
        "Plantae" -> LifeGroup.PLANT
        "Fungi" -> LifeGroup.FUNGUS
        "Insecta", "Arachnida" -> LifeGroup.INSECT
        "Amphibia" -> LifeGroup.AMPHIBIAN
        "Reptilia" -> LifeGroup.REPTILE
        "Mammalia" -> LifeGroup.MAMMAL
        else -> LifeGroup.OTHER
    }

    private fun groupOfGbif(o: JsonObject): LifeGroup = when {
        o["class"].str() == "Aves" -> LifeGroup.BIRD
        o["kingdom"].str() == "Plantae" -> LifeGroup.PLANT
        o["kingdom"].str() == "Fungi" -> LifeGroup.FUNGUS
        o["class"].str() in setOf("Insecta", "Arachnida") -> LifeGroup.INSECT
        o["class"].str() == "Amphibia" -> LifeGroup.AMPHIBIAN
        o["class"].str() in setOf("Squamata", "Testudines", "Crocodylia", "Reptilia") -> LifeGroup.REPTILE
        o["class"].str() == "Mammalia" -> LifeGroup.MAMMAL
        else -> LifeGroup.OTHER
    }

    fun parseInatObservations(text: String): List<CommunityRecord> {
        val results = json.parseToJsonElement(text).obj()?.get("results") as? JsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val taxon = o["taxon"].obj() ?: return@mapNotNull null
            val coords = (o["geojson"].obj()?.get("coordinates") as? JsonArray)
            val lon = coords?.getOrNull(0).num()
            val lat = coords?.getOrNull(1).num()
            if (lat == null || lon == null) return@mapNotNull null
            val id = o["id"].str() ?: return@mapNotNull null
            val sci = taxon["name"].str() ?: return@mapNotNull null
            CommunityRecord(
                Source.INATURALIST, id, taxon["preferred_common_name"].str() ?: o["species_guess"].str() ?: sci, sci,
                groupOfIconic(taxon["iconic_taxon_name"].str()), lat, lon, o["observed_on"].str().orEmpty(),
                o["obscured"].str() == "true",
                "© " + (o["user"].obj()?.get("login").str() ?: "observer") + (o["license_code"].str()?.let { " · ${it.uppercase()}" } ?: " · all rights reserved"),
                o["uri"].str() ?: "https://www.inaturalist.org/observations/$id"
            )
        }
    }

    fun parseInatSpecies(text: String): List<NearbySpecies> {
        val results = json.parseToJsonElement(text).obj()?.get("results") as? JsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val taxon = o["taxon"].obj() ?: return@mapNotNull null
            val sci = taxon["name"].str() ?: return@mapNotNull null
            NearbySpecies(taxon["preferred_common_name"].str() ?: sci, sci, groupOfIconic(taxon["iconic_taxon_name"].str()),
                o["count"].num()?.toInt() ?: 0, taxon["id"].num()?.toLong() ?: 0)
        }
    }

    fun parseInatTaxonId(text: String): Long? =
        (json.parseToJsonElement(text).obj()?.get("results") as? JsonArray)?.firstOrNull().obj()?.get("id").num()?.toLong()

    fun parseGbif(text: String): List<CommunityRecord> {
        val results = json.parseToJsonElement(text).obj()?.get("results") as? JsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            if (o["datasetKey"].str() == GBIF_INAT_DATASET) return@mapNotNull null
            val lat = o["decimalLatitude"].num()
            val lon = o["decimalLongitude"].num()
            val key = o["key"].str()
            val sci = o["species"].str() ?: o["scientificName"].str()
            if (lat == null || lon == null || key == null || sci == null) return@mapNotNull null
            CommunityRecord(
                if (o["datasetKey"].str() == GBIF_EBIRD_DATASET) Source.EBIRD else Source.GBIF, key, o["vernacularName"].str() ?: sci, sci, groupOfGbif(o), lat, lon,
                o["eventDate"].str()?.take(10).orEmpty(), o["informationWithheld"].str() != null || o["dataGeneralizations"].str() != null,
                listOfNotNull(if (o["datasetKey"].str() == GBIF_EBIRD_DATASET) "eBird, Cornell Lab of Ornithology" else o["datasetName"].str() ?: o["institutionCode"].str(), o["license"].str()?.substringAfter("licenses/")?.trim('/')?.let { "CC " + it.uppercase() }).joinToString(" · ").ifBlank { "via GBIF" },
                "https://www.gbif.org/occurrence/$key"
            )
        }
    }

    /**
     * The body for creating an iNaturalist observation. Sensitive species are sent with
     * geoprivacy "obscured", so iNaturalist (and GBIF after it) only ever publish a generalised position.
     */
    fun inatBody(o: Observation, scientific: String?, taxonId: Long?, sensitive: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val notes = listOfNotNull(
            o.note.takeIf { it.isNotBlank() }, o.lifeStage?.let { "Life stage / sign: $it" }, o.phenotype?.let { "Form: $it" },
            if (o.heritage) "Recorded as an ancient or heritage tree." else null,
            "Recorded with TERRA (${o.method.label.lowercase()})."
        ).joinToString("\n")
        return buildJsonObject {
            putJsonObject("observation") {
                put("species_guess", scientific ?: o.displayName)
                taxonId?.let { put("taxon_id", it) }
                put("observed_on_string", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(o.timestamp).atZone(zone)))
                put("latitude", o.lat)
                put("longitude", o.lon)
                o.accuracyM?.let { put("positional_accuracy", it.toInt().coerceAtLeast(1)) }
                put("geoprivacy", if (sensitive) "obscured" else "open")
                put("description", notes)
            }
        }.toString()
    }

    private fun csv(v: String?) = if (v == null) "" else if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /**
     * A Darwin Core occurrence table (CSV) — the format GBIF publishers load into the IPT, and one
     * iNaturalist's bulk import can read. Coordinates follow the same public/research rule as the GeoJSON.
     */
    fun darwinCoreCsv(observations: List<Observation>, catalog: Catalog, research: Boolean): String {
        val head = "occurrenceID,basisOfRecord,scientificName,vernacularName,eventDate,decimalLatitude,decimalLongitude,geodeticDatum,coordinateUncertaintyInMeters,lifeStage,occurrenceRemarks,samplingProtocol,informationWithheld,license"
        val rows = observations.map { o ->
            val s = catalog[o.speciesId]
            val sensitive = o.sensitive || s?.sensitive == true
            val p = if (research) GeoFuzzer.Point(o.lat, o.lon) else GeoFuzzer.publicPoint(o.lat, o.lon, sensitive)
            listOf(
                "terra:${o.timestamp}-${o.id}", if (o.method.name == "PHOTO" || o.method.name == "MANUAL") "HumanObservation" else "MachineObservation",
                s?.scientific, o.displayName, Instant.ofEpochMilli(o.timestamp).toString(), f(p.lat), f(p.lon), "WGS84",
                (if (sensitive && !research) GeoFuzzer.UNCERTAINTY_M else o.accuracyM?.toInt())?.toString(), o.lifeStage,
                listOfNotNull(o.phenotype, o.note.takeIf { it.isNotBlank() }).joinToString("; ").ifBlank { null }, o.method.label,
                if (sensitive && !research) "coordinates generalised to a ${GeoFuzzer.CELL_DEG} degree grid" else null, "CC-BY 4.0"
            ).joinToString(",") { csv(it) }
        }
        return (listOf(head) + rows).joinToString("\n") + "\n"
    }
}
