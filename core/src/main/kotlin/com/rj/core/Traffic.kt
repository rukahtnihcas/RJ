package com.rj.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.util.Locale
import kotlin.math.max

data class GeoPoint(val lat: Double, val lon: Double, val label: String? = null) {
    fun lonLat() = "%.5f,%.5f".format(Locale.US, lon, lat)
    fun latLon() = "%.5f,%.5f".format(Locale.US, lat, lon)
}

/**
 * Route and traffic from open data, with optional live traffic:
 * - OpenStreetMap Nominatim to find the destination (free, no key).
 * - OSRM (open-source routing on OpenStreetMap) for distance and empty-road time (free, no key).
 * - Live estimate from the driver's own pace on this trip.
 * - With a TomTom key (free tier): live-traffic ETA, current road speed vs normal, and incidents.
 */
class TrafficClient(
    private val tomTomKey: String? = null,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(12)).build(),
    private val nominatimBase: String = "https://nominatim.openstreetmap.org",
    private val osrmBase: String = "https://router.project-osrm.org",
    private val tomTomBase: String = "https://api.tomtom.com",
) {
    private val mapper = ObjectMapper()

    private fun json(url: String): JsonNode? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) null else mapper.readTree(r.body!!.string())
        }
    }.getOrNull()

    /** Find a place, biased towards where we are now. */
    fun geocode(query: String, near: GeoPoint? = null): GeoPoint? {
        val url = "$nominatimBase/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("format", "jsonv2").addQueryParameter("limit", "1")
            .apply {
                near?.let {
                    val d = 1.5 // ~150 km box, preferred but not exclusive
                    addQueryParameter("viewbox", "${it.lon - d},${it.lat + d},${it.lon + d},${it.lat - d}")
                }
            }.build().toString()
        val first = json(url)?.takeIf { it.isArray && it.size() > 0 }?.get(0) ?: return null
        return GeoPoint(first["lat"].asText().toDouble(), first["lon"].asText().toDouble(), first.path("name").asText(null))
    }

    data class Route(val km: Double, val minutes: Int, val trafficDelayMinutes: Int? = null)

    fun osrmRoute(from: GeoPoint, to: GeoPoint): Route? {
        val r = json("$osrmBase/route/v1/driving/${from.lonLat()};${to.lonLat()}?overview=false")
            ?.path("routes")?.path(0)?.takeIf { it.isObject } ?: return null
        return Route(r["distance"].asDouble() / 1000.0, (r["duration"].asDouble() / 60.0).toInt())
    }

    fun tomTomRoute(from: GeoPoint, to: GeoPoint): Route? {
        val key = tomTomKey ?: return null
        val s = json("$tomTomBase/routing/1/calculateRoute/${from.latLon()}:${to.latLon()}/json?traffic=true&key=$key")
            ?.path("routes")?.path(0)?.path("summary")?.takeIf { it.isObject } ?: return null
        return Route(
            km = s["lengthInMeters"].asDouble() / 1000.0,
            minutes = (s["travelTimeInSeconds"].asDouble() / 60.0).toInt(),
            trafficDelayMinutes = (s.path("trafficDelayInSeconds").asDouble(0.0) / 60.0).toInt(),
        )
    }

    /** Current speed vs free-flow speed on the road at this point. */
    fun tomTomFlow(at: GeoPoint): String? {
        val key = tomTomKey ?: return null
        val f = json("$tomTomBase/traffic/services/4/flowSegmentData/absolute/10/json?point=${at.latLon()}&unit=KMPH&key=$key")
            ?.path("flowSegmentData")?.takeIf { it.isObject } ?: return null
        return describeFlow(f.path("currentSpeed").asInt(), f.path("freeFlowSpeed").asInt(), f.path("roadClosure").asBoolean(false))
    }

    /** Incidents (accidents, closures, roadworks) in a box around us and the destination. */
    fun tomTomIncidents(a: GeoPoint, b: GeoPoint?): List<String> {
        val key = tomTomKey ?: return emptyList()
        val pad = 0.03
        val maxSpan = 0.8 // keep the box within TomTom's area limit
        val minLat = minOf(a.lat, b?.lat ?: a.lat) - pad
        val maxLat = (maxOf(a.lat, b?.lat ?: a.lat) + pad).coerceAtMost(minLat + maxSpan)
        val minLon = minOf(a.lon, b?.lon ?: a.lon) - pad
        val maxLon = (maxOf(a.lon, b?.lon ?: a.lon) + pad).coerceAtMost(minLon + maxSpan)
        val url = "$tomTomBase/traffic/services/5/incidentDetails".toHttpUrl().newBuilder()
            .addQueryParameter("bbox", "%.4f,%.4f,%.4f,%.4f".format(Locale.US, minLon, minLat, maxLon, maxLat))
            .addQueryParameter("fields", "{incidents{properties{iconCategory,magnitudeOfDelay,events{description},from,to,delay}}}")
            .addQueryParameter("language", "en-GB")
            .addQueryParameter("key", key)
            .build().toString()
        return parseIncidents(json(url) ?: return emptyList())
    }

    /**
     * Everything the RJ needs about the trip. [avgSpeedKph]/[minutesDriven] are the driver's own pace so far,
     * used to make the estimate realistic when there's no live traffic feed.
     */
    fun trafficInfo(here: GeoPoint, dest: GeoPoint, destName: String, avgSpeedKph: Double, minutesDriven: Long): TrafficInfo? {
        val free = osrmRoute(here, dest)
        val live = tomTomRoute(here, dest)
        val km = live?.km ?: free?.km ?: return null
        val freeMinutes = free?.minutes ?: live?.let { it.minutes - (it.trafficDelayMinutes ?: 0) } ?: return null
        val eta: Int
        val source: String
        if (live != null) {
            eta = live.minutes; source = "TomTom live traffic"
        } else {
            eta = paceEstimate(km, freeMinutes, avgSpeedKph, minutesDriven); source = "your pace + OpenStreetMap route"
        }
        return TrafficInfo(
            destination = destName,
            remainingKm = km,
            freeFlowMinutes = freeMinutes,
            etaMinutes = eta,
            flowNote = tomTomFlow(here),
            incidents = tomTomIncidents(here, dest),
            source = source,
        )
    }

    companion object {
        const val USER_AGENT = "RJ-car-companion/0.3 (personal Android app)"

        /**
         * Without live traffic, blend the empty-road time with the pace the driver has actually managed so far.
         * Early in a trip we trust the route; after ~10 minutes we lean on observed pace.
         */
        fun paceEstimate(km: Double, freeMinutes: Int, avgSpeedKph: Double, minutesDriven: Long): Int {
            if (minutesDriven < 5 || avgSpeedKph < 3) return freeMinutes
            val paceMinutes = (km / avgSpeedKph.coerceAtLeast(8.0) * 60).toInt()
            val weight = (minutesDriven / 10.0).coerceIn(0.0, 0.7)
            return max(freeMinutes, (freeMinutes * (1 - weight) + paceMinutes * weight).toInt())
        }

        fun describeFlow(current: Int, free: Int, closed: Boolean): String? {
            if (closed) return "road closed ahead"
            if (current <= 0 || free <= 0) return null
            val ratio = current.toDouble() / free
            val level = when {
                ratio >= 0.85 -> return null // flowing normally: nothing to say
                ratio >= 0.6 -> "busy"
                ratio >= 0.35 -> "heavy"
                else -> "standstill"
            }
            return "$level: $current km/h vs $free normally"
        }

        fun parseIncidents(root: JsonNode): List<String> =
            root.path("incidents").mapNotNull { inc ->
                val p = inc.path("properties")
                val what = p.path("events").path(0).path("description").asText("").ifBlank { return@mapNotNull null }
                val where = listOf(p.path("from").asText(""), p.path("to").asText("")).filter { it.isNotBlank() }
                val delay = p.path("delay").asInt(0) / 60
                buildString {
                    append(what)
                    if (where.isNotEmpty()) append(" (${where.joinToString(" to ")})")
                    if (delay >= 1) append(", +$delay min")
                } to p.path("magnitudeOfDelay").asInt(0)
            }.sortedByDescending { it.second }.map { it.first }.take(5)
    }
}
