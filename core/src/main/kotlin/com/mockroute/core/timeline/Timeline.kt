package com.mockroute.core.timeline

import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import com.mockroute.core.plan.GammaSetting
import com.mockroute.core.plan.SegmentPlanSettings
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.zone.ZoneDefinition
import com.mockroute.core.zone.ZoneKind
import com.mockroute.core.zone.ZonePlanner
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a route segment's path is drawn (mirrors the Stage 2 editor modes). */
@Serializable
enum class RouteMode { PointToPoint, Streets, FreeDraw }

/**
 * One timeline step. Every segment has a [start] and an [end]; the timeline keeps end(i) == start(i+1).
 * Zones keep their entry/exit in sync with start/end; a [Pause] is a single point (start == end).
 */
@Serializable
sealed class Segment {
    abstract val id: String
    abstract val start: LatLon
    abstract val end: LatLon
    /** Per-segment re-roll counter (mixed into the seed). */
    abstract val seedOffset: Long
    abstract fun withStart(p: LatLon): Segment
    abstract fun withEnd(p: LatLon): Segment
    abstract fun reroll(): Segment

    /** Stage 9: reception environment override for the GPS noise (null = automatic for the step's kind). */
    abstract val environment: com.mockroute.core.noise.Environment?
    abstract fun withEnvironment(e: com.mockroute.core.noise.Environment?): Segment

    /** Base altitude (m above sea level) for this step; null = automatic (the real location's altitude). */
    abstract val altitudeM: Double?
    abstract fun withAltitude(a: Double?): Segment

    @Serializable
    @SerialName("route")
    data class Route(
        override val id: String,
        override val start: LatLon,
        override val end: LatLon,
        val profile: RoutingProfile = RoutingProfile.Walk,
        val mode: RouteMode = RouteMode.PointToPoint,
        /** Intermediate tap points (Streets / Free draw). */
        val waypoints: List<LatLon> = emptyList(),
        /** Last routed geometry (cache) and the inputs it was routed for: stale when they differ from [inputs]. */
        val geometry: List<LatLon> = emptyList(),
        val geometryFor: List<LatLon> = emptyList(),
        val settings: SegmentPlanSettings? = null,
        override val seedOffset: Long = 0,
        override val environment: com.mockroute.core.noise.Environment? = null,
        override val altitudeM: Double? = null,
    ) : Segment() {
        override fun withEnvironment(e: com.mockroute.core.noise.Environment?) = copy(environment = e)
        override fun withAltitude(a: Double?) = copy(altitudeM = a)
        val inputs: List<LatLon> get() = listOf(start) + waypoints + listOf(end)
        val geometryFresh: Boolean get() = geometry.size >= 2 && geometryFor == inputs
        override fun withStart(p: LatLon) = copy(start = p)
        override fun withEnd(p: LatLon) = copy(end = p)
        override fun reroll() = copy(seedOffset = seedOffset + 1)
    }

    @Serializable
    @SerialName("indoor")
    data class IndoorZone(
        override val id: String,
        override val start: LatLon,
        override val end: LatLon,
        val zone: ZoneDefinition,
        val settings: SegmentPlanSettings? = null,
        override val seedOffset: Long = 0,
        override val environment: com.mockroute.core.noise.Environment? = null,
        override val altitudeM: Double? = null,
    ) : Segment() {
        override fun withEnvironment(e: com.mockroute.core.noise.Environment?) = copy(environment = e)
        override fun withAltitude(a: Double?) = copy(altitudeM = a)
        override fun withStart(p: LatLon) = copy(start = p, zone = zone.copy(entry = p))
        override fun withEnd(p: LatLon) = copy(end = p, zone = zone.copy(exit = p))
        override fun reroll() = copy(seedOffset = seedOffset + 1)
    }

    @Serializable
    @SerialName("street")
    data class StreetZone(
        override val id: String,
        override val start: LatLon,
        override val end: LatLon,
        val zone: ZoneDefinition,
        val settings: SegmentPlanSettings? = null,
        override val seedOffset: Long = 0,
        override val environment: com.mockroute.core.noise.Environment? = null,
        override val altitudeM: Double? = null,
    ) : Segment() {
        override fun withEnvironment(e: com.mockroute.core.noise.Environment?) = copy(environment = e)
        override fun withAltitude(a: Double?) = copy(altitudeM = a)
        override fun withStart(p: LatLon) = copy(start = p, zone = zone.copy(entry = p))
        override fun withEnd(p: LatLon) = copy(end = p, zone = zone.copy(exit = p))
        override fun reroll() = copy(seedOffset = seedOffset + 1)
    }

    /** Stand still at [at] for a duration drawn from [duration] (seconds). */
    @Serializable
    @SerialName("pause")
    data class Pause(
        override val id: String,
        val at: LatLon,
        val duration: GammaSetting = GammaSetting(60.0, 300.0, 120.0, 4.0),
        override val seedOffset: Long = 0,
        override val environment: com.mockroute.core.noise.Environment? = null,
        override val altitudeM: Double? = null,
    ) : Segment() {
        override fun withEnvironment(e: com.mockroute.core.noise.Environment?) = copy(environment = e)
        override fun withAltitude(a: Double?) = copy(altitudeM = a)
        override val start: LatLon get() = at
        override val end: LatLon get() = at
        override fun withStart(p: LatLon) = copy(at = p)
        override fun withEnd(p: LatLon) = copy(at = p)
        override fun reroll() = copy(seedOffset = seedOffset + 1)
    }
}

enum class SegmentKind { Route, Indoor, Street, Pause }

fun Segment.kind(): SegmentKind = when (this) {
    is Segment.Route -> SegmentKind.Route
    is Segment.IndoorZone -> SegmentKind.Indoor
    is Segment.StreetZone -> SegmentKind.Street
    is Segment.Pause -> SegmentKind.Pause
}

/** A problem with one segment's shared point (or the segment itself), with the fixes the UI can offer. */
data class Issue(val segment: Int, val which: End, val kind: IssueKind) {
    enum class End { Start, EndPoint, Whole }
}

enum class IssueKind {
    /** Route without a B yet (zero length). */
    RouteIncomplete,
    /** Zone shape missing/invalid. */
    ZoneIncomplete,
    /** Indoor entry/exit isn't on the zone's edge → fix: snap to edge, or insert a connector route. */
    NotOnEdge,
    /** Street-zone A/B outside the polygon → same fixes. */
    NotInside,
}

/**
 * Ordered segments starting at [anchor] (the first A: the real location by default, or moved by the user).
 * Invariant (enforced by every operation): segments[0].start == anchor and segments[i].end == segments[i+1].start.
 * Immutable; each edit returns a new Timeline.
 */
@Serializable
data class Timeline(
    val name: String = "Timeline",
    val anchor: LatLon,
    val anchorIsRealLocation: Boolean = true,
    val segments: List<Segment> = emptyList(),
    /** Mixed into every segment's seed; "Re-roll all" changes it. */
    val seedBase: Long = 1,
) {
    val size: Int get() = segments.size

    /** Boundary k (0..n): k = 0 is the anchor, k = i+1 is the end of segment i. */
    fun boundary(k: Int): LatLon = if (k == 0) anchor else segments[k - 1].end

    fun boundaries(): List<LatLon> = List(size + 1) { boundary(it) }

    /** Re-establishes the chain front to back: each start := previous end (a Pause moves with it). */
    fun normalized(): Timeline {
        var prev = anchor
        val out = segments.map { s ->
            val t = if (s.start == prev) s else s.withStart(prev)
            prev = t.end
            t
        }
        return copy(segments = out)
    }

    /**
     * Moves boundary [k] to [p]. Both neighbours change (end of k−1, start of k), and because a Pause is a single
     * point, a run of pauses around the boundary moves with it (their boundaries are the same point).
     */
    fun setBoundary(k: Int, p: LatLon, fromRealLocation: Boolean = false): Timeline {
        require(k in 0..size) { "boundary $k out of 0..$size" }
        // The run of boundaries tied together by pauses: [lo, hi].
        var lo = k
        while (lo > 0 && segments[lo - 1] is Segment.Pause) lo--
        var hi = k
        while (hi < size && segments[hi] is Segment.Pause) hi++
        val segs = segments.toMutableList()
        for (b in lo..hi) {
            if (b > 0) segs[b - 1] = segs[b - 1].withEnd(p)
            if (b < size) segs[b] = segs[b].withStart(p)
        }
        val newAnchor = if (lo == 0) p else anchor
        return copy(
            anchor = newAnchor,
            anchorIsRealLocation = if (lo == 0) fromRealLocation else anchorIsRealLocation,
            segments = segs,
        ).normalized()
    }

    fun setAnchor(p: LatLon, isRealLocation: Boolean) = setBoundary(0, p, isRealLocation)

    /** Appends a new segment of [kind] starting where the timeline ends. */
    fun add(kind: SegmentKind, id: String, profile: RoutingProfile = RoutingProfile.Walk): Timeline {
        val p = boundary(size)
        val seg: Segment = when (kind) {
            SegmentKind.Route -> Segment.Route(id, p, p, profile)
            // Indoor: the entry point is the shape's first corner, so it is on the edge whatever the user draws next.
            // Street: the entry may be anywhere inside, so the corners are left entirely to the user (the editor only
            // lets the shape close when the entry is inside it or on its edge).
            SegmentKind.Indoor -> Segment.IndoorZone(id, p, p, ZoneDefinition(vertices = listOf(p), entry = p, exit = p, kind = ZoneKind.Indoor))
            SegmentKind.Street -> Segment.StreetZone(id, p, p, ZoneDefinition(entry = p, exit = p, kind = ZoneKind.Streets, profile = profile))
            SegmentKind.Pause -> Segment.Pause(id, p)
        }
        return copy(segments = segments + seg).normalized()
    }

    fun insert(index: Int, seg: Segment): Timeline {
        val i = index.coerceIn(0, size)
        val list = segments.toMutableList().also { it.add(i, seg.withStart(boundary(i))) }
        // The inserted segment's end becomes the next start.
        return copy(segments = list).normalized()
    }

    fun remove(index: Int): Timeline {
        if (index !in segments.indices) return this
        return copy(segments = segments.filterIndexed { i, _ -> i != index }).normalized()
    }

    /** Reorders; the chain is then re-linked (each start := previous end). */
    fun move(from: Int, to: Int): Timeline {
        if (from !in segments.indices) return this
        val list = segments.toMutableList()
        val s = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), s)
        return copy(segments = list).normalized()
    }

    /**
     * Replaces segment [index] with an edited version: its start and end are applied as boundary moves, so the
     * neighbours follow (previous end, next start) — the point stays shared.
     */
    fun replace(index: Int, seg: Segment): Timeline {
        if (index !in segments.indices) return this
        val list = segments.toMutableList().also { it[index] = seg }
        return copy(segments = list).setBoundary(index, seg.start).setBoundary(index + 1, seg.end)
    }

    fun rerollAll(newBase: Long) = copy(seedBase = newBase)

    // ---- validation & fixes ------------------------------------------------------------------

    fun issues(): List<Issue> {
        val out = mutableListOf<Issue>()
        segments.forEachIndexed { i, s ->
            when (s) {
                is Segment.Route -> if (s.waypoints.isEmpty() && GeoMath.haversineMeters(s.start, s.end) < 1.0) out += Issue(i, Issue.End.Whole, IssueKind.RouteIncomplete)
                is Segment.IndoorZone -> zoneIssues(i, s.zone, s.start, s.end, indoor = true, out)
                is Segment.StreetZone -> zoneIssues(i, s.zone, s.start, s.end, indoor = false, out)
                is Segment.Pause -> Unit
            }
        }
        return out
    }

    private fun zoneIssues(i: Int, z: ZoneDefinition, a: LatLon, b: LatLon, indoor: Boolean, out: MutableList<Issue>) {
        val f = z.frame()
        val poly = f?.let { z.polygon(it) }
        if (!z.closed || f == null || poly == null) {
            out += Issue(i, Issue.End.Whole, IssueKind.ZoneIncomplete)
            return
        }
        for ((p, which) in listOf(a to Issue.End.Start, b to Issue.End.EndPoint)) {
            val local = f.toLocal(p)
            if (indoor) {
                if (poly.distanceToBoundary(local) > EDGE_TOLERANCE_M) out += Issue(i, which, IssueKind.NotOnEdge)
            } else {
                if (!poly.contains(local, EDGE_TOLERANCE_M)) out += Issue(i, which, IssueKind.NotInside)
            }
        }
    }

    /** Fix: move the offending shared point onto the zone's edge (both neighbours follow). */
    fun snapToEdge(issue: Issue): Timeline {
        val s = segments.getOrNull(issue.segment) ?: return this
        val z = when (s) {
            is Segment.IndoorZone -> s.zone
            is Segment.StreetZone -> s.zone
            else -> return this
        }
        val f = z.frame() ?: return this
        val poly = z.polygon(f) ?: return this
        val p = if (issue.which == Issue.End.Start) s.start else s.end
        val snapped = ZonePlanner.snapToEdge(poly, f, p)
        val k = if (issue.which == Issue.End.Start) issue.segment else issue.segment + 1
        return setBoundary(k, snapped)
    }

    /**
     * Fix: keep the outside point and insert a connector ROUTE between it and the zone's edge (before the zone for
     * its start, after it for its end).
     */
    fun insertConnector(issue: Issue, id: String): Timeline {
        val s = segments.getOrNull(issue.segment) ?: return this
        val z = when (s) {
            is Segment.IndoorZone -> s.zone
            is Segment.StreetZone -> s.zone
            else -> return this
        }
        val f = z.frame() ?: return this
        val poly = z.polygon(f) ?: return this
        val profile = (segments.getOrNull(issue.segment - 1) as? Segment.Route)?.profile ?: RoutingProfile.Walk
        return if (issue.which == Issue.End.Start) {
            val outside = s.start
            val edge = ZonePlanner.snapToEdge(poly, f, outside)
            val list = segments.toMutableList()
            list.add(issue.segment, Segment.Route(id, outside, edge, profile, RouteMode.FreeDraw))
            list[issue.segment + 1] = s.withStart(edge)
            copy(segments = list).normalized()
        } else {
            val outside = s.end
            val edge = ZonePlanner.snapToEdge(poly, f, outside)
            val list = segments.toMutableList()
            list[issue.segment] = s.withEnd(edge)
            list.add(issue.segment + 1, Segment.Route(id, edge, outside, profile, RouteMode.FreeDraw))
            copy(segments = list).normalized()
        }
    }

    // ---- Stage 8.1 rules: fixed points, ends-only add/remove, letter labels ----------------------------

    /**
     * A point shared by two steps (one before and one after) is FIXED: nothing may move it. Only the very first
     * point (k = 0) and the very last (k = n) are free. To change a fixed point, delete the steps after it.
     */
    fun isFixed(k: Int): Boolean = k in 1 until size

    /** Is segment [i]'s start / end fixed? */
    fun startFixed(i: Int): Boolean = isFixed(i)
    fun endFixed(i: Int): Boolean = isFixed(i + 1)

    /** Moves a FREE point only (k = 0 or k = n); fixed points are left untouched. */
    fun moveFreePoint(k: Int, p: LatLon, fromRealLocation: Boolean = false): Timeline =
        if (isFixed(k) || k !in 0..size) this else setBoundary(k, p, fromRealLocation)

    /**
     * Replaces segment [i] with an edited version, but its fixed points stay where they are (the editor may have
     * snapped them; that must not move the neighbours). Free ends are applied.
     */
    fun replaceKeepingFixed(i: Int, seg: Segment): Timeline {
        if (i !in segments.indices) return this
        var s = seg
        if (startFixed(i)) s = s.withStart(boundary(i))
        if (endFixed(i)) s = s.withEnd(boundary(i + 1))
        return replace(i, s)
    }

    /** Adds a step BEFORE the first one: it ends at the current first point (now fixed) and starts there too. */
    fun addAtStart(kind: SegmentKind, id: String, profile: RoutingProfile = RoutingProfile.Walk): Timeline {
        if (size == 0) return add(kind, id, profile)
        val p = anchor
        val seg: Segment = when (kind) {
            SegmentKind.Route -> Segment.Route(id, p, p, profile)
            SegmentKind.Indoor -> Segment.IndoorZone(id, p, p, ZoneDefinition(vertices = listOf(p), entry = p, exit = p, kind = ZoneKind.Indoor))
            SegmentKind.Street -> Segment.StreetZone(id, p, p, ZoneDefinition(entry = p, exit = p, kind = ZoneKind.Streets, profile = profile))
            SegmentKind.Pause -> Segment.Pause(id, p)
        }
        return copy(segments = listOf(seg) + segments, anchorIsRealLocation = false).normalized()
    }

    /** Deletes the first step; its end becomes the new first point (free). */
    fun removeFirst(): Timeline = if (size == 0) this else copy(
        anchor = segments[0].end,
        anchorIsRealLocation = size == 1 && anchorIsRealLocation,
        segments = segments.drop(1),
    ).normalized()

    /** Deletes the last step; its start becomes the new last point (free). */
    fun removeLast(): Timeline = if (size == 0) this else copy(segments = segments.dropLast(1)).normalized()

    /** Fixes are only offered when they don't move a fixed point or insert a step in the middle. */
    fun canSnap(issue: Issue): Boolean = when (issue.which) {
        Issue.End.Start -> !startFixed(issue.segment)
        Issue.End.EndPoint -> !endFixed(issue.segment)
        Issue.End.Whole -> false
    }

    fun canInsertConnector(issue: Issue): Boolean =
        (issue.which == Issue.End.Start && issue.segment == 0) || (issue.which == Issue.End.EndPoint && issue.segment == size - 1)

    /** True if every boundary is shared exactly (the invariant). */
    fun chainOk(): Boolean {
        if (segments.isEmpty()) return true
        if (segments[0].start != anchor) return false
        for (i in 0 until size - 1) if (segments[i].end != segments[i + 1].start) return false
        return segments.all { it !is Segment.Pause || it.start == it.end }
    }

    companion object {
        const val EDGE_TOLERANCE_M = 0.5

        /** A, B, …, Z, AA, AB, … (spreadsheet-style) for point k. */
        fun pointLabel(k: Int): String {
            var n = k
            val sb = StringBuilder()
            do {
                sb.append('A' + n % 26)
                n = n / 26 - 1
            } while (n >= 0)
            return sb.reverse().toString()
        }
    }
}
