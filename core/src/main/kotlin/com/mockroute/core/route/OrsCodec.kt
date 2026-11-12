package com.mockroute.core.route

import com.mockroute.core.geo.LatLon
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * openrouteservice Directions v2, GeoJSON output.
 * POST {BASE_URL}/v2/directions/{profile}/geojson, header `Authorization: <key>`.
 */
object OrsCodec {
    /** HeiGIT moved ORS here; api.openrouteservice.org was deprecated 28 Apr 2026 and shut down Nov 2026. */
    const val BASE_URL = "https://api.heigit.org/openrouteservice"
    const val PROVIDER = "OpenRouteService"
    const val MAX_WAYPOINTS = 50

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    fun profilePath(profile: RoutingProfile): String = when (profile) {
        RoutingProfile.Walk -> "foot-walking"
        RoutingProfile.Car -> "driving-car"
        RoutingProfile.Bus -> "driving-hgv" // closest public profile to a city bus
    }

    fun url(profile: RoutingProfile): String = "$BASE_URL/v2/directions/${profilePath(profile)}/geojson"

    @Serializable
    data class Request(
        val coordinates: List<List<Double>>,
        @SerialName("extra_info") val extraInfo: List<String> = listOf("waytype"),
        // Steps carry the provider's distance & duration per street: the default speed profile (1.2.0).
        val instructions: Boolean = true,
        val elevation: Boolean = false,
    )

    fun requestBody(points: List<LatLon>): String {
        require(points.size in 2..MAX_WAYPOINTS) { "ORS needs 2..$MAX_WAYPOINTS points" }
        return json.encodeToString(Request.serializer(), Request(points.map { listOf(it.lon, it.lat) }))
    }

    // ---- response models -------------------------------------------------------------------

    @Serializable
    data class Response(val features: List<Feature> = emptyList())

    @Serializable
    data class Feature(val geometry: Geometry, val properties: Properties = Properties())

    @Serializable
    data class Geometry(val type: String = "LineString", val coordinates: List<List<Double>> = emptyList())

    @Serializable
    data class Properties(
        val summary: Summary = Summary(),
        val extras: Map<String, Extra> = emptyMap(),
        @SerialName("way_points") val wayPoints: List<Int> = emptyList(),
        val segments: List<Segment> = emptyList(),
    )

    @Serializable
    data class Segment(val distance: Double = 0.0, val duration: Double = 0.0, val steps: List<Step> = emptyList())

    /** One instruction step: way_points = [fromIndex, toIndex] into the coordinate list. */
    @Serializable
    data class Step(
        val distance: Double = 0.0,
        val duration: Double = 0.0,
        @SerialName("way_points") val wayPoints: List<Int> = emptyList(),
    )

    @Serializable
    data class Summary(val distance: Double = 0.0, val duration: Double = 0.0)

    /** values = [[fromIndex, toIndex, code], ...] (indices into the coordinate list). */
    @Serializable
    data class Extra(val values: List<List<Double>> = emptyList())

    /** ORS "waytype" codes. */
    val WAYTYPES = mapOf(
        0 to "unknown", 1 to "state_road", 2 to "road", 3 to "street", 4 to "path", 5 to "track",
        6 to "cycleway", 7 to "footway", 8 to "steps", 9 to "ferry", 10 to "construction",
    )

    fun parse(body: String, profile: RoutingProfile, providerId: String = "ors"): RouteResult {
        val resp = try {
            json.decodeFromString(Response.serializer(), body)
        } catch (e: Exception) {
            throw RoutingException.Parse(PROVIDER, e)
        }
        val feature = resp.features.firstOrNull() ?: throw RoutingException.NoRoute(PROVIDER, null)
        val coords = feature.geometry.coordinates
        if (coords.size < 2) throw RoutingException.NoRoute(PROVIDER, null)
        val geometry = RouteGeometry.of(coords.map { LatLon(it[1], it[0]) })
        val spans = feature.properties.extras["waytype"]?.values.orEmpty().mapNotNull { v ->
            if (v.size < 3) return@mapNotNull null
            val from = v[0].toInt().coerceIn(0, geometry.size - 1)
            val to = v[1].toInt().coerceIn(0, geometry.size - 1)
            RouteSpan(geometry.cumulativeM(from), geometry.cumulativeM(to), roadClass = WAYTYPES[v[2].toInt()] ?: "unknown")
        }
        val summary = feature.properties.summary
        // Provider speed per step: step distance / step duration over the step's stretch of geometry.
        val speeds = feature.properties.segments.flatMap { it.steps }.mapNotNull { st ->
            if (st.wayPoints.size < 2 || st.duration <= 0 || st.distance <= 0) return@mapNotNull null
            val from = st.wayPoints[0].coerceIn(0, geometry.size - 1)
            val to = st.wayPoints[1].coerceIn(0, geometry.size - 1)
            val a = geometry.cumulativeM(from)
            val b = geometry.cumulativeM(to)
            if (b - a < 0.5) null else SpeedSpan(a, b, st.distance / st.duration)
        }
        return RouteResult(
            geometry = geometry,
            distanceM = if (summary.distance > 0) summary.distance else geometry.lengthM,
            durationS = summary.duration.takeIf { it > 0 },
            providerId = providerId,
            profile = profile,
            spans = spans,
            providerSpeeds = speeds,
        )
    }

    /** Maps an HTTP error to a [RoutingException]. ORS bodies: {"error":{"code":2010,"message":"..."}} or {"error":"..."}. */
    fun error(httpCode: Int, body: String?): RoutingException {
        val (code, message) = parseErrorBody(body)
        return when {
            httpCode == 401 -> RoutingException.Unauthorized(PROVIDER)
            httpCode == 403 -> RoutingException.Forbidden(PROVIDER)
            httpCode == 429 -> RoutingException.RateLimited(PROVIDER)
            // 2009 = route not found, 2010 = point not found / not routable, 2004 = distance limit exceeded.
            code in setOf(2009, 2010, 2004) || httpCode == 404 -> RoutingException.NoRoute(PROVIDER, message)
            else -> RoutingException.Http(PROVIDER, httpCode, message)
        }
    }

    private fun parseErrorBody(body: String?): Pair<Int?, String?> {
        if (body.isNullOrBlank()) return null to null
        return try {
            val err: JsonElement? = json.parseToJsonElement(body).jsonObject["error"]
            when (err) {
                is JsonObject -> err["code"]?.jsonPrimitive?.intOrNull to err["message"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> null to err.contentOrNull
                else -> null to null
            }
        } catch (_: Exception) {
            null to null
        }
    }
}
