package com.mockroute.core.geo

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 position in decimal degrees. */
@Serializable
data class LatLon(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "lat out of range: $lat" }
        require(lon.isFinite()) { "lon not finite: $lon" }
    }
}

/** Local East-North-Up offset in metres relative to some origin. */
data class Enu(val east: Double, val north: Double, val up: Double = 0.0)

/** Result of walking a given distance along a polyline. */
data class PolylinePoint(
    val position: LatLon,
    /** Direction of travel at that point, degrees clockwise from true north in [0, 360). */
    val bearingDeg: Double,
    /** Index of the segment (points[i] → points[i+1]) the position lies on. */
    val segmentIndex: Int,
)

/** Spherical-earth geodesy helpers (good to ~0.5 %) plus exact WGS84 local ENU. All distances in metres. */
object GeoMath {
    /** IUGG mean Earth radius. */
    const val EARTH_RADIUS_M: Double = 6_371_008.8

    private const val WGS84_A = 6_378_137.0
    private const val WGS84_F = 1.0 / 298.257223563
    private const val WGS84_B = WGS84_A * (1 - WGS84_F)
    private const val WGS84_E2 = WGS84_F * (2 - WGS84_F)
    private const val WGS84_EP2 = WGS84_E2 / (1 - WGS84_E2)

    fun normalizeBearing(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    fun normalizeLon(deg: Double): Double {
        var r = (deg + 180.0) % 360.0
        if (r < 0) r += 360.0
        return r - 180.0
    }

    /** Great-circle distance (haversine). */
    fun haversineMeters(a: LatLon, b: LatLon): Double {
        val phi1 = Math.toRadians(a.lat)
        val phi2 = Math.toRadians(b.lat)
        val dPhi = phi2 - phi1
        val dLambda = Math.toRadians(b.lon - a.lon)
        val h = sin(dPhi / 2).let { it * it } + cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial great-circle bearing from [from] to [to], degrees in [0, 360). 0 when the points coincide. */
    fun initialBearingDeg(from: LatLon, to: LatLon): Double {
        val phi1 = Math.toRadians(from.lat)
        val phi2 = Math.toRadians(to.lat)
        val dLambda = Math.toRadians(to.lon - from.lon)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        if (y == 0.0 && x == 0.0) return 0.0
        return normalizeBearing(Math.toDegrees(atan2(y, x)))
    }

    /** Point reached travelling [distanceM] from [origin] on initial bearing [bearingDeg] along a great circle. */
    fun destination(origin: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val delta = distanceM / EARTH_RADIUS_M
        val theta = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(origin.lat)
        val lambda1 = Math.toRadians(origin.lon)
        val sinPhi2 = sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta)
        val phi2 = asin(sinPhi2.coerceIn(-1.0, 1.0))
        val lambda2 = lambda1 + atan2(sin(theta) * sin(delta) * cos(phi1), cos(delta) - sin(phi1) * sinPhi2)
        return LatLon(Math.toDegrees(phi2), normalizeLon(Math.toDegrees(lambda2)))
    }

    /** Point at [fraction] ∈ [0,1] of the way from [a] to [b] along the great circle. */
    fun intermediate(a: LatLon, b: LatLon, fraction: Double): LatLon {
        val f = fraction.coerceIn(0.0, 1.0)
        val d = haversineMeters(a, b)
        if (d < 1e-6 || f == 0.0) return a
        if (f == 1.0) return b
        return destination(a, initialBearingDeg(a, b), d * f)
    }

    /** Total length of a polyline. */
    fun polylineLengthMeters(points: List<LatLon>): Double =
        points.zipWithNext().sumOf { (a, b) -> haversineMeters(a, b) }

    /**
     * Point at [distanceM] along [points] (clamped to [0, length]). Bearing is the direction of the segment
     * the point lies on (for zero-length segments, the next non-degenerate one if any).
     */
    fun pointAtDistance(points: List<LatLon>, distanceM: Double): PolylinePoint {
        require(points.isNotEmpty()) { "empty polyline" }
        if (points.size == 1) return PolylinePoint(points[0], 0.0, 0)
        var remaining = distanceM.coerceAtLeast(0.0)
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val seg = haversineMeters(a, b)
            if (remaining <= seg || i == points.size - 2) {
                val pos = if (seg < 1e-9) a else intermediate(a, b, (remaining / seg).coerceAtMost(1.0))
                val bearing = if (seg < 1e-9) segmentBearingFrom(points, i) else initialBearingDeg(a, b)
                return PolylinePoint(pos, bearing, i)
            }
            remaining -= seg
        }
        error("unreachable")
    }

    private fun segmentBearingFrom(points: List<LatLon>, start: Int): Double {
        for (i in start until points.size - 1) {
            if (haversineMeters(points[i], points[i + 1]) >= 1e-9) return initialBearingDeg(points[i], points[i + 1])
        }
        return 0.0
    }

    // ---- WGS84 ECEF / local ENU ---------------------------------------------------------------

    private data class Ecef(val x: Double, val y: Double, val z: Double)

    private fun toEcef(p: LatLon, alt: Double): Ecef {
        val phi = Math.toRadians(p.lat)
        val lambda = Math.toRadians(p.lon)
        val n = WGS84_A / sqrt(1 - WGS84_E2 * sin(phi) * sin(phi))
        return Ecef(
            (n + alt) * cos(phi) * cos(lambda),
            (n + alt) * cos(phi) * sin(lambda),
            (n * (1 - WGS84_E2) + alt) * sin(phi),
        )
    }

    /** Returns (position, altitude) using Bowring's closed-form inverse (sub-mm near the surface). */
    private fun fromEcef(e: Ecef): Pair<LatLon, Double> {
        val p = hypot(e.x, e.y)
        val theta = atan2(e.z * WGS84_A, p * WGS84_B)
        val phi = atan2(
            e.z + WGS84_EP2 * WGS84_B * sin(theta).let { it * it * it },
            p - WGS84_E2 * WGS84_A * cos(theta).let { it * it * it },
        )
        val lambda = atan2(e.y, e.x)
        val n = WGS84_A / sqrt(1 - WGS84_E2 * sin(phi) * sin(phi))
        val alt = if (cos(phi) > 1e-9) p / cos(phi) - n else e.z / sin(phi) - n * (1 - WGS84_E2)
        return LatLon(Math.toDegrees(phi), Math.toDegrees(lambda)) to alt
    }

    /** ENU coordinates of [point] (altitude [alt]) relative to [origin] (altitude [originAlt]). */
    fun toEnu(origin: LatLon, point: LatLon, originAlt: Double = 0.0, alt: Double = 0.0): Enu {
        val o = toEcef(origin, originAlt)
        val p = toEcef(point, alt)
        val dx = p.x - o.x
        val dy = p.y - o.y
        val dz = p.z - o.z
        val phi = Math.toRadians(origin.lat)
        val lambda = Math.toRadians(origin.lon)
        val east = -sin(lambda) * dx + cos(lambda) * dy
        val north = -sin(phi) * cos(lambda) * dx - sin(phi) * sin(lambda) * dy + cos(phi) * dz
        val up = cos(phi) * cos(lambda) * dx + cos(phi) * sin(lambda) * dy + sin(phi) * dz
        return Enu(east, north, up)
    }

    /** Inverse of [toEnu]: position and altitude of the ENU offset [enu] from [origin]. */
    fun fromEnu(origin: LatLon, enu: Enu, originAlt: Double = 0.0): Pair<LatLon, Double> {
        val o = toEcef(origin, originAlt)
        val phi = Math.toRadians(origin.lat)
        val lambda = Math.toRadians(origin.lon)
        val dx = -sin(lambda) * enu.east - sin(phi) * cos(lambda) * enu.north + cos(phi) * cos(lambda) * enu.up
        val dy = cos(lambda) * enu.east - sin(phi) * sin(lambda) * enu.north + cos(phi) * sin(lambda) * enu.up
        val dz = cos(phi) * enu.north + sin(phi) * enu.up
        return fromEcef(Ecef(o.x + dx, o.y + dy, o.z + dz))
    }
}
