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

    /** What iNaturalist, eBird (via GBIF) and the rest of GBIF hold for one habitat. Null if nothing could be reached. */
    suspend fun habitat(h: org.terra.app.model.Habitat, now: Long): List<org.terra.app.model.HabitatSpecies>? = withContext(Dispatchers.IO) {
        val km = (h.radiusM / 1000).coerceIn(0.05, 5.0).let { Math.round(it * 100) / 100.0 }
        coroutineScope {
            val inat = async { runCatching { Community.parseInatSpecies(get(Community.inatSpeciesUrl(h.lat, h.lon, km))) } }
            val ebird = async { runCatching { Community.parseGbif(get(Community.gbifEbirdUrl(h.lat, h.lon, km))) } }
            val gbif = async { runCatching { Community.parseGbif(get(Community.gbifUrl(h.lat, h.lon, km, 300))).filter { it.source == org.terra.app.domain.Source.GBIF } } }
            if (inat.await().isFailure && ebird.await().isFailure && gbif.await().isFailure) return@coroutineScope null
            val names = inat.await().getOrDefault(emptyList()).associate { it.scientific.lowercase() to it.name }
            Community.habitatRows(h.id, inat.await().getOrDefault(emptyList()), Community.speciesOf(ebird.await().getOrDefault(emptyList()), names),
                Community.speciesOf(gbif.await().getOrDefault(emptyList()), names), now)
        }
    }

    /** Exchanges a sign-in code for a lasting iNaturalist access token (PKCE: no client secret is shipped in the app). */
    suspend fun inatExchange(clientId: String, code: String, verifier: String): String = withContext(Dispatchers.IO) {
        val conn = open("${Community.INAT_WEB}/oauth/token").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val form = mapOf("client_id" to clientId, "code" to code, "redirect_uri" to Community.REDIRECT, "grant_type" to "authorization_code", "code_verifier" to verifier)
        conn.outputStream.use { out -> out.write(form.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }.toByteArray()) }
        Json.parseToJsonElement(conn.body()).jsonObject.getValue("access_token").jsonPrimitive.content
    }

    /** Trades the lasting access token for the short-lived API token iNaturalist's API expects. Done silently, as needed. */
    suspend fun inatApiToken(accessToken: String): String = withContext(Dispatchers.IO) {
        Json.parseToJsonElement(open("${Community.INAT_WEB}/users/api_token", "Bearer $accessToken").body()).jsonObject.getValue("api_token").jsonPrimitive.content
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
