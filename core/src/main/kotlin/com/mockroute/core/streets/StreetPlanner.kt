package com.mockroute.core.streets

import com.mockroute.core.dist.BoundedGamma
import com.mockroute.core.plan.Feasibility
import com.mockroute.core.plan.SegmentPlanSettings
import com.mockroute.core.plan.SpeedPlan
import com.mockroute.core.plan.SpeedProfileGenerator
import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.zone.GaussianMixture2D
import com.mockroute.core.zone.LocalFrame
import com.mockroute.core.zone.Polygon2
import com.mockroute.core.zone.Vec2
import com.mockroute.core.zone.WanderMode
import com.mockroute.core.zone.ZonePlan
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Result: the playable plan plus what the tests (and the map) need to know about the graph walk. */
class StreetPlan(
    val zone: ZonePlan,
    /** Graph with A/B inserted. */
    val graph: RoadGraph,
    /** Directed edges traversed, in order. */
    val edgePath: List<Int>,
    val nodeA: Int,
    val nodeB: Int,
)

/**
 * STREET zone: wander only on the road graph inside the polygon.
 *
 * 1. Snap A and B onto the graph (an existing node within 0.5 m, otherwise the edge is split).
 * 2. T ~ time gamma (or its mean), ≥ tMinPhys(shortest A→B)·1.02 + 1 s.
 * 3. Reverse Dijkstra → distance-to-B for every node (the "reach B" guard).
 * 4. Wander:
 *    - [WanderMode.Waypoints] (default): targets sampled ∝ mixture density at the graph nodes, joined by Dijkstra.
 *    - [WanderMode.Langevin] ("drift"): biased random walk; next edge ∝ p(head)^β, U-turns ×0.05, one-way rules
 *      honoured by the directed graph.
 *    Stop when (time so far + leg + way to B, at the speed mean) > 0.97·T; then the shortest path to B.
 * 5. Stage 4 generator on the polyline with dwells (walk), maxspeed caps, and ≤ 15 km/h through turns > 60° (car/bus).
 */
object StreetPlanner {
    const val TURN_ANGLE_DEG = 60.0
    const val TURN_SPEED_KMH = 15.0
    const val TURN_ZONE_M = 8.0
    const val STREET_ACCURACY_M = 5.0

    data class Request(
        val graph: RoadGraph,
        val polygon: Polygon2,
        val frame: LocalFrame,
        val a: Vec2,
        val b: Vec2,
        val mixture: GaussianMixture2D,
        val settings: SegmentPlanSettings,
        val profile: RoutingProfile,
        val seed: Long,
        val lockTime: Boolean = false,
        val dwellProbability: Double = 0.0,
        val dwellMeanS: Double = 30.0,
        val mode: WanderMode = WanderMode.Waypoints,
        val beta: Double = 1.5,
    )

    /** Shortest A→B on the graph (feasibility distance). */
    fun shortestPathM(graph: RoadGraph, a: Vec2, b: Vec2): Double {
        val g = graph.copy()
        val na = g.insertNode(a)
        val nb = g.insertNode(b)
        return g.dijkstra(na).first[nb]
    }

    fun plan(req: Request): StreetPlan {
        val rng = Random(req.seed)
        val st = req.settings
        val g = req.graph.copy()
        val na = g.insertNode(req.a)
        val nb = g.insertNode(req.b)
        val toB = g.dijkstra(nb, reverse = true).first
        val dShort = toB[na].takeIf { it.isFinite() } ?: error("B not reachable from A on the road graph")

        val timeDist = st.time.distribution()
        val tSample = if (req.lockTime) timeDist.mean else timeDist.sample(rng)
        val tLow = Feasibility.tMinPhys(max(dShort, 1.0), st.vMax, st.aMaxMps2) * 1.02 + 1.0
        val target = tSample.coerceIn(tLow, max(tLow, st.tMax))
        val vBar = st.speed.distribution().mean.coerceAtLeast(0.3)

        val walk = req.profile == RoutingProfile.Walk
        val dwellDist = BoundedGamma.withMean(0.0, max(1.0, req.dwellMeanS * 6), 2.0, req.dwellMeanS.coerceAtLeast(0.5))
        // Node weights ∝ mixture density (tiny floor so far nodes are still possible).
        val dens = DoubleArray(g.nodeCount) { req.mixture.density(g.nodes[it].x, g.nodes[it].y) }
        val floor = (dens.maxOrNull() ?: 0.0) * 1e-4 + 1e-300
        val cum = DoubleArray(g.nodeCount)
        var acc = 0.0
        for (i in 0 until g.nodeCount) {
            acc += dens[i] + floor
            cum[i] = acc
        }

        // The walker plans at the speed mean, but maxspeed/turn caps can make the real plan slower than T. If the
        // generator can't be fast enough, re-plan with a smaller wandering budget; with budget 0 it goes straight
        // to B, and if even that can't make T, T is raised to the fastest achievable time.
        var attempt: Attempt? = null
        for (budget in listOf(0.97, 0.9, 0.8, 0.65, 0.5, 0.3, 0.0)) {
            val at = wander(req, g, na, nb, toB, target, vBar, budget, walk, dwellDist, dens, floor, cum, acc, Random(req.seed xor (budget * 1000).toLong()))
            attempt = at
            if (at.plan.totalTimeS <= target + 0.5) break
        }
        var at = attempt!!
        var finalTarget = target
        if (at.plan.totalTimeS > target + 0.5) {
            finalTarget = at.plan.totalTimeS
            at = at.copy(plan = at.regenerate(finalTarget))
        }
        val plan = at.plan
        val path = at.path
        val edgePath = at.edgePath
        val waypoints = at.waypoints
        val stopsLocal = at.stopsLocal

        val zone = ZonePlan(
            polygon = req.polygon,
            frame = req.frame,
            a = g.nodes[na],
            b = g.nodes[nb],
            path = path,
            plan = plan,
            targetTimeS = finalTarget,
            waypoints = waypoints,
            stops = stopsLocal.map { SpeedProfileGenerator.Stop(it.first, it.second) },
            wobbleDt = 0.5,
            wobble = DoubleArray(0),
            warning = plan.warning,
            shortestPathM = dShort,
            accuracyM = STREET_ACCURACY_M,
        )
        return StreetPlan(zone, g, edgePath, na, nb)
    }

    private data class Attempt(
        val plan: SpeedPlan,
        val path: List<Vec2>,
        val edgePath: List<Int>,
        val waypoints: List<Vec2>,
        val stopsLocal: List<Pair<Double, Double>>,
        val regenerate: (Double) -> SpeedPlan,
    )

    private fun wander(
        req: Request,
        g: RoadGraph,
        na: Int,
        nb: Int,
        toB: DoubleArray,
        target: Double,
        vBar: Double,
        budget: Double,
        walk: Boolean,
        dwellDist: BoundedGamma,
        dens: DoubleArray,
        floor: Double,
        cum: DoubleArray,
        acc: Double,
        rng: Random,
    ): Attempt {
        val st = req.settings
        val edgePath = ArrayList<Int>()
        val waypoints = ArrayList<Vec2>()
        val stopsLocal = ArrayList<Pair<Double, Double>>()
        var walked = 0.0
        var est = 0.0
        var cur = na


        fun append(edges: List<Int>) {
            for (ei in edges) {
                edgePath += ei
                walked += g.edges[ei].lengthM
            }
        }

        when (req.mode) {
            WanderMode.Waypoints -> {
                var misses = 0
                while (misses < 8 && waypoints.size < 500) {
                    val r = rng.nextDouble() * acc
                    var t = cum.binarySearch(r).let { if (it < 0) -it - 1 else it }.coerceIn(0, g.nodeCount - 1)
                    if (t == cur) t = (t + 1) % g.nodeCount
                    val (dist, prev) = g.dijkstra(cur)
                    val leg = dist[t]
                    val dwell = if (walk && req.dwellProbability > 0 && rng.nextDouble() < req.dwellProbability) dwellDist.sample(rng) else 0.0
                    val over = if (dwell > 0) vBar / st.aMaxMps2 else 0.0
                    if (!leg.isFinite() || est + leg / vBar + dwell + over + toB[t] / vBar > budget * target) {
                        misses++
                        continue
                    }
                    misses = 0
                    append(g.shortestPath(cur, t, prev))
                    est += leg / vBar
                    if (dwell > 0) {
                        stopsLocal += walked to dwell
                        est += dwell + over
                    }
                    waypoints += g.nodes[t]
                    cur = t
                }
            }
            WanderMode.Langevin -> {
                var last = -1
                var steps = 0
                while (steps++ < 20_000) {
                    val options = g.out[cur]
                    if (options.isEmpty()) break
                    val weights = DoubleArray(options.size) { k ->
                        val e = g.edges[options[k]]
                        val uTurn = last >= 0 && g.edges[last].twin == options[k]
                        (dens[e.to] + floor).pow(req.beta) * if (uTurn) 0.05 else 1.0
                    }
                    var r = rng.nextDouble() * weights.sum()
                    var k = 0
                    while (k < options.size - 1 && r > weights[k]) {
                        r -= weights[k]
                        k++
                    }
                    val e = g.edges[options[k]]
                    if (est + e.lengthM / vBar + toB[e.to] / vBar > budget * target) break
                    append(listOf(options[k]))
                    est += e.lengthM / vBar
                    last = options[k]
                    cur = e.to
                }
            }
        }
        append(g.shortestPath(cur, nb))

        // Polyline (local) from the edge sequence.
        val path = ArrayList<Vec2>()
        path += g.nodes[na]
        val edgeStart = DoubleArray(edgePath.size)
        var s = 0.0
        for ((k, ei) in edgePath.withIndex()) {
            edgeStart[k] = s
            val pts = g.edges[ei].points
            for (j in 1 until pts.size) {
                if (path.last().distanceTo(pts[j]) > 1e-6) path += pts[j]
            }
            s += g.edges[ei].lengthM
        }
        if (path.size < 2) path += g.nodes[nb] + Vec2(1e-3, 0.0)

        // Speed caps (car/bus): maxspeed per edge, ≤ 15 km/h within ±8 m of a turn > 60°.
        val turns = if (walk) emptyList() else turnPoints(path)
        val geometry = RouteGeometry.of(path.map(req.frame::toLatLon))
        val localLen = path.zipWithNext().sumOf { (p, q) -> p.distanceTo(q) }
        val toGeo = if (localLen > 0) geometry.lengthM / localLen else 1.0
        val cap: ((Double) -> Double)? = if (walk) null else { sGeo ->
            val sl = sGeo / toGeo
            var c = Double.POSITIVE_INFINITY
            for (tp in turns) if (abs(tp - sl) <= TURN_ZONE_M) c = min(c, TURN_SPEED_KMH / 3.6)
            val k = edgeStart.binarySearch(sl).let { if (it < 0) -it - 2 else it }.coerceIn(0, max(0, edgePath.size - 1))
            g.edges.getOrNull(edgePath.getOrNull(k) ?: -1)?.maxSpeedKmh?.let { c = min(c, it / 3.6) }
            c
        }
        fun generate(extraEnd: Double, tgt: Double): SpeedPlan = SpeedProfileGenerator.generate(
            SpeedProfileGenerator.Request(
                geometry, st, req.profile, req.seed,
                curvatureCap = false,
                stepM = if (geometry.lengthM < 10_000) 5.0 else 10.0,
                stops = stopsLocal.map { SpeedProfileGenerator.Stop(it.first * toGeo, it.second) } +
                    if (extraEnd > 0) listOf(SpeedProfileGenerator.Stop(geometry.lengthM, extraEnd)) else emptyList(),
                targetTimeS = tgt,
                speedCap = cap,
            ),
        )
        var plan = generate(0.0, target)
        if (plan.totalTimeS < target - 0.5) plan = generate(target - plan.totalTimeS, target)
        return Attempt(plan, path, edgePath, waypoints, stopsLocal) { t -> generate(0.0, t) }

    }

    /** Distances along [path] of vertices where the heading turns by more than 60°. */
    internal fun turnPoints(path: List<Vec2>): List<Double> {
        val out = ArrayList<Double>()
        var s = 0.0
        for (i in 1 until path.size - 1) {
            s += path[i - 1].distanceTo(path[i])
            val u = (path[i] - path[i - 1]).normalized()
            val v = (path[i + 1] - path[i]).normalized()
            val ang = Math.toDegrees(acos(u.dot(v).coerceIn(-1.0, 1.0)))
            if (ang > TURN_ANGLE_DEG) out += s
        }
        return out
    }
}
