package com.mockroute.core.zone

import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A point / vector in local ENU metres (x = east, y = north). */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    fun dot(o: Vec2) = x * o.x + y * o.y
    fun cross(o: Vec2) = x * o.y - y * o.x
    fun length() = hypot(x, y)
    fun normalized(): Vec2 = length().let { if (it < 1e-12) Vec2(0.0, 0.0) else Vec2(x / it, y / it) }
    fun distanceTo(o: Vec2) = hypot(x - o.x, y - o.y)
    /** Left perpendicular (rotate +90°). */
    fun perp() = Vec2(-y, x)
}

/**
 * Local tangent plane around [origin]: equirectangular metres (exact inverse, error ≪ 1 mm over a building).
 * Zones live in this frame; LatLon only at the edges (UI and the mock provider).
 */
class LocalFrame(val origin: LatLon) {
    private val kx = GeoMath.EARTH_RADIUS_M * Math.toRadians(1.0) * cos(Math.toRadians(origin.lat))
    private val ky = GeoMath.EARTH_RADIUS_M * Math.toRadians(1.0)

    fun toLocal(p: LatLon) = Vec2((p.lon - origin.lon) * kx, (p.lat - origin.lat) * ky)
    fun toLatLon(v: Vec2) = LatLon(origin.lat + v.y / ky, origin.lon + v.x / kx)
}

/** Result of projecting a point onto the polygon boundary. */
data class BoundaryPoint(val point: Vec2, val edge: Int, val t: Double, val distance: Double)

/**
 * Simple polygon (counter-clockwise after construction) in local metres.
 * Use [validate] first: self-intersecting or degenerate polygons are rejected.
 */
class Polygon2(vertices: List<Vec2>) {
    /** Vertices in CCW order, no repeated closing vertex. */
    val vertices: List<Vec2>

    init {
        val v = clean(vertices)
        require(v.size >= 3) { "polygon needs ≥ 3 vertices" }
        this.vertices = if (signedArea(v) < 0) v.reversed() else v
    }

    val size: Int get() = vertices.size
    val area: Double = abs(signedArea(this.vertices))

    val centroid: Vec2 = run {
        var cx = 0.0
        var cy = 0.0
        var a2 = 0.0
        for (i in vertices.indices) {
            val p = vertices[i]
            val q = vertices[(i + 1) % vertices.size]
            val c = p.cross(q)
            a2 += c
            cx += (p.x + q.x) * c
            cy += (p.y + q.y) * c
        }
        if (abs(a2) < 1e-12) vertices[0] else Vec2(cx / (3 * a2), cy / (3 * a2))
    }

    val minX = vertices.minOf { it.x }
    val maxX = vertices.maxOf { it.x }
    val minY = vertices.minOf { it.y }
    val maxY = vertices.maxOf { it.y }

    fun edge(i: Int): Pair<Vec2, Vec2> = vertices[i] to vertices[(i + 1) % size]

    /** Inside or within [eps] metres of the boundary. */
    fun contains(p: Vec2, eps: Double = 1e-6): Boolean {
        if (distanceToBoundary(p) <= eps) return true
        var inside = false
        var j = size - 1
        for (i in 0 until size) {
            val a = vertices[i]
            val b = vertices[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** Strictly inside with at least [margin] metres to every wall. */
    fun containsWithMargin(p: Vec2, margin: Double): Boolean = contains(p, 0.0) && distanceToBoundary(p) >= margin

    fun nearestBoundaryPoint(p: Vec2): BoundaryPoint {
        var best = BoundaryPoint(vertices[0], 0, 0.0, Double.POSITIVE_INFINITY)
        for (i in 0 until size) {
            val (a, b) = edge(i)
            val ab = b - a
            val len2 = ab.dot(ab)
            val t = if (len2 == 0.0) 0.0 else ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
            val q = a + ab * t
            val d = q.distanceTo(p)
            if (d < best.distance) best = BoundaryPoint(q, i, t, d)
        }
        return best
    }

    fun distanceToBoundary(p: Vec2): Double = nearestBoundaryPoint(p).distance

    /**
     * True if the whole segment a→b lies inside (boundary counts as inside): no proper crossing of any edge, and
     * points along it are inside. Endpoints may lie on the boundary (A/B, touching).
     */
    fun segmentInside(a: Vec2, b: Vec2, eps: Double = 1e-6): Boolean {
        if (!contains(a, eps) || !contains(b, eps)) return false
        for (i in 0 until size) {
            val (p, q) = edge(i)
            if (properlyIntersect(a, b, p, q)) return false
        }
        val len = a.distanceTo(b)
        val samples = max(8, min(256, (len / 0.5).toInt()))
        for (k in 1 until samples) {
            if (!contains(a + (b - a) * (k.toDouble() / samples), eps)) return false
        }
        return true
    }

    /** Vertex [i] is reflex (interior angle > 180°) for a CCW polygon. */
    fun isReflex(i: Int): Boolean {
        val prev = vertices[(i - 1 + size) % size]
        val cur = vertices[i]
        val next = vertices[(i + 1) % size]
        return (cur - prev).cross(next - cur) < 0
    }

    /** A point [inset] metres inside the polygon from vertex [i] along the inward bisector, if that lands inside. */
    fun insetVertex(i: Int, inset: Double): Vec2? {
        val prev = vertices[(i - 1 + size) % size]
        val cur = vertices[i]
        val next = vertices[(i + 1) % size]
        val n1 = (cur - prev).normalized().perp() // inward normal for CCW
        val n2 = (next - cur).normalized().perp()
        var dir = (n1 + n2).normalized()
        if (dir.length() < 1e-9) dir = n1
        // Distance along the bisector giving [inset] from both edges.
        val cosHalf = dir.dot(n1).coerceAtLeast(0.2)
        val p = cur + dir * (inset / cosHalf)
        return if (containsWithMargin(p, inset * 0.5)) p else null
    }

    companion object {
        fun signedArea(v: List<Vec2>): Double {
            var s = 0.0
            for (i in v.indices) s += v[i].cross(v[(i + 1) % v.size])
            return s / 2
        }

        enum class Problem { TooFewVertices, TooSmall, SelfIntersecting }

        /**
         * Drops consecutive near-duplicate vertices (< [minSpacing] m, e.g. a double tap) and a closing vertex equal to
         * the first. Without this, a duplicated corner makes its two neighbouring edges "touch" and the outline is
         * wrongly reported as self-intersecting (phone report 2026-11-10: "it doesn't let me close the shape").
         */
        fun clean(vertices: List<Vec2>, minSpacing: Double = 0.05): List<Vec2> {
            val out = ArrayList<Vec2>(vertices.size)
            for (p in vertices) if (out.isEmpty() || out.last().distanceTo(p) >= minSpacing) out += p
            while (out.size >= 2 && out.first().distanceTo(out.last()) < minSpacing) out.removeAt(out.size - 1)
            return out
        }

        /** Null if usable; otherwise why not. Rejects self-intersecting and tiny (< 4 m²) polygons. */
        fun validate(vertices: List<Vec2>): Problem? {
            val v = clean(vertices)
            if (v.size < 3) return Problem.TooFewVertices
            val n = v.size
            for (i in 0 until n) {
                val a1 = v[i]
                val a2 = v[(i + 1) % n]
                for (j in i + 1 until n) {
                    if (j == i || (j + 1) % n == i || (i + 1) % n == j) continue // adjacent edges share a vertex
                    val b1 = v[j]
                    val b2 = v[(j + 1) % n]
                    if (segmentsIntersect(a1, a2, b1, b2)) return Problem.SelfIntersecting
                }
            }
            if (abs(signedArea(v)) < 4.0) return Problem.TooSmall
            return null
        }

        private fun orient(a: Vec2, b: Vec2, c: Vec2): Double = (b - a).cross(c - a)

        /** Crossing in the interior of both segments (touching at an endpoint does not count). */
        fun properlyIntersect(a: Vec2, b: Vec2, c: Vec2, d: Vec2, eps: Double = 1e-9): Boolean {
            val o1 = orient(a, b, c)
            val o2 = orient(a, b, d)
            val o3 = orient(c, d, a)
            val o4 = orient(c, d, b)
            return ((o1 > eps && o2 < -eps) || (o1 < -eps && o2 > eps)) &&
                ((o3 > eps && o4 < -eps) || (o3 < -eps && o4 > eps))
        }

        /** Any contact, including touching and collinear overlap. */
        fun segmentsIntersect(a: Vec2, b: Vec2, c: Vec2, d: Vec2, eps: Double = 1e-9): Boolean {
            if (properlyIntersect(a, b, c, d, eps)) return true
            fun onSeg(p: Vec2, q: Vec2, r: Vec2) = abs(orient(p, q, r)) <= eps &&
                r.x >= min(p.x, q.x) - eps && r.x <= max(p.x, q.x) + eps &&
                r.y >= min(p.y, q.y) - eps && r.y <= max(p.y, q.y) + eps
            return onSeg(a, b, c) || onSeg(a, b, d) || onSeg(c, d, a) || onSeg(c, d, b)
        }
    }
}

/**
 * Shortest inside path between two points: straight if visible, else Dijkstra over a visibility graph of the
 * reflex vertices inset by [inset] m (shortest paths in a simple polygon only bend at reflex vertices; the inset
 * keeps the walker off the walls). The vertex–vertex visibility is precomputed once per polygon.
 */
class InsidePathFinder(val polygon: Polygon2, val inset: Double = 0.3) {
    private val nodes: List<Vec2> = (0 until polygon.size).filter { polygon.isReflex(it) }.mapNotNull { polygon.insetVertex(it, inset) }
    private val adjacency: Array<DoubleArray> = Array(nodes.size) { i ->
        DoubleArray(nodes.size) { j ->
            if (i != j && polygon.segmentInside(nodes[i], nodes[j])) nodes[i].distanceTo(nodes[j]) else Double.POSITIVE_INFINITY
        }
    }

    fun path(a: Vec2, b: Vec2): List<Vec2> {
        if (polygon.segmentInside(a, b)) return listOf(a, b)
        val n = nodes.size
        // Graph: 0..n-1 vertex nodes, n = a, n+1 = b.
        val fromA = DoubleArray(n) { if (polygon.segmentInside(a, nodes[it])) a.distanceTo(nodes[it]) else Double.POSITIVE_INFINITY }
        val toB = DoubleArray(n) { if (polygon.segmentInside(nodes[it], b)) nodes[it].distanceTo(b) else Double.POSITIVE_INFINITY }
        val dist = DoubleArray(n) { fromA[it] }
        val prev = IntArray(n) { -1 }
        val done = BooleanArray(n)
        repeat(n) {
            var u = -1
            for (i in 0 until n) if (!done[i] && (u < 0 || dist[i] < dist[u])) u = i
            if (u < 0 || dist[u].isInfinite()) return@repeat
            done[u] = true
            for (w in 0 until n) {
                val alt = dist[u] + adjacency[u][w]
                if (alt < dist[w]) {
                    dist[w] = alt
                    prev[w] = u
                }
            }
        }
        var best = -1
        var bestLen = Double.POSITIVE_INFINITY
        for (i in 0 until n) if (dist[i] + toB[i] < bestLen) {
            bestLen = dist[i] + toB[i]
            best = i
        }
        if (best < 0) return listOf(a, b) // shouldn't happen in a valid simple polygon
        val chain = ArrayList<Vec2>()
        var c = best
        while (c >= 0) {
            chain += nodes[c]
            c = prev[c]
        }
        return listOf(a) + chain.reversed() + listOf(b)
    }

    companion object {
        fun length(path: List<Vec2>): Double = path.zipWithNext().sumOf { (p, q) -> p.distanceTo(q) }
    }
}
