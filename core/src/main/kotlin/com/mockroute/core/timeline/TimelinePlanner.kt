package com.mockroute.core.timeline

import com.mockroute.core.geo.LatLon
import com.mockroute.core.plan.SegmentPlanSettings
import com.mockroute.core.plan.SpeedProfileGenerator
import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.streets.RoadGraph
import com.mockroute.core.streets.StreetPlanner
import com.mockroute.core.trajectory.KinematicState
import com.mockroute.core.trajectory.PlanTrajectory
import com.mockroute.core.trajectory.Trajectory
import com.mockroute.core.zone.LocalFrame
import com.mockroute.core.zone.Polygon2
import com.mockroute.core.zone.ZonePlanner
import kotlin.math.min
import kotlin.random.Random

/** What the planner needs from the outside world (routing, road data, traffic). The app wires network + caches. */
interface TimelineSources {
    /**
     * The routed path of a route step (or straight/hand-drawn) with the router's ETA if it has one
     * ([com.mockroute.core.route.RouteResult.durationS]). Should start/end near the step's start/end.
     */
    suspend fun route(seg: Segment.Route): com.mockroute.core.route.RouteResult

    /** Road graph for a street zone (null → that segment can't be planned). */
    suspend fun roadGraph(seg: Segment.StreetZone, frame: LocalFrame, polygon: Polygon2): RoadGraph?

    /** Stage 5: TomTom analysis for a car/bus route departing at [departEpochS] (local median + auto settings), or null. */
    suspend fun traffic(seg: Segment.Route, geometry: RouteGeometry, departEpochS: Long): com.mockroute.core.traffic.TrafficAnalysis? = null

    /** Time zone for hour-of-day rules (rush hours, crossing waits). */
    val zone: java.time.ZoneId get() = java.time.ZoneId.systemDefault()
}

/** One planned segment on the global clock. */
class PlannedSegment(
    val index: Int,
    /** The segment as planned (compare with the timeline's current one to detect stale plans). */
    val segment: Segment,
    val trajectory: Trajectory?,
    val startOffsetS: Double,
    val durationS: Double,
    val distanceM: Double,
    val startSpeedMps: Double,
    val endSpeedMps: Double,
    /** Display path (LatLon). */
    val path: List<LatLon>,
    val warning: String? = null,
    val error: String? = null,
    /** Where the speed & time came from: "custom", or the automatic source ("route ETA × daytime at 10:05 + crossings"). */
    val settingsSource: String = "custom",
)

class TimelinePlan(val timeline: Timeline, val startEpochS: Long, val segments: List<PlannedSegment>) {
    val totalS: Double get() = segments.sumOf { it.durationS }
    val playable: Boolean get() = segments.isNotEmpty() && segments.all { it.trajectory != null }

    /** Stale if any segment changed since planning. */
    fun isStaleFor(t: Timeline): Boolean = t.segments.size != segments.size || t.segments.indices.any { t.segments[it] != segments[it].segment }

    fun trajectory(): TimelineTrajectory? = if (playable) TimelineTrajectory(segments.map { TimelineTrajectory.Part(it.trajectory!!, it.startOffsetS, it.durationS, label(it), it.segment.environment) }) else null

    fun label(p: PlannedSegment): String = when (p.segment) {
        is Segment.Route -> "Route"
        is Segment.IndoorZone -> "Indoor zone"
        is Segment.StreetZone -> "Street zone"
        is Segment.Pause -> "Pause"
    }
}

/** Stand still at [point] for [durationSeconds]. */
class HoldTrajectory(val point: LatLon, override val durationSeconds: Double, private val accuracyM: Double = Trajectory.DEFAULT_ACCURACY_M) : Trajectory {
    override fun stateAt(tSeconds: Double) = KinematicState(point.lat, point.lon, Trajectory.DEFAULT_ALTITUDE_M, 0.0, 0.0, accuracyM)
}

/**
 * Plans a [Timeline] in order: segment i starts when i−1 ends (that's the departure time for Stage 5 traffic), each
 * with its own deterministic seed. Boundary speeds are continuous: between two moving segments (route / street
 * zone) the vehicle keeps a moderate speed; at a Pause, an indoor entry/exit and the timeline ends it is 0.
 */
object TimelinePlanner {
    fun seedFor(t: Timeline, seg: Segment): Long {
        var h = t.seedBase * 1_000_003L + seg.id.hashCode().toLong()
        h = h * 31 + seg.seedOffset
        return h xor (h ushr 29)
    }

    /** Speed at boundary k (0..n). */
    fun boundarySpeed(t: Timeline, k: Int): Double {
        if (k == 0 || k == t.size) return 0.0
        val l = t.segments[k - 1]
        val r = t.segments[k]
        if (!moving(l) || !moving(r)) return 0.0
        val sl = settingsOf(l) ?: return 0.0
        val sr = settingsOf(r) ?: return 0.0
        val v = 0.6 * min(sl.speed.distribution().mean, sr.speed.distribution().mean)
        return v.coerceAtMost(min(sl.vMax, sr.vMax))
    }

    private fun moving(s: Segment) = s is Segment.Route || s is Segment.StreetZone

    fun profileOf(s: Segment): RoutingProfile = when (s) {
        is Segment.Route -> s.profile
        is Segment.StreetZone -> s.zone.profile
        else -> RoutingProfile.Walk
    }

    /** The segment's speed/time settings, or the defaults for its kind. */
    fun settingsOf(s: Segment, distanceM: Double = 1000.0): SegmentPlanSettings? = when (s) {
        is Segment.Route -> s.settings ?: SegmentPlanSettings.defaults(s.profile, distanceM)
        is Segment.IndoorZone -> s.settings ?: SegmentPlanSettings.zoneDefaults()
        is Segment.StreetZone -> s.settings ?: SegmentPlanSettings.streetDefaults(s.zone.profile)
        is Segment.Pause -> null
    }

    suspend fun plan(
        t: Timeline,
        startEpochS: Long,
        sources: TimelineSources,
        onProgress: (Int) -> Unit = {},
    ): TimelinePlan {
        val out = ArrayList<PlannedSegment>()
        var clock = 0.0
        for ((i, seg) in t.segments.withIndex()) {
            onProgress(i)
            val v0 = boundarySpeed(t, i)
            val vEnd = boundarySpeed(t, i + 1)
            val seed = seedFor(t, seg)
            val p = try {
                planOne(i, seg, clock, startEpochS, v0, vEnd, seed, sources)
            } catch (e: Exception) {
                PlannedSegment(i, seg, null, clock, 0.0, 0.0, v0, vEnd, emptyList(), error = e.message ?: e.javaClass.simpleName)
            }
            out += p
            clock += p.durationS
        }
        return TimelinePlan(t, startEpochS, out)
    }

    private suspend fun planOne(
        i: Int,
        seg: Segment,
        startOffset: Double,
        startEpochS: Long,
        v0: Double,
        vEnd: Double,
        seed: Long,
        src: TimelineSources,
    ): PlannedSegment = when (seg) {
        is Segment.Pause -> {
            val d = seg.duration.distribution().sample(Random(seed)).coerceAtLeast(1.0)
            PlannedSegment(i, seg, HoldTrajectory(seg.at, d), startOffset, d, 0.0, 0.0, 0.0, listOf(seg.at))
        }
        is Segment.Route -> {
            val routed = src.route(seg)
            var g = routed.geometry
            // Start/end EXACTLY at the shared points (a routed path starts on the snapped road).
            val pts = g.points.toMutableList()
            if (pts.isEmpty() || com.mockroute.core.geo.GeoMath.haversineMeters(pts.first(), seg.start) > 0.05) pts.add(0, seg.start)
            if (com.mockroute.core.geo.GeoMath.haversineMeters(pts.last(), seg.end) > 0.05) pts.add(seg.end)
            g = RouteGeometry.of(pts)
            require(g.lengthM >= 1.0) { "Route ${i + 1}: set its end point" }
            val depart = startEpochS + startOffset.toLong()
            val traffic = src.traffic(seg, g, depart)
            // Custom settings if the user set them; otherwise data-driven ones for this hour (traffic / ETA / standard).
            val auto = if (seg.settings == null) com.mockroute.core.plan.AutoSettings.forRoute(seg.profile, g.lengthM, routed.durationS, depart, src.zone, traffic) else null
            val st = seg.settings ?: auto!!.settings
            // Walking: real stops at street crossings (always: it's how walking in a city works).
            val stops = if (seg.profile == RoutingProfile.Walk) com.mockroute.core.plan.AutoSettings.crossingStops(g.lengthM, depart, src.zone, Random(seed xor 0x5eed)) else emptyList()
            val plan = SpeedProfileGenerator.generate(
                SpeedProfileGenerator.Request(g, st, seg.profile, seed, v0 = v0, vEnd = vEnd, localMedian = traffic?.let { a -> { s: Double -> a.medianSpeedAt(s) } }, stops = stops),
            )
            PlannedSegment(i, seg, PlanTrajectory(g, plan), startOffset, plan.totalTimeS, g.lengthM, v0, vEnd, g.points, plan.warning, settingsSource = auto?.source ?: "custom")
        }
        is Segment.IndoorZone -> {
            val z = seg.zone
            val f = z.frame() ?: error("Zone ${i + 1}: draw its shape")
            val poly = z.polygon(f) ?: error("Zone ${i + 1}: the shape is invalid")
            val st = settingsOf(seg)!!
            val zp = ZonePlanner.plan(
                ZonePlanner.Request(poly, f, f.toLocal(seg.start), f.toLocal(seg.end), z.mixture(f, poly), st, seed, false, z.dwellProbability, z.dwellMeanS, z.wobble, z.mode),
            )
            PlannedSegment(i, seg, zp, startOffset, zp.durationSeconds, zp.lengthM, 0.0, 0.0, zp.geometry.points, zp.warning, settingsSource = if (seg.settings == null) "indoor defaults" else "custom")
        }
        is Segment.StreetZone -> {
            val z = seg.zone
            val f = z.frame() ?: error("Zone ${i + 1}: draw its shape")
            val poly = z.polygon(f) ?: error("Zone ${i + 1}: the shape is invalid")
            // Street zones: start and end must be inside the zone or on its edge, never outside.
            for ((p, k) in listOf(seg.start to i, seg.end to i + 1)) {
                require(poly.contains(f.toLocal(p), Timeline.EDGE_TOLERANCE_M)) {
                    "Point ${Timeline.pointLabel(k)} is outside the street zone: reshape the zone so it contains the point"
                }
            }
            val graph = src.roadGraph(seg, f, poly) ?: error("Zone ${i + 1}: roads not loaded")
            val st = settingsOf(seg)!!
            val sp = StreetPlanner.plan(
                StreetPlanner.Request(
                    graph, poly, f, f.toLocal(seg.start), f.toLocal(seg.end), z.mixture(f, poly), st, z.profile, seed,
                    dwellProbability = if (z.profile == RoutingProfile.Walk) z.dwellProbability else 0.0,
                    dwellMeanS = z.dwellMeanS, mode = z.mode, v0 = v0, vEnd = vEnd, connectEndpoints = true,
                ),
            )
            PlannedSegment(i, seg, sp.zone, startOffset, sp.zone.durationSeconds, sp.zone.lengthM, v0, vEnd, sp.zone.geometry.points, sp.zone.warning, settingsSource = if (seg.settings == null) "${z.profile.label.lowercase()} defaults" else "custom")
        }
    }
}

/**
 * All segments on one global clock. stateAt(t) finds the segment by binary search on its start offset and asks it
 * for t − start. Supports "start at segment k" ([startOf]) and skipping ([nextStart]).
 */
class TimelineTrajectory(val parts: List<Part>) : Trajectory {
    class Part(
        val trajectory: Trajectory,
        val startS: Double,
        val durationS: Double,
        val label: String,
        /** Per-step override of the reception environment (null = the trajectory's own). */
        val environment: com.mockroute.core.noise.Environment? = null,
    )

    override fun environmentAt(tSeconds: Double): com.mockroute.core.noise.Environment {
        val t = tSeconds.coerceAtLeast(0.0)
        val p = parts[indexAt(t)]
        return p.environment ?: p.trajectory.environmentAt((t - p.startS).coerceAtMost(p.durationS))
    }

    init {
        require(parts.isNotEmpty())
    }

    override val durationSeconds: Double = parts.last().startS + parts.last().durationS

    fun indexAt(t: Double): Int {
        var lo = 0
        var hi = parts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (parts[mid].startS <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    override fun stateAt(tSeconds: Double): KinematicState {
        val t = tSeconds.coerceAtLeast(0.0)
        val i = indexAt(t)
        val p = parts[i]
        val s = p.trajectory.stateAt((t - p.startS).coerceAtMost(p.durationS))
        return if (t >= durationSeconds) s.copy(speedMps = 0.0) else s
    }

    fun startOf(k: Int): Double = parts[k.coerceIn(0, parts.size - 1)].startS
    fun nextStart(t: Double): Double? = parts.getOrNull(indexAt(t) + 1)?.startS

    /** "2/4 · Indoor zone". */
    fun labelAt(t: Double): String {
        val i = indexAt(t)
        return "${i + 1}/${parts.size} · ${parts[i].label}"
    }
}
