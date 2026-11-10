package com.mockroute.core.route

import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import com.mockroute.core.geo.PolylinePoint
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Immutable polyline with precomputed cumulative distances (metres, haversine).
 * Stage 3+ consume it via [lengthM], [cumulativeM], [pointAtDistance] and [densify].
 */
@Serializable(with = RouteGeometrySerializer::class)
class RouteGeometry private constructor(
    val points: List<LatLon>,
    private val cumulative: DoubleArray,
) {
    /** Total length in metres. */
    val lengthM: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative.last()

    val size: Int get() = points.size
    val isEmpty: Boolean get() = points.isEmpty()
    val start: LatLon? get() = points.firstOrNull()
    val end: LatLon? get() = points.lastOrNull()

    /** Distance from the start to point i. cumulativeM(0) == 0. */
    fun cumulativeM(i: Int): Double = cumulative[i]

    /** Copy of all cumulative distances. */
    fun cumulativeDistances(): DoubleArray = cumulative.copyOf()

    /** Point at [distanceM] along the route (clamped), O(log n). */
    fun pointAtDistance(distanceM: Double): PolylinePoint {
        require(points.isNotEmpty()) { "empty geometry" }
        if (points.size == 1) return PolylinePoint(points[0], 0.0, 0)
        val d = distanceM.coerceIn(0.0, lengthM)
        // Largest i with cumulative[i] <= d, limited to the last segment start.
        var lo = 0
        var hi = points.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulative[mid] <= d) lo = mid else hi = mid - 1
        }
        var i = lo
        // Skip zero-length segments so the bearing is meaningful.
        while (i < points.size - 2 && cumulative[i + 1] - cumulative[i] < 1e-9) i++
        val a = points[i]
        val b = points[i + 1]
        val seg = cumulative[i + 1] - cumulative[i]
        val pos = if (seg < 1e-9) a else GeoMath.intermediate(a, b, ((d - cumulative[i]) / seg).coerceIn(0.0, 1.0))
        val bearing = if (seg < 1e-9) 0.0 else GeoMath.initialBearingDeg(a, b)
        return PolylinePoint(pos, bearing, i)
    }

    /** Inserts great-circle points so that no two consecutive points are more than [maxSpacingM] apart. */
    fun densify(maxSpacingM: Double = 10.0): RouteGeometry {
        require(maxSpacingM > 0) { "spacing must be > 0" }
        if (points.size < 2) return this
        val out = ArrayList<LatLon>(points.size + (lengthM / maxSpacingM).toInt() + 1)
        out += points[0]
        for (i in 0 until points.size - 1) {
            val seg = cumulative[i + 1] - cumulative[i]
            val pieces = ceil(seg / maxSpacingM).toInt().coerceAtLeast(1)
            for (k in 1 until pieces) out += GeoMath.intermediate(points[i], points[i + 1], k.toDouble() / pieces)
            out += points[i + 1]
        }
        return of(out)
    }

    /** Douglas–Peucker simplification for display; keeps first and last points. */
    fun simplify(toleranceM: Double): RouteGeometry {
        if (points.size < 3 || toleranceM <= 0) return this
        val origin = points[0]
        val kx = GeoMath.EARTH_RADIUS_M * Math.toRadians(1.0) * cos(Math.toRadians(origin.lat))
        val ky = GeoMath.EARTH_RADIUS_M * Math.toRadians(1.0)
        val xs = DoubleArray(points.size) { (points[it].lon - origin.lon) * kx }
        val ys = DoubleArray(points.size) { (points[it].lat - origin.lat) * ky }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast().let { it[0] to it[1] }
            var maxD = -1.0
            var idx = -1
            for (i in s + 1 until e) {
                val dist = pointSegmentDistance(xs[i], ys[i], xs[s], ys[s], xs[e], ys[e])
                if (dist > maxD) {
                    maxD = dist
                    idx = i
                }
            }
            if (idx >= 0 && maxD > toleranceM) {
                keep[idx] = true
                stack.addLast(intArrayOf(s, idx))
                stack.addLast(intArrayOf(idx, e))
            }
        }
        return of(points.filterIndexed { i, _ -> keep[i] })
    }

    /**
     * Nearest point on the route to [p]: returns (distance along the route, distance from the route) in metres.
     * Local equirectangular projection per segment; O(n).
     */
    fun project(p: LatLon): Pair<Double, Double> {
        require(points.isNotEmpty()) { "empty geometry" }
        if (points.size == 1) return 0.0 to GeoMath.haversineMeters(p, points[0])
        val kx = GeoMath.EARTH_RADIUS_M * Math.toRadians(1.0) * cos(Math.toRadians(p.lat))
        val ky = GeoMath.EARTH_RADIUS_M * Math.toRadians(1.0)
        var bestAlong = 0.0
        var bestDist = Double.POSITIVE_INFINITY
        for (i in 0 until points.size - 1) {
            val ax = (points[i].lon - p.lon) * kx
            val ay = (points[i].lat - p.lat) * ky
            val bx = (points[i + 1].lon - p.lon) * kx
            val by = (points[i + 1].lat - p.lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val dist = sqrt(cx * cx + cy * cy)
            if (dist < bestDist) {
                bestDist = dist
                bestAlong = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
            }
        }
        return bestAlong to bestDist
    }

    /** Joins two routes; a duplicate joint point (< 0.5 m apart) is dropped. */
    operator fun plus(other: RouteGeometry): RouteGeometry {
        if (isEmpty) return other
        if (other.isEmpty) return this
        val tail = if (GeoMath.haversineMeters(points.last(), other.points.first()) < 0.5) other.points.drop(1) else other.points
        return of(points + tail)
    }

    override fun equals(other: Any?): Boolean = other is RouteGeometry && other.points == points
    override fun hashCode(): Int = points.hashCode()
    override fun toString(): String = "RouteGeometry(${points.size} pts, ${"%.1f".format(lengthM)} m)"

    companion object {
        val EMPTY = RouteGeometry(emptyList(), DoubleArray(0))

        fun of(points: List<LatLon>): RouteGeometry {
            if (points.isEmpty()) return EMPTY
            val cum = DoubleArray(points.size)
            for (i in 1 until points.size) cum[i] = cum[i - 1] + GeoMath.haversineMeters(points[i - 1], points[i])
            return RouteGeometry(points.toList(), cum)
        }

        fun of(vararg points: LatLon): RouteGeometry = of(points.toList())

        private fun pointSegmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx - px
            val cy = ay + t * dy - py
            return sqrt(cx * cx + cy * cy)
        }
    }
}

object RouteGeometrySerializer : KSerializer<RouteGeometry> {
    private val delegate = ListSerializer(LatLon.serializer())
    override val descriptor: SerialDescriptor = SerialDescriptor("RouteGeometry", delegate.descriptor)
    override fun serialize(encoder: Encoder, value: RouteGeometry) = delegate.serialize(encoder, value.points)
    override fun deserialize(decoder: Decoder): RouteGeometry = RouteGeometry.of(delegate.deserialize(decoder))
}
