package org.terra.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.terra.app.domain.Community
import org.terra.app.domain.CommunityRecord
import org.terra.app.domain.NearbySpecies
import org.terra.app.model.Observation
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String) : Exception(message)

/** Thin HTTP layer over the public iNaturalist and GBIF APIs. Reads need no account. */
object CommunityApi {
    private const val AGENT = "TERRA-field-guide/0.2 (Android)"

    private fun open(url: String, token: String? = null): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", AGENT)
        setRequestProperty("Accept", "application/json")
        token?.let { setRequestProperty("Authorization", it) }
    }

    private fun HttpURLConnection.body(): String {
        val code = responseCode
        if (code !in 200..299) {
            val detail = runCatching { errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty().take(200)
            throw ApiException(code, "HTTP $code $detail")
        }
        return inputStream.bufferedReader().use { it.readText() }
    }

    private fun get(url: String) = open(url).body()

    data class Nearby(val records: List<CommunityRecord>, val species: List<NearbySpecies>, val failed: List<String>)

    /** Everything the commons knows about the ground around a point. One source failing does not hide the other. */
    suspend fun nearby(lat: Double, lon: Double, radiusKm: Double = 1.5): Nearby = withContext(Dispatchers.IO) {
        coroutineScope {
            val inat = async { runCatching { Community.parseInatObservations(get(Community.inatObservationsUrl(lat, lon, radiusKm))) } }
            val gbif = async { runCatching { Community.parseGbif(get(Community.gbifUrl(lat, lon, radiusKm))) } }
            val species = async { runCatching { Community.parseInatSpecies(get(Community.inatSpeciesUrl(lat, lon, radiusKm))) } }
            val failed = buildList {
                if (inat.await().isFailure || species.await().isFailure) add("iNaturalist")
                if (gbif.await().isFailure) add("GBIF")
            }
            Nearby(inat.await().getOrDefault(emptyList()) + gbif.await().getOrDefault(emptyList()), species.await().getOrDefault(emptyList()), failed)
        }
    }

    /**
     * Creates the observation on iNaturalist as the token's owner and attaches its photo.
     * Returns the new iNaturalist observation id.
     */
    suspend fun shareToInat(o: Observation, scientific: String?, sensitive: Boolean, token: String): Long = withContext(Dispatchers.IO) {
        val taxonId = scientific?.let { runCatching { Community.parseInatTaxonId(get(Community.inatTaxonUrl(it))) }.getOrNull() }
        val conn = open("${Community.INAT}/observations", token).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(Community.inatBody(o, scientific, taxonId, sensitive).toByteArray()) }
        val id = Json.parseToJsonElement(conn.body()).jsonObject.getValue("id").jsonPrimitive.long
        o.photoPath?.let(::File)?.takeIf { it.exists() }?.let { photo -> runCatching { attachPhoto(id, photo, token) } }
        id
    }

    private fun attachPhoto(observationId: Long, photo: File, token: String) {
        val boundary = "terra" + System.nanoTime()
        val conn = open("${Community.INAT}/observation_photos", token).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        conn.outputStream.use { out ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"observation_photo[observation_id]\"\r\n\r\n$observationId\r\n".toByteArray())
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${photo.name}\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
            photo.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        conn.body()
    }
}
