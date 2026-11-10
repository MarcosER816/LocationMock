package com.mockroute.core.streets

import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.zone.LocalFrame
import com.mockroute.core.zone.Polygon2
import com.mockroute.core.zone.Vec2
import java.util.PriorityQueue

/** A directed edge: polyline [points] (local m) from node [from] to node [to]. */
class RoadEdge(
    val from: Int,
    val to: Int,
    val points: List<Vec2>,
    val highway: String,
    /** km/h if tagged. */
    val maxSpeedKmh: Double?,
    val wayId: Long,
    /** True if this direction follows the OSM way's node order. */
    val forward: Boolean,
    /** Index of the opposite-direction edge (two-way roads), or −1. */
    var twin: Int = -1,
) {
    val lengthM: Double = points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
}

/**
 * Directed road graph inside a polygon, in local metres. Ways are split at shared nodes (junctions); runs that
 * leave the polygon are dropped; only the largest strongly connected component is kept (always possible to reach
 * B and to get back). Mutable only through [insertNode] (snapping A/B onto an edge).
 */
class RoadGraph private constructor(
    val nodes: MutableList<Vec2>,
    val edges: MutableList<RoadEdge>,
) {
    /** Outgoing edges per node. */
    val out: MutableList<MutableList<Int>> = MutableList(nodes.size) { mutableListOf() }
    /** Incoming edges per node. */
    val inc: MutableList<MutableList<Int>> = MutableList(nodes.size) { mutableListOf() }

    init {
        edges.forEachIndexed { i, e ->
            out[e.from] += i
            inc[e.to] += i
        }
    }

    /** Independent copy (planning inserts A/B nodes). */
    fun copy(): RoadGraph = RoadGraph(
        nodes.toMutableList(),
        edges.map { RoadEdge(it.from, it.to, it.points, it.highway, it.maxSpeedKmh, it.wayId, it.forward, it.twin) }.toMutableList(),
    )

    val nodeCount: Int get() = nodes.size
    val edgeCount: Int get() = edges.size
    val totalLengthM: Double get() = edges.sumOf { it.lengthM } // directed length

    data class Snap(val edge: Int, val point: Vec2, val distance: Double, val alongM: Double)

    /** Nearest point on any edge. */
    fun snap(p: Vec2): Snap {
        var best = Snap(0, nodes[0], Double.POSITIVE_INFINITY, 0.0)
        for ((ei, e) in edges.withIndex()) {
            var along = 0.0
            for ((a, b) in e.points.zipWithNext()) {
                val ab = b - a
                val len2 = ab.dot(ab)
                val t = if (len2 == 0.0) 0.0 else ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
                val q = a + ab * t
                val d = q.distanceTo(p)
                if (d < best.distance) best = Snap(ei, q, d, along + t * kotlin.math.sqrt(len2))
                along += kotlin.math.sqrt(len2)
            }
        }
        return best
    }

    /** Distance from [p] to the nearest edge (for tests: every path point must be on the graph). */
    fun distanceToGraph(p: Vec2): Double = snap(p).distance

    /**
     * Snaps [p] onto the nearest edge and returns a node there: an existing end node if within 0.5 m, otherwise a new
     * node splitting that edge (and its twin) in two.
     */
    fun insertNode(p: Vec2): Int {
        val s = snap(p)
        val e = edges[s.edge]
        if (s.alongM < 0.5) return e.from
        if (e.lengthM - s.alongM < 0.5) return e.to
        val newNode = nodes.size
        nodes.add(s.point)
        out.add(mutableListOf())
        inc.add(mutableListOf())
        val twinIdx = e.twin
        split(s.edge, s.alongM, newNode)
        if (twinIdx >= 0) split(twinIdx, edges[twinIdx].lengthM - s.alongM, newNode)
        // Re-link twins of the four halves.
        relinkTwins(newNode)
        return newNode
    }

    private fun split(ei: Int, at: Double, newNode: Int) {
        val e = edges[ei]
        val (first, second) = cut(e.points, at)
        val a = RoadEdge(e.from, newNode, first, e.highway, e.maxSpeedKmh, e.wayId, e.forward)
        val b = RoadEdge(newNode, e.to, second, e.highway, e.maxSpeedKmh, e.wayId, e.forward)
        // Replace e by a (same index), append b.
        out[e.from].remove(ei)
        inc[e.to].remove(ei)
        edges[ei] = a
        out[a.from] += ei
        inc[a.to] += ei
        val bi = edges.size
        edges += b
        out[b.from] += bi
        inc[b.to] += bi
    }

    private fun relinkTwins(n: Int) {
        val touching = (out[n] + inc[n]).distinct()
        for (i in touching) for (j in touching) {
            if (i != j && edges[i].from == edges[j].to && edges[i].to == edges[j].from && edges[i].wayId == edges[j].wayId) {
                edges[i].twin = j
            }
        }
    }

    /** Dijkstra from [source] (forward) or to [source] (reverse). Returns distances and predecessor edges. */
    fun dijkstra(source: Int, reverse: Boolean = false): Pair<DoubleArray, IntArray> {
        val dist = DoubleArray(nodes.size) { Double.POSITIVE_INFINITY }
        val prev = IntArray(nodes.size) { -1 }
        dist[source] = 0.0
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        pq += 0.0 to source
        while (pq.isNotEmpty()) {
            val (d, u) = pq.poll()
            if (d > dist[u]) continue
            for (ei in if (reverse) inc[u] else out[u]) {
                val e = edges[ei]
                val v = if (reverse) e.from else e.to
                val nd = d + e.lengthM
                if (nd < dist[v]) {
                    dist[v] = nd
                    prev[v] = ei
                    pq += nd to v
                }
            }
        }
        return dist to prev
    }

    /** Edge sequence of the shortest path a→b (empty if a == b or unreachable). */
    fun shortestPath(a: Int, b: Int, prevFromA: IntArray? = null): List<Int> {
        if (a == b) return emptyList()
        val prev = prevFromA ?: dijkstra(a).second
        val path = ArrayList<Int>()
        var cur = b
        while (cur != a) {
            val ei = prev[cur]
            if (ei < 0) return emptyList()
            path += ei
            cur = edges[ei].from
        }
        return path.reversed()
    }

    companion object {
        private val WALK = setOf("primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service", "pedestrian", "footway", "path", "steps", "track", "primary_link", "secondary_link", "tertiary_link")
        private val CAR = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link")
        private val NO = setOf("no", "private")
        private val YES = setOf("yes", "designated", "permissive")

        /** Mode filter (access tags). */
        fun usable(w: OsmWay, profile: RoutingProfile): Boolean {
            val hw = w.highway ?: return false
            val access = w.tags["access"]
            return when (profile) {
                RoutingProfile.Walk -> hw in WALK && w.tags["foot"] != "no" && (access !in NO || w.tags["foot"] in YES)
                RoutingProfile.Car, RoutingProfile.Bus -> hw in CAR && access !in NO && w.tags["motor_vehicle"] !in NO && w.tags["motorcar"] != "no" &&
                    !(hw == "service" && w.tags["service"] in setOf("parking_aisle", "driveway") && profile == RoutingProfile.Bus)
            }
        }

        /** +1 = only along node order, −1 = only against, 0 = both. Walking ignores oneway. */
        fun direction(w: OsmWay, profile: RoutingProfile): Int {
            if (profile == RoutingProfile.Walk) return 0
            val ow = w.tags["oneway"]
            return when {
                ow in setOf("yes", "true", "1") -> 1
                ow in setOf("-1", "reverse") -> -1
                ow == "no" -> 0
                w.tags["junction"] in setOf("roundabout", "circular") -> 1
                w.highway == "motorway" || w.highway == "motorway_link" -> 1
                else -> 0
            }
        }

        /** "50" → 50, "30 mph" → 48.3, "none"/"signals"/zone codes → null. */
        fun parseMaxSpeed(s: String?): Double? {
            if (s == null) return null
            val t = s.trim().lowercase()
            val num = t.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
            return if (t.endsWith("mph")) num * 1.609344 else num
        }

        /**
         * Builds the graph for [profile] from raw ways clipped to [polygon] (local frame [frame]). A way is cut wherever
         * a node lies outside the polygon or a segment leaves it; it is split at nodes shared with other usable ways.
         */
        fun build(ways: List<OsmWay>, polygon: Polygon2, frame: LocalFrame, profile: RoutingProfile): RoadGraph {
            val usable = ways.filter { usable(it, profile) }
            val local = HashMap<Long, Vec2>()
            val uses = HashMap<Long, Int>()
            for (w in usable) {
                w.nodeIds.forEachIndexed { i, id ->
                    local.getOrPut(id) { frame.toLocal(w.points[i]) }
                    uses[id] = (uses[id] ?: 0) + 1
                }
            }
            val nodeIndex = HashMap<Long, Int>()
            val nodes = mutableListOf<Vec2>()
            val edges = mutableListOf<RoadEdge>()
            fun nodeOf(id: Long): Int = nodeIndex.getOrPut(id) { nodes += local[id]!!; nodes.size - 1 }

            for (w in usable) {
                val dir = direction(w, profile)
                val maxs = parseMaxSpeed(w.tags["maxspeed"])
                // Runs of consecutive nodes inside the polygon joined by inside segments.
                val ids = w.nodeIds
                var run = ArrayList<Int>()
                fun flushRun() {
                    if (run.size >= 2) {
                        // Split the run at junctions (shared nodes) and its ends.
                        var start = 0
                        for (k in 1 until run.size) {
                            val isJunction = (uses[ids[run[k]]] ?: 0) >= 2 || k == run.size - 1
                            if (isJunction) {
                                val pts = (start..k).map { local[ids[run[it]]]!! }
                                val a = nodeOf(ids[run[start]])
                                val b = nodeOf(ids[run[k]])
                                if (a != b) addEdges(edges, a, b, pts, w, maxs, dir)
                                start = k
                            }
                        }
                    }
                    run = ArrayList()
                }
                for (i in ids.indices) {
                    val p = local[ids[i]]!!
                    val inside = polygon.contains(p, 1e-6)
                    if (!inside) {
                        flushRun()
                        continue
                    }
                    if (run.isNotEmpty() && !polygon.segmentInside(local[ids[run.last()]]!!, p)) flushRun()
                    run += i
                }
                flushRun()
            }
            return largestComponent(nodes, edges)
        }

        private fun addEdges(edges: MutableList<RoadEdge>, a: Int, b: Int, pts: List<Vec2>, w: OsmWay, maxs: Double?, dir: Int) {
            val hw = w.highway ?: "road"
            var fwd = -1
            if (dir >= 0) {
                fwd = edges.size
                edges += RoadEdge(a, b, pts, hw, maxs, w.id, true)
            }
            if (dir <= 0) {
                val bi = edges.size
                edges += RoadEdge(b, a, pts.reversed(), hw, maxs, w.id, false, twin = fwd)
                if (fwd >= 0) edges[fwd].twin = bi
            }
        }

        /** Keeps the largest strongly connected component (iterative Kosaraju), re-indexing nodes and edges. */
        private fun largestComponent(nodes: List<Vec2>, edges: List<RoadEdge>): RoadGraph {
            val n = nodes.size
            if (n == 0) return RoadGraph(mutableListOf(), mutableListOf())
            val out = Array(n) { mutableListOf<Int>() }
            val inc = Array(n) { mutableListOf<Int>() }
            edges.forEachIndexed { i, e -> out[e.from] += i; inc[e.to] += i }
            // Pass 1: finishing order on the forward graph.
            val visited = BooleanArray(n)
            val order = ArrayList<Int>(n)
            for (s in 0 until n) {
                if (visited[s]) continue
                val stack = ArrayDeque<Pair<Int, Int>>()
                stack.addLast(s to 0)
                visited[s] = true
                while (stack.isNotEmpty()) {
                    val (u, k) = stack.removeLast()
                    if (k < out[u].size) {
                        stack.addLast(u to k + 1)
                        val v = edges[out[u][k]].to
                        if (!visited[v]) {
                            visited[v] = true
                            stack.addLast(v to 0)
                        }
                    } else {
                        order += u
                    }
                }
            }
            // Pass 2: components on the reverse graph in reverse finishing order.
            val comp = IntArray(n) { -1 }
            var c = 0
            for (s in order.asReversed()) {
                if (comp[s] >= 0) continue
                val stack = ArrayDeque<Int>()
                stack.addLast(s)
                comp[s] = c
                while (stack.isNotEmpty()) {
                    val u = stack.removeLast()
                    for (ei in inc[u]) {
                        val v = edges[ei].from
                        if (comp[v] < 0) {
                            comp[v] = c
                            stack.addLast(v)
                        }
                    }
                }
                c++
            }
            // Largest by total edge length (not node count).
            val size = DoubleArray(c)
            for (e in edges) if (comp[e.from] == comp[e.to]) size[comp[e.from]] += e.lengthM
            val best = size.indices.maxByOrNull { size[it] } ?: 0
            val remap = IntArray(n) { -1 }
            val newNodes = mutableListOf<Vec2>()
            for (i in 0 until n) if (comp[i] == best) {
                remap[i] = newNodes.size
                newNodes += nodes[i]
            }
            val edgeRemap = IntArray(edges.size) { -1 }
            val newEdges = mutableListOf<RoadEdge>()
            edges.forEachIndexed { i, e ->
                if (comp[e.from] == best && comp[e.to] == best) {
                    edgeRemap[i] = newEdges.size
                    newEdges += RoadEdge(remap[e.from], remap[e.to], e.points, e.highway, e.maxSpeedKmh, e.wayId, e.forward)
                }
            }
            edges.forEachIndexed { i, e -> if (edgeRemap[i] >= 0 && e.twin >= 0) newEdges[edgeRemap[i]].twin = edgeRemap[e.twin] }
            return RoadGraph(newNodes.toMutableList(), newEdges)
        }

        /** Cut a polyline at [at] metres. */
        internal fun cut(points: List<Vec2>, at: Double): Pair<List<Vec2>, List<Vec2>> {
            var acc = 0.0
            for (i in 0 until points.size - 1) {
                val seg = points[i].distanceTo(points[i + 1])
                if (acc + seg >= at) {
                    val f = if (seg > 0) ((at - acc) / seg).coerceIn(0.0, 1.0) else 0.0
                    val q = points[i] + (points[i + 1] - points[i]) * f
                    return (points.subList(0, i + 1) + q) to (listOf(q) + points.subList(i + 1, points.size))
                }
                acc += seg
            }
            return points to listOf(points.last(), points.last())
        }
    }
}
