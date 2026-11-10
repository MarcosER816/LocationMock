package com.mockroute.core.streets

import com.mockroute.core.geo.LatLon
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale

/** A raw OSM way from Overpass `out geom;` (node ids aligned with geometry). */
data class OsmWay(val id: Long, val nodeIds: LongArray, val points: List<LatLon>, val tags: Map<String, String>) {
    val highway: String? get() = tags["highway"]
}

/**
 * Overpass API (public instances; fair use: < 10,000 queries and < 1 GB per day, and the app keeps itself to
 * ≤ 100 per day). ONE query per zone fetches every road class we may need, for walking AND driving, so switching
 * Walk/Car/Bus needs no new request: the mode filters are applied in [RoadGraph.build].
 */
object Overpass {
    val ENDPOINTS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter")
    const val USER_AGENT = "MockRoute-Android/0.7 (personal, non-commercial mock-location app)"
    const val HIGHWAY_REGEX =
        "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|pedestrian|footway|path|steps|track)(_link)?$"

    /** Overpass QL with a `poly:"lat lon …"` filter and `out geom;`. */
    fun query(polygon: List<LatLon>): String {
        require(polygon.size >= 3) { "polygon needs ≥ 3 vertices" }
        val poly = polygon.joinToString(" ") { String.format(Locale.US, "%.6f %.6f", it.lat, it.lon) }
        return "[out:json][timeout:90][maxsize:268435456];\n" +
            "way[\"highway\"~\"$HIGHWAY_REGEX\"](poly:\"$poly\");\n" +
            "out geom;"
    }

    /** Cache key: SHA-256 of the query text (polygon rounded to 1e-6°). */
    fun cacheKey(query: String): String =
        MessageDigest.getInstance("SHA-256").digest(query.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    @Serializable private data class Resp(val elements: List<El> = emptyList())
    @Serializable private data class El(
        val type: String,
        val id: Long,
        val nodes: List<Long> = emptyList(),
        val geometry: List<G?> = emptyList(),
        val tags: Map<String, String> = emptyMap(),
    )
    @Serializable private data class G(val lat: Double, val lon: Double)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Parses `out geom;` JSON; ways whose node and geometry arrays don't line up are skipped. */
    fun parse(body: String): List<OsmWay> {
        val r = json.decodeFromString(Resp.serializer(), body)
        return r.elements.filter { it.type == "way" && it.nodes.size >= 2 && it.nodes.size == it.geometry.size && it.geometry.all { g -> g != null } }
            .map { e -> OsmWay(e.id, e.nodes.toLongArray(), e.geometry.map { LatLon(it!!.lat, it.lon) }, e.tags) }
    }
}
