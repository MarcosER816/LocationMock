package com.mockroute.core.traffic

import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.RouteGeometry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * TomTom Routing API v1 calculateRoute (docs.tomtom.com/routing-api, checked 2026-11-10).
 *
 * POST https://api.tomtom.com/routing/1/calculateRoute/{A}:{B}/json?key=…&departAt=<RFC 3339, future>
 *   &traffic=true&sectionType=traffic&computeTravelTimeFor=all&travelMode=car&instructionsType=coded
 * Body {"supportingPoints":[{"latitude":…,"longitude":…},…]} so TomTom reconstructs OUR traced path.
 * For a future departAt TomTom uses historic speed profiles (live traffic does not apply).
 * Free tier: 2,500 non-tile requests/day shared across TomTom APIs.
 */
object TomTomCodec {
    const val PROVIDER = "TomTom"
    const val BASE_URL = "https://api.tomtom.com/routing/1/calculateRoute"
    /** Supporting points sent (route simplified to fit); TomTom documents no max, the body limit is 10 MB. */
    const val MAX_SUPPORTING_POINTS = 1500

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Waypoint circles for [PathMatch.Corridor]: every ~1.5 km, ≤ 148 (TomTom allows 150 waypoints). */
    const val CORRIDOR_SPACING_M = 1500.0
    const val CORRIDOR_RADIUS_M = 150

    /**
     * Request URL for [match]:
     * - Exact: A:B + POST body of supportingPoints, `reconstructionMode=track` (tolerant: our geometry comes from
     *   OpenRouteService/OSM, so points can sit slightly off TomTom's road network; strict reconstruction then
     *   fails with CANNOT_RESTORE_BASEROUTE).
     * - Corridor: A:circle(…):…:B, GET, circles don't add legs.
     * - Direct: A:B, GET.
     */
    fun url(route: RouteGeometry, departureEpochS: Long, zone: ZoneId, mode: TrafficMode, key: String, match: PathMatch = PathMatch.Exact): String {
        val a = route.start!!
        val b = route.end!!
        val depart = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochSecond(departureEpochS).atZone(zone))
        // Bus: TomTom's travelMode=bus is BETA with limited data; we query car speeds and apply bus rules ourselves.
        val travelMode = when (mode) {
            TrafficMode.Car, TrafficMode.Bus -> "car"
        }
        val locations = when (match) {
            PathMatch.Corridor -> buildString {
                append(ll(a))
                val n = (route.lengthM / CORRIDOR_SPACING_M).toInt().coerceIn(0, 148)
                for (i in 1..n) {
                    val p = route.pointAtDistance(route.lengthM * i / (n + 1)).position
                    append(":circle(").append(ll(p)).append(',').append(CORRIDOR_RADIUS_M).append(')')
                }
                append(':').append(ll(b))
            }
            else -> "${ll(a)}:${ll(b)}"
        }
        val reconstruction = if (match == PathMatch.Exact) "&reconstructionMode=track" else ""
        return "$BASE_URL/$locations/json?key=${enc(key)}&departAt=${enc(depart)}&traffic=true" +
            "&sectionType=traffic&computeTravelTimeFor=all&travelMode=$travelMode&instructionsType=coded" +
            "&routeType=fastest$reconstruction"
    }

    fun body(route: RouteGeometry): String {
        var g = route
        var tol = 2.0
        while (g.size > MAX_SUPPORTING_POINTS) {
            g = route.simplify(tol)
            tol *= 2
        }
        return buildString(g.size * 48) {
            append("""{"supportingPoints":[""")
            g.points.forEachIndexed { i, p ->
                if (i > 0) append(',')
                append("""{"latitude":""").append(p.lat).append(""","longitude":""").append(p.lon).append('}')
            }
            append("]}")
        }
    }

    private fun ll(p: LatLon) = String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon)
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    // ---- response ------------------------------------------------------------------------------

    @Serializable data class Response(val routes: List<Route> = emptyList())

    @Serializable
    data class Route(
        val summary: Summary = Summary(),
        val legs: List<Leg> = emptyList(),
        val sections: List<Section> = emptyList(),
        val guidance: Guidance? = null,
    )

    @Serializable
    data class Summary(
        val lengthInMeters: Double = 0.0,
        val travelTimeInSeconds: Double = 0.0,
        val trafficDelayInSeconds: Double = 0.0,
        val noTrafficTravelTimeInSeconds: Double? = null,
        val historicTrafficTravelTimeInSeconds: Double? = null,
        val liveTrafficIncidentsTravelTimeInSeconds: Double? = null,
    )

    @Serializable data class Leg(val points: List<Point> = emptyList())
    @Serializable data class Point(val latitude: Double, val longitude: Double)

    @Serializable
    data class Section(
        val startPointIndex: Int = 0,
        val endPointIndex: Int = 0,
        val sectionType: String = "",
        val simpleCategory: String? = null,
        val effectiveSpeedInKmh: Double? = null,
        val delayInSeconds: Double? = null,
        val magnitudeOfDelay: Int? = null,
    )

    @Serializable data class Guidance(val instructions: List<Instruction> = emptyList())
    @Serializable data class Instruction(val routeOffsetInMeters: Double = 0.0, val travelTimeInSeconds: Double = 0.0)

    /**
     * Parses a calculateRoute answer into a profile along [ours]. Distances along TomTom's polyline are rescaled
     * to our geometry length. The time profile comes from guidance instructions (cumulative offset/time at every
     * manoeuvre). Without guidance it is rebuilt from the traffic sections (their effective speeds) with the
     * remaining time spread evenly over the rest.
     */
    fun parse(body: String, ours: RouteGeometry, departureEpochS: Long, match: PathMatch = PathMatch.Exact): SpeedProfileSample {
        val resp = try {
            json.decodeFromString(Response.serializer(), body)
        } catch (e: Exception) {
            throw TrafficException.Parse(PROVIDER, e)
        }
        val route = resp.routes.firstOrNull() ?: throw TrafficException.NoRoute(PROVIDER, null)
        val pts = route.legs.flatMap { it.points }.map { LatLon(it.latitude, it.longitude) }
        val sum = route.summary
        val ttLen = if (sum.lengthInMeters > 0) sum.lengthInMeters else RouteGeometry.of(pts).lengthM
        if (ttLen <= 0 || sum.travelTimeInSeconds <= 0) throw TrafficException.NoRoute(PROVIDER, "empty route")
        val scale = ours.lengthM / ttLen

        // Cumulative distance of TomTom's points (to map section point indices to metres).
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + GeoMath.haversineMeters(pts[i - 1], pts[i])
        val cumScale = if (pts.size > 1 && cum.last() > 0) ttLen / cum.last() else 1.0
        fun offsetOf(idx: Int): Double = if (pts.isEmpty()) 0.0 else cum[idx.coerceIn(0, pts.size - 1)] * cumScale * scale

        val sections = route.sections.filter { it.sectionType.equals("TRAFFIC", ignoreCase = true) }.map {
            TrafficSection(
                startM = offsetOf(it.startPointIndex),
                endM = offsetOf(it.endPointIndex),
                effectiveSpeedKmh = it.effectiveSpeedInKmh,
                delayS = it.delayInSeconds ?: 0.0,
                magnitude = it.magnitudeOfDelay ?: 0,
                category = it.simpleCategory,
            )
        }

        val (offsets, times) = profileFromGuidance(route, scale, sum.travelTimeInSeconds, ours.lengthM)
            ?: profileFromSections(sections, sum.travelTimeInSeconds, ours.lengthM)

        return SpeedProfileSample(
            departureEpochS = departureEpochS,
            lengthM = ours.lengthM,
            travelTimeS = sum.travelTimeInSeconds,
            noTrafficTimeS = sum.noTrafficTravelTimeInSeconds,
            historicTimeS = sum.historicTrafficTravelTimeInSeconds,
            liveTimeS = sum.liveTrafficIncidentsTravelTimeInSeconds,
            offsetsM = offsets,
            timesS = times,
            sections = sections,
            provider = PROVIDER,
            match = match,
        )
    }

    private fun profileFromGuidance(route: Route, scale: Double, total: Double, len: Double): Pair<List<Double>, List<Double>>? {
        val ins = route.guidance?.instructions.orEmpty().sortedBy { it.routeOffsetInMeters }
        if (ins.size < 2) return null
        val offs = ArrayList<Double>(ins.size + 2)
        val times = ArrayList<Double>(ins.size + 2)
        offs += 0.0
        times += 0.0
        for (i in ins) {
            val o = (i.routeOffsetInMeters * scale).coerceIn(0.0, len)
            val t = i.travelTimeInSeconds.coerceIn(0.0, total)
            if (o > offs.last() && t >= times.last()) {
                offs += o
                times += t
            }
        }
        if (offs.last() < len) {
            offs += len
            times += total
        } else {
            times[times.size - 1] = total
        }
        return if (offs.size >= 2) offs to times else null
    }

    /** Congested sections at their effective speed; the remaining time spread uniformly over the rest. */
    internal fun profileFromSections(sections: List<TrafficSection>, total: Double, len: Double): Pair<List<Double>, List<Double>> {
        val jam = sections.filter { it.endM > it.startM && (it.effectiveSpeedKmh ?: 0.0) > 0 }.sortedBy { it.startM }
        val jamTime = jam.sumOf { (it.endM - it.startM) / (it.effectiveSpeedKmh!! / 3.6) }
        val jamLen = jam.sumOf { it.endM - it.startM }
        val restLen = (len - jamLen).coerceAtLeast(1e-6)
        val restPace = ((total - jamTime).coerceAtLeast(total * 0.05)) / restLen // s per m
        val offs = mutableListOf(0.0)
        val times = mutableListOf(0.0)
        var s = 0.0
        var t = 0.0
        for (j in jam) {
            if (j.startM > s) {
                t += (j.startM - s) * restPace
                s = j.startM
                offs += s
                times += t
            }
            t += (j.endM - j.startM) / (j.effectiveSpeedKmh!! / 3.6)
            s = j.endM
            offs += s
            times += t
        }
        if (s < len) {
            t += (len - s) * restPace
            offs += len
            times += t
        }
        // Normalise to the reported total.
        val k = if (t > 0) total / t else 1.0
        return offs to times.map { it * k }
    }

    /** TomTom error bodies: {"detailedError":{"code":"…","message":"…"}}; 403 may be XML. */
    fun error(httpCode: Int, body: String?): TrafficException {
        val err = runCatching { json.parseToJsonElement(body ?: "").jsonObject["detailedError"]?.jsonObject }.getOrNull()
        val code = err?.get("code")?.jsonPrimitive?.contentOrNull
        val msg = err?.get("message")?.jsonPrimitive?.contentOrNull
        return when {
            httpCode == 401 -> TrafficException.Unauthorized(PROVIDER)
            httpCode == 403 && body?.contains("over", true) == true -> TrafficException.QuotaExceeded(PROVIDER)
            httpCode == 403 -> TrafficException.Unauthorized(PROVIDER)
            httpCode == 429 -> TrafficException.QuotaExceeded(PROVIDER)
            // TomTom's message even spells it "CANOT_RESTORE_BASEROUTE"; match the code and the text.
            code in setOf("MAP_MATCHING_FAILURE", "CANNOT_RESTORE_BASEROUTE") ||
                msg?.contains("RESTORE_BASEROUTE", true) == true || msg?.contains("MAP_MATCHING", true) == true ->
                TrafficException.RestoreFailed(PROVIDER, msg)
            code == "NO_ROUTE_FOUND" -> TrafficException.NoRoute(PROVIDER, msg)
            else -> TrafficException.Http(PROVIDER, httpCode, msg ?: code)
        }
    }
}
