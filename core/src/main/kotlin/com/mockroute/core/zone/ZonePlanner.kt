package com.mockroute.core.zone

import com.mockroute.core.dist.BoundedGamma
import com.mockroute.core.geo.LatLon
import com.mockroute.core.plan.Feasibility
import com.mockroute.core.plan.SegmentPlanSettings
import com.mockroute.core.plan.SpeedPlan
import com.mockroute.core.plan.SpeedProfileGenerator
import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.trajectory.KinematicState
import com.mockroute.core.trajectory.Trajectory
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Indoor zone plan: a random path inside [polygon] from A to B lasting T, played like any [Trajectory].
 * Positions are computed in the local frame (exact straight segments, wobble clipped inside) and converted
 * to LatLon at the end.
 */
class ZonePlan(
    val polygon: Polygon2,
    val frame: LocalFrame,
    val a: Vec2,
    val b: Vec2,
    /** Path vertices in local metres (A … waypoints/inset corners … B). */
    val path: List<Vec2>,
    val plan: SpeedPlan,
    /** Requested total time T (s). */
    val targetTimeS: Double,
    val waypoints: List<Vec2>,
    val stops: List<SpeedProfileGenerator.Stop>,
    private val wobbleDt: Double,
    private val wobble: DoubleArray,
    val warning: String?,
    /** Shortest inside path A→B (m): the feasibility distance. */
    val shortestPathM: Double,
) : Trajectory {
    private val cum = DoubleArray(path.size).also { for (i in 1 until path.size) it[i] = it[i - 1] + path[i - 1].distanceTo(path[i]) }
    val lengthM: Double get() = cum.last()

    /** The path as LatLon (for the gradient display and PlanRendering). */
    val geometry: RouteGeometry = RouteGeometry.of(path.map(frame::toLatLon))

    override val durationSeconds: Double = plan.totalTimeS

    private fun pointAt(s: Double): Pair<Vec2, Vec2> {
        val d = s.coerceIn(0.0, lengthM)
        var lo = 0
        var hi = path.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cum[mid] <= d) lo = mid else hi = mid - 1
        }
        var i = lo
        while (i < path.size - 2 && cum[i + 1] - cum[i] < 1e-9) i++
        val seg = cum[i + 1] - cum[i]
        val dir = if (seg > 1e-9) (path[i + 1] - path[i]) * (1 / seg) else Vec2(0.0, 1.0)
        val f = if (seg > 1e-9) ((d - cum[i]) / seg).coerceIn(0.0, 1.0) else 0.0
        return (path[i] + (path[i + 1] - path[i]) * f) to dir
    }

    private fun wobbleAt(t: Double): Double {
        if (wobble.isEmpty()) return 0.0
        val x = (t / wobbleDt).coerceIn(0.0, (wobble.size - 1).toDouble())
        val i = x.toInt().coerceAtMost(wobble.size - 2)
        val f = x - i
        val e = wobble[i] + f * (wobble[i + 1] - wobble[i])
        // Taper to 0 within 3 s of the ends so the plan starts exactly at A and ends exactly at B.
        val taper = min(1.0, min(t / 3.0, (durationSeconds - t) / 3.0)).coerceAtLeast(0.0)
        return e * taper
    }

    /** Local position at time t (wobble applied and clipped inside). */
    fun localPositionAt(tSeconds: Double): Pair<Vec2, Vec2> {
        val k = plan.stateAtTime(tSeconds)
        val s = if (plan.distanceM > 0) k.distanceM * lengthM / plan.distanceM else 0.0
        val (p, dir) = pointAt(s)
        var e = wobbleAt(tSeconds)
        if (e != 0.0) {
            val n = dir.perp()
            repeat(6) {
                if (polygon.containsWithMargin(p + n * e, 0.05)) return (p + n * e) to dir
                e *= 0.5
            }
        }
        return p to dir
    }

    override fun stateAt(tSeconds: Double): KinematicState {
        val (p, dir) = localPositionAt(tSeconds)
        val ll = frame.toLatLon(p)
        val bearing = ((Math.toDegrees(atan2(dir.x, dir.y)) % 360) + 360) % 360
        val speed = if (tSeconds >= durationSeconds) 0.0 else plan.stateAtTime(tSeconds).speedMps
        return KinematicState(ll.lat, ll.lon, Trajectory.DEFAULT_ALTITUDE_M, speed, bearing, INDOOR_ACCURACY_M)
    }

    companion object {
        /** Reported horizontal accuracy indoors (Stage 9 will model it properly). */
        const val INDOOR_ACCURACY_M = 12.0
    }
}

object ZonePlanner {
    const val INSET_M = 0.3
    const val WAYPOINT_MARGIN_M = 0.4
    const val WOBBLE_SIGMA_M = 0.3
    const val WOBBLE_TAU_S = 3.0
    const val MAX_WAYPOINTS = 300

    data class Request(
        val polygon: Polygon2,
        val frame: LocalFrame,
        /** Entry/exit ON the boundary (use [snapToEdge]). */
        val a: Vec2,
        val b: Vec2,
        val mixture: GaussianMixture2D,
        val settings: SegmentPlanSettings,
        val seed: Long,
        val lockTime: Boolean = false,
        val dwellProbability: Double = 0.3,
        val dwellMeanS: Double = 30.0,
        val wobble: Boolean = true,
        val mode: WanderMode = WanderMode.Waypoints,
    )

    /** Nearest boundary point: entry/exit are always ON the edge, never inside. */
    fun snapToEdge(polygon: Polygon2, p: Vec2): Vec2 = polygon.nearestBoundaryPoint(p).point

    fun snapToEdge(polygon: Polygon2, frame: LocalFrame, p: LatLon): LatLon = frame.toLatLon(snapToEdge(polygon, frame.toLocal(p)))

    /** Shortest inside path length A→B. */
    fun shortestPath(polygon: Polygon2, a: Vec2, b: Vec2): List<Vec2> = InsidePathFinder(polygon, INSET_M).path(a, b)

    /**
     * Zone feasibility: like a route over the shortest inside path, except that "too short for v_min·t_min" doesn't
     * apply (wandering makes the path as long as needed).
     */
    fun feasibility(shortestM: Double, settings: SegmentPlanSettings): Feasibility.Result {
        val r = settings.feasibility(shortestM)
        val issues = r.issues - Feasibility.Issue.TooShort
        return r.copy(feasible = issues.isEmpty(), issues = issues)
    }

    fun plan(req: Request): ZonePlan {
        val rng = Random(req.seed)
        val st = req.settings
        val aMax = st.aMaxMps2
        val finder = InsidePathFinder(req.polygon, INSET_M)
        val shortest = finder.path(req.a, req.b)
        val dShort = InsidePathFinder.length(shortest)

        // 1. Time budget T: sampled (or mean), at least the physical minimum over the shortest inside path.
        val timeDist = st.time.distribution()
        val tSample = if (req.lockTime) timeDist.mean else timeDist.sample(rng)
        val tLow = Feasibility.tMinPhys(dShort, st.vMax, aMax) * 1.02 + 1.0
        val target = tSample.coerceIn(tLow, max(tLow, st.tMax))

        // 2. Waypoints within the budget, with planning speed = the speed distribution's (time-weighted) mean.
        val vBar = st.speed.distribution().mean.coerceAtLeast(0.3)
        val dwellDist = BoundedGamma.withMean(0.0, max(1.0, req.dwellMeanS * 6), 2.0, req.dwellMeanS.coerceAtLeast(0.5))
        val legs = ArrayList<List<Vec2>>()
        val waypoints = ArrayList<Vec2>()
        val stopsLocal = ArrayList<Pair<Double, Double>>() // (local distance, duration)
        var cur = req.a
        var est = 0.0
        var walked = 0.0
        var misses = 0
        val langevinStep = sqrt(req.polygon.area) / 6
        while (waypoints.size < MAX_WAYPOINTS && misses < 8) {
            val wp = when (req.mode) {
                WanderMode.Waypoints -> req.mixture.sampleInside(req.polygon, rng, WAYPOINT_MARGIN_M)
                WanderMode.Langevin -> langevin(req.mixture, req.polygon, if (waypoints.isEmpty()) req.mixture.sampleInside(req.polygon, rng, WAYPOINT_MARGIN_M) else cur, langevinStep, rng)
            }
            val p1 = finder.path(cur, wp)
            val p2 = finder.path(wp, req.b)
            val l1 = InsidePathFinder.length(p1)
            val dwell = if (req.dwellProbability > 0 && rng.nextDouble() < req.dwellProbability) dwellDist.sample(rng) else 0.0
            val overhead = if (dwell > 0) vBar / aMax else 0.0
            val need = est + l1 / vBar + dwell + overhead + InsidePathFinder.length(p2) / vBar
            if (need > target * 0.97) {
                misses++
                continue
            }
            misses = 0
            legs += p1
            walked += l1
            est += l1 / vBar
            if (dwell > 0) {
                stopsLocal += walked to dwell
                est += dwell + overhead
            }
            waypoints += wp
            cur = wp
        }
        legs += finder.path(cur, req.b)

        // 3. One polyline (consecutive duplicates removed).
        val path = ArrayList<Vec2>()
        for (leg in legs) for (p in leg) if (path.isEmpty() || path.last().distanceTo(p) > 1e-6) path += p
        if (path.size < 2) path += req.b + Vec2(1e-3, 0.0)

        // 4. Stage 4 generator over the LatLon polyline, dwells as v = 0 holds, target T exactly.
        val geometry = RouteGeometry.of(path.map(req.frame::toLatLon))
        val localLen = InsidePathFinder.length(path)
        val toGeo = if (localLen > 0) geometry.lengthM / localLen else 1.0
        val step = if (geometry.lengthM < 2000) 2.0 else 5.0
        fun generate(extraEndDwell: Double): SpeedPlan {
            val stops = stopsLocal.map { SpeedProfileGenerator.Stop(it.first * toGeo, it.second) } +
                if (extraEndDwell > 0) listOf(SpeedProfileGenerator.Stop(geometry.lengthM, extraEndDwell)) else emptyList()
            return SpeedProfileGenerator.generate(
                SpeedProfileGenerator.Request(
                    geometry, st, RoutingProfile.Walk, req.seed,
                    curvatureCap = false, stepM = step, stops = stops, targetTimeS = target,
                    correlationLengthM = st.correlationLengthM ?: 30.0,
                ),
            )
        }
        var plan = generate(0.0)
        if (plan.totalTimeS < target - 0.5) {
            // Even the slowest speeds finish early: wait at B for the rest (still "exits at B at T").
            plan = generate(target - plan.totalTimeS)
        }

        // 5. Lateral OU wobble (σ 0.3 m, τ 3 s), clipped inside at playback.
        val dt = 0.5
        val wob = if (req.wobble) {
            val n = (plan.totalTimeS / dt).toInt() + 2
            val rho = exp(-dt / WOBBLE_TAU_S)
            val innov = sqrt(1 - rho * rho) * WOBBLE_SIGMA_M
            DoubleArray(n).also { w -> for (i in 1 until n) w[i] = rho * w[i - 1] + innov * gaussianPair(rng).first }
        } else {
            DoubleArray(0)
        }

        return ZonePlan(
            polygon = req.polygon,
            frame = req.frame,
            a = req.a,
            b = req.b,
            path = path,
            plan = plan,
            targetTimeS = target,
            waypoints = waypoints,
            stops = stopsLocal.map { SpeedProfileGenerator.Stop(it.first, it.second) },
            wobbleDt = dt,
            wobble = wob,
            warning = plan.warning,
            shortestPathM = dShort,
        )
    }

    /**
     * One overdamped Langevin step on log p (drift towards high density + noise), reflective boundary: a step that
     * leaves the zone is mirrored at the nearest wall; if that's still outside it is retried.
     */
    internal fun langevin(m: GaussianMixture2D, poly: Polygon2, x: Vec2, step: Double, rng: Random): Vec2 {
        val h = 0.25
        fun logp(p: Vec2) = kotlin.math.ln(m.density(p.x, p.y).coerceAtLeast(1e-300))
        val grad = Vec2((logp(x + Vec2(h, 0.0)) - logp(x - Vec2(h, 0.0))) / (2 * h), (logp(x + Vec2(0.0, h)) - logp(x - Vec2(0.0, h))) / (2 * h))
        var drift = grad * (step * step / 2)
        if (drift.length() > 3 * step) drift = drift.normalized() * (3 * step)
        repeat(20) {
            val (g1, g2) = gaussianPair(rng)
            var y = x + drift + Vec2(g1, g2) * step
            if (!poly.containsWithMargin(y, WAYPOINT_MARGIN_M)) {
                val nb = poly.nearestBoundaryPoint(y).point
                y = nb * 2.0 - y
            }
            if (poly.containsWithMargin(y, WAYPOINT_MARGIN_M)) return y
        }
        return x
    }
}
