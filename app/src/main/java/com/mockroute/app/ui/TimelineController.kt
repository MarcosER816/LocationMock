package com.mockroute.app.ui

import com.mockroute.app.data.AppSettings
import com.mockroute.app.data.SettingsRepository
import com.mockroute.app.data.TimelineStore
import com.mockroute.app.net.OverpassClient
import com.mockroute.app.net.RoutingService
import com.mockroute.app.net.TomTomTrafficProvider
import com.mockroute.app.net.TrafficDiskCache
import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.route.RouteResult
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.streets.Overpass
import com.mockroute.core.streets.RoadGraph
import com.mockroute.core.timeline.Issue
import com.mockroute.core.timeline.RouteMode
import com.mockroute.core.timeline.Segment
import com.mockroute.core.timeline.SegmentKind
import com.mockroute.core.timeline.Timeline
import com.mockroute.core.timeline.TimelinePlan
import com.mockroute.core.timeline.TimelinePlanner
import com.mockroute.core.timeline.TimelineSources
import com.mockroute.core.traffic.TrafficMode
import com.mockroute.core.traffic.TrafficPlanner
import com.mockroute.core.zone.LocalFrame
import com.mockroute.core.zone.Polygon2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

sealed interface TimelinePlanState {
    data object Idle : TimelinePlanState
    data class Planning(val segment: Int, val of: Int) : TimelinePlanState
    data class Planned(val plan: TimelinePlan) : TimelinePlanState
}

/** The current timeline: edits (chain enforced in :core), persistence, planning with real sources, playback spec. */
@OptIn(FlowPreview::class)
class TimelineController(
    private val scope: CoroutineScope,
    private val repo: SettingsRepository,
    val store: TimelineStore,
    private val routing: RoutingService,
    private val overpass: OverpassClient,
    private val trafficCache: TrafficDiskCache,
    private val settings: () -> AppSettings?,
    private val realLocation: () -> LatLon?,
) {
    private val _timeline = MutableStateFlow(Timeline(anchor = LatLon(0.0, 0.0)))
    val timeline: StateFlow<Timeline> = _timeline.asStateFlow()
    private val _plan = MutableStateFlow<TimelinePlanState>(TimelinePlanState.Idle)
    val plan: StateFlow<TimelinePlanState> = _plan.asStateFlow()
    private val _saved = MutableStateFlow<List<TimelineStore.Entry>>(emptyList())
    val saved: StateFlow<List<TimelineStore.Entry>> = _saved.asStateFlow()
    private var planJob: Job? = null

    init {
        scope.launch {
            store.loadCurrent()?.let { _timeline.value = it }
            if (_timeline.value.anchor == LatLon(0.0, 0.0)) realLocation()?.let { _timeline.value = _timeline.value.setAnchor(it, true) }
            refreshSaved()
            _timeline.drop(1).debounce(500).collect { store.saveCurrent(it) }
        }
    }

    fun refreshSaved() = scope.launch { _saved.value = store.list() }

    private fun edit(f: (Timeline) -> Timeline) {
        _timeline.value = f(_timeline.value)
    }

    private fun newId() = UUID.randomUUID().toString().take(8)

    // ---- edits ----------------------------------------------------------------------------------

    fun add(kind: SegmentKind) = edit {
        // Before the first segment, (re)capture the real location as the first A if that's what A is meant to be.
        val base = if (it.size == 0 && it.anchorIsRealLocation) realLocation()?.let { p -> it.setAnchor(p, true) } ?: it else it
        val prof = (base.segments.lastOrNull() as? Segment.Route)?.profile ?: RoutingProfile.Walk
        base.add(kind, newId(), prof)
    }
    fun remove(i: Int) = edit { it.remove(i) }
    fun move(from: Int, to: Int) = edit { it.move(from, to) }
    fun replace(i: Int, s: Segment) = edit { it.replace(i, s) }
    fun setBoundary(k: Int, p: LatLon) = edit { it.setBoundary(k, p) }
    fun rename(n: String) = edit { it.copy(name = n) }
    fun reroll(i: Int) = edit { t -> t.segments.getOrNull(i)?.let { t.replace(i, it.reroll()) } ?: t }
    fun rerollAll() = edit { it.rerollAll(System.nanoTime()) }
    fun fixSnap(issue: Issue) = edit { it.snapToEdge(issue) }
    fun fixConnector(issue: Issue) = edit { it.insertConnector(issue, newId()) }

    /** First A = the real location (captured now, before any mocking). */
    fun anchorToRealLocation() {
        realLocation()?.let { p -> edit { it.setAnchor(p, isRealLocation = true) } }
    }

    fun newTimeline() {
        _timeline.value = Timeline(anchor = realLocation() ?: _timeline.value.anchor)
        _plan.value = TimelinePlanState.Idle
    }

    fun load(t: Timeline) {
        _timeline.value = t
        _plan.value = TimelinePlanState.Idle
    }

    // ---- planning ---------------------------------------------------------------------------------

    /** Plans every segment in order (start time = now + 1 min + previous durations). */
    fun planAll() {
        planJob?.cancel()
        val t = _timeline.value
        planJob = scope.launch {
            _plan.value = TimelinePlanState.Planning(0, t.size)
            val start = System.currentTimeMillis() / 1000 + 60
            val result = withContext(Dispatchers.Default) {
                TimelinePlanner.plan(t, start, sources()) { i -> _plan.value = TimelinePlanState.Planning(i, t.size) }
            }
            _plan.value = TimelinePlanState.Planned(result)
        }
    }

    private fun sources() = object : TimelineSources {
        override suspend fun routeGeometry(seg: Segment.Route): RouteGeometry {
            val s = settings() ?: AppSettings()
            val snap = seg.mode != RouteMode.FreeDraw
            val pts = seg.inputs
            if (seg.mode == RouteMode.PointToPoint) return routing.route(listOf(seg.start, seg.end), seg.profile, snap, s).geometry
            // Street-by-street / free draw: one leg per consecutive pair, concatenated.
            val legs = pts.zipWithNext().map { (a, b) -> routing.route(listOf(a, b), seg.profile, snap, s) }
            return RouteResult.concat(legs, seg.profile)?.geometry ?: RouteGeometry.of(pts)
        }

        override suspend fun roadGraph(seg: Segment.StreetZone, frame: LocalFrame, polygon: Polygon2): RoadGraph? {
            val z = seg.zone
            val poly = if (z.roadsPolygon.size >= 3) z.roadsPolygon else z.vertices
            val q = Overpass.query(poly)
            val body = overpass.cached(q)?.first ?: overpass.fetch(Overpass.query(z.vertices), overpassToday()) {
                repo.countOverpassRequest(LocalDate.now().toString())
            }
            val ways = Overpass.parse(body)
            return RoadGraph.build(ways, polygon, frame, z.profile)
        }

        override suspend fun trafficMedian(seg: Segment.Route, geometry: RouteGeometry, departEpochS: Long): ((Double) -> Double)? {
            if (seg.profile == RoutingProfile.Walk) return null
            val key = settings()?.tomTomApiKey.orEmpty()
            if (key.isBlank()) return null
            val zone = ZoneId.systemDefault()
            val provider = TomTomTrafficProvider(key, zone) { repo.countTrafficRequest(LocalDate.now(zone).toString()) }
            val mode = if (seg.profile == RoutingProfile.Bus) TrafficMode.Bus else TrafficMode.Car
            return runCatching {
                val a = TrafficPlanner.analyze(geometry, departEpochS, mode, provider, trafficCache, System.currentTimeMillis() / 1000, zone)
                val f: (Double) -> Double = { s -> a.medianSpeedAt(s) }
                f
            }.getOrNull()
        }
    }

    private fun overpassToday(): Int {
        val s = settings() ?: return 0
        return if (s.overpassRequestDay == LocalDate.now().toString()) s.overpassRequestCount else 0
    }

    // ---- save / load ------------------------------------------------------------------------------

    suspend fun saveAs(name: String): String {
        edit { it.copy(name = name) }
        val n = store.save(_timeline.value)
        refreshSaved()
        return n
    }

    suspend fun loadSaved(name: String) {
        load(store.load(name))
    }

    suspend fun deleteSaved(name: String) {
        store.delete(name)
        refreshSaved()
    }
}
