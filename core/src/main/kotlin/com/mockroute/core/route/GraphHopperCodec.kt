package com.mockroute.core.route

import com.mockroute.core.geo.LatLon
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * GraphHopper Directions API (free plan: 500 credits/day, non-commercial, profiles car/bike/foot).
 * POST https://graphhopper.com/api/1/route?key=KEY with a JSON body.
 */
object GraphHopperCodec {
    const val BASE_URL = "https://graphhopper.com/api/1/route"
    const val PROVIDER = "GraphHopper"
    const val MAX_WAYPOINTS = 5 // free plan limit

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    fun profileName(profile: RoutingProfile): String = when (profile) {
        RoutingProfile.Walk -> "foot"
        RoutingProfile.Car -> "car"
        RoutingProfile.Bus -> "car" // no bus/truck profile on the free plan
    }

    @Serializable
    data class Request(
        val points: List<List<Double>>,
        val profile: String,
        @SerialName("points_encoded") val pointsEncoded: Boolean = false,
        val instructions: Boolean = false,
        @SerialName("calc_points") val calcPoints: Boolean = true,
        val details: List<String> = listOf("max_speed", "road_class", "average_speed"),
    )

    fun requestBody(points: List<LatLon>, profile: RoutingProfile): String {
        require(points.size in 2..MAX_WAYPOINTS) { "GraphHopper free plan needs 2..$MAX_WAYPOINTS points" }
        return json.encodeToString(Request.serializer(), Request(points.map { listOf(it.lon, it.lat) }, profileName(profile)))
    }

    @Serializable
    data class Response(val paths: List<Path> = emptyList())

    @Serializable
    data class Path(
        val distance: Double = 0.0,
        /** milliseconds */
        val time: Long = 0,
        val points: LineString = LineString(),
        val details: Map<String, JsonElement> = emptyMap(),
    )

    @Serializable
    data class LineString(val type: String = "LineString", val coordinates: List<List<Double>> = emptyList())

    fun parse(body: String, profile: RoutingProfile, providerId: String = "graphhopper"): RouteResult {
        val resp = try {
            json.decodeFromString(Response.serializer(), body)
        } catch (e: Exception) {
            throw RoutingException.Parse(PROVIDER, e)
        }
        val path = resp.paths.firstOrNull() ?: throw RoutingException.NoRoute(PROVIDER, null)
        val coords = path.points.coordinates
        if (coords.size < 2) throw RoutingException.NoRoute(PROVIDER, null)
        val geometry = RouteGeometry.of(coords.map { LatLon(it[1], it[0]) })

        val speed = intervals(path.details["max_speed"], geometry.size)
        val roadClass = intervals(path.details["road_class"], geometry.size)
        // Merge both detail streams into spans split at every boundary.
        val cuts = (speed.flatMap { listOf(it.first, it.second) } + roadClass.flatMap { listOf(it.first, it.second) })
            .distinct().sorted()
        val spans = cuts.zipWithNext().mapNotNull { (from, to) ->
            if (to <= from) return@mapNotNull null
            val s = speed.firstOrNull { from >= it.first && to <= it.second }?.third?.jsonPrimitive?.doubleOrNull
            val rc = roadClass.firstOrNull { from >= it.first && to <= it.second }?.third?.jsonPrimitive?.contentOrNull
            if (s == null && rc == null) null
            else RouteSpan(geometry.cumulativeM(from), geometry.cumulativeM(to), speedLimitKmh = s, roadClass = rc)
        }
        // Provider speed per edge (average_speed, km/h).
        val speeds = intervals(path.details["average_speed"], geometry.size).mapNotNull { (from, to, v) ->
            val kmh = v.jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            val a = geometry.cumulativeM(from)
            val b = geometry.cumulativeM(to)
            if (kmh <= 0 || b - a < 0.5) null else SpeedSpan(a, b, kmh / 3.6)
        }
        return RouteResult(
            geometry = geometry,
            providerSpeeds = speeds,
            distanceM = if (path.distance > 0) path.distance else geometry.lengthM,
            durationS = (path.time / 1000.0).takeIf { it > 0 },
            providerId = providerId,
            profile = profile,
            spans = spans,
        )
    }

    /** Detail arrays look like [[from, to, value], ...] with value number|string|null. */
    private fun intervals(el: JsonElement?, n: Int): List<Triple<Int, Int, JsonElement>> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val a = item.jsonArray
            if (a.size < 3) return@mapNotNull null
            val from = a[0].jsonPrimitive.doubleOrNull?.toInt()?.coerceIn(0, n - 1) ?: return@mapNotNull null
            val to = a[1].jsonPrimitive.doubleOrNull?.toInt()?.coerceIn(0, n - 1) ?: return@mapNotNull null
            if (a[2] is JsonNull) null else Triple(from, to, a[2])
        }
    }

    /** GraphHopper errors: {"message":"..."}; 401 bad key, 429 credits/rate limit, 400 e.g. point not found. */
    fun error(httpCode: Int, body: String?): RoutingException {
        val message = runCatching { json.parseToJsonElement(body ?: "").jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        return when (httpCode) {
            401 -> RoutingException.Unauthorized(PROVIDER)
            403 -> RoutingException.Forbidden(PROVIDER)
            429 -> RoutingException.RateLimited(PROVIDER)
            400 -> if (message != null && (message.contains("point", true) || message.contains("connection", true))) {
                RoutingException.NoRoute(PROVIDER, message)
            } else {
                RoutingException.Http(PROVIDER, httpCode, message)
            }
            else -> RoutingException.Http(PROVIDER, httpCode, message)
        }
    }
}
