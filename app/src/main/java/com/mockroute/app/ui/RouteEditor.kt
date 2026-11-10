package com.mockroute.app.ui

import com.mockroute.app.data.AppSettings
import com.mockroute.app.net.RoutingService
import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.RouteResult
import com.mockroute.core.route.RoutingProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EditMode { Explore, PointToPoint, Streets, Indoor }

enum class Endpoint { A, B }

/** One street-by-street leg: waypoints[i] → waypoints[i+1]. [result] is null while routing. */
data class Leg(val from: LatLon, val to: LatLon, val snap: Boolean, val result: RouteResult? = null)

data class RouteEditorState(
    val mode: EditMode = EditMode.Explore,
    val profile: RoutingProfile = RoutingProfile.Walk,
    /** Street-by-street: snap new legs to roads (false = free draw). */
    val snap: Boolean = true,
    // Point-to-point
    val a: LatLon? = null,
    val b: LatLon? = null,
    val aIsRealLocation: Boolean = true,
    val pickTarget: Endpoint = Endpoint.B,
    val p2pResult: RouteResult? = null,
    val p2pLoading: Boolean = false,
    // Street-by-street / free draw
    val waypoints: List<LatLon> = emptyList(),
    val legs: List<Leg> = emptyList(),
    val playSpeedKmh: Double = defaultSpeedKmh(RoutingProfile.Walk),
    /** Bumped to force point-to-point re-routing (settings changed / Retry). */
    val revision: Int = 0,
    /** Timeline: A / B shared with a neighbouring step → fixed (taps and drags can't move them). */
    val lockA: Boolean = false,
    val lockB: Boolean = false,
) {
    val loading: Boolean get() = if (mode == EditMode.PointToPoint) p2pLoading else legs.any { it.result == null }

    /** The current segment's route (for streets: all finished legs joined). */
    val result: RouteResult? get() = when (mode) {
        EditMode.PointToPoint -> p2pResult
        EditMode.Streets -> RouteResult.concat(legs.mapNotNull { it.result }, profile)
        EditMode.Explore, EditMode.Indoor -> null
    }

    companion object {
        fun defaultSpeedKmh(p: RoutingProfile): Double = when (p) {
            RoutingProfile.Walk -> 5.0
            RoutingProfile.Car -> 40.0
            RoutingProfile.Bus -> 25.0
        }
    }
}

/**
 * Holds the route being edited and runs routing requests: point-to-point requests are debounced
 * (dragging A/B fires many updates), street legs are routed one request per tap, newest-wins per leg.
 */
@OptIn(FlowPreview::class)
class RouteEditor(
    private val scope: CoroutineScope,
    private val routing: RoutingService,
    private val settings: () -> AppSettings?,
) {
    private val _state = MutableStateFlow(RouteEditorState())
    val state: StateFlow<RouteEditorState> = _state.asStateFlow()

    private val legJobs = mutableMapOf<Int, Job>()

    init {
        // Debounced point-to-point routing.
        scope.launch {
            _state.map { listOf(it.a, it.b, it.profile, it.revision) to (it.mode == EditMode.PointToPoint) }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collectLatest { (_, active) ->
                    val snapshot = _state.value
                    val a = snapshot.a
                    val b = snapshot.b
                    val profile = snapshot.profile
                    if (!active || a == null || b == null) {
                        _state.update { it.copy(p2pResult = null, p2pLoading = false) }
                        return@collectLatest
                    }
                    _state.update { it.copy(p2pLoading = true) }
                    val r = routing.route(listOf(a, b), profile, snap = true, settings = currentSettings())
                    _state.update { s ->
                        if (s.a == a && s.b == b && s.profile == profile) s.copy(p2pResult = r, p2pLoading = false) else s
                    }
                }
        }
    }

    private fun currentSettings() = settings() ?: AppSettings()

    fun setMode(mode: EditMode, realLocation: LatLon?) = _state.update { s ->
        when (mode) {
            EditMode.PointToPoint -> s.copy(
                mode = mode,
                a = if (s.aIsRealLocation || s.a == null) realLocation ?: s.a else s.a,
                pickTarget = Endpoint.B,
            )
            else -> s.copy(mode = mode)
        }
    }

    fun setSnap(snap: Boolean) = _state.update { it.copy(snap = snap, mode = EditMode.Streets) }

    fun setProfile(profile: RoutingProfile) {
        val old = _state.value
        if (old.profile == profile) return
        _state.update { it.copy(profile = profile, playSpeedKmh = RouteEditorState.defaultSpeedKmh(profile)) }
        // Re-route snapped legs for the new profile.
        old.legs.forEachIndexed { i, leg -> if (leg.snap) routeLeg(i) }
    }

    fun setPlaySpeed(kmh: Double) = _state.update { it.copy(playSpeedKmh = kmh.coerceIn(1.0, 100.0)) }

    /** Map tap / long-press while editing. */
    fun onMapTap(p: LatLon) {
        when (_state.value.mode) {
            EditMode.PointToPoint -> setEndpoint(_state.value.pickTarget, p)
            EditMode.Streets -> addWaypoint(p)
            EditMode.Explore, EditMode.Indoor -> Unit
        }
    }

    // ---- point-to-point ----------------------------------------------------------------------

    fun setEndpoint(which: Endpoint, p: LatLon) = _state.update {
        if ((which == Endpoint.A && it.lockA) || (which == Endpoint.B && it.lockB)) return@update it
        when (which) {
            Endpoint.A -> it.copy(a = p, aIsRealLocation = false, pickTarget = Endpoint.B)
            Endpoint.B -> it.copy(b = p, pickTarget = Endpoint.B)
        }
    }

    fun pickNext(which: Endpoint) = _state.update { if (which == Endpoint.A && it.lockA) it else it.copy(pickTarget = which) }

    fun useRealLocationAsA(real: LatLon?) {
        if (real != null) _state.update { if (it.lockA) it else it.copy(a = real, aIsRealLocation = true) }
    }

    fun swapAB() = _state.update { if (it.lockA || it.lockB) it else it.copy(a = it.b, b = it.a, aIsRealLocation = false) }

    // ---- street-by-street ----------------------------------------------------------------------

    fun addWaypoint(p: LatLon) {
        val s = _state.value
        // Fixed end (timeline): new points go BEFORE it, so the route still ends at the fixed point.
        if (s.lockB && s.waypoints.size >= 2) {
            insertBeforeLast(p)
            return
        }
        val last = s.waypoints.lastOrNull()
        if (last == null) {
            _state.update { it.copy(waypoints = listOf(p)) }
            return
        }
        val index = s.legs.size
        _state.update { it.copy(waypoints = it.waypoints + p, legs = it.legs + Leg(last, p, it.snap)) }
        routeLeg(index)
    }

    private fun routeLeg(index: Int) {
        legJobs.remove(index)?.cancel()
        val leg = _state.value.legs.getOrNull(index) ?: return
        _state.update { s -> s.copy(legs = s.legs.toMutableList().also { it[index] = leg.copy(result = null) }) }
        legJobs[index] = scope.launch {
            val r = routing.route(listOf(leg.from, leg.to), _state.value.profile, leg.snap, currentSettings())
            _state.update { s ->
                val cur = s.legs.getOrNull(index)
                if (cur == null || cur.from != leg.from || cur.to != leg.to) s
                else s.copy(legs = s.legs.toMutableList().also { it[index] = cur.copy(result = r) })
            }
            legJobs.remove(index)
        }
    }

    /**
     * Re-routes whatever currently fell back to a straight line (after a key/provider change, or Retry).
     * Successful routes are kept (and are cached anyway).
     */
    fun refresh() {
        _state.update { it.copy(revision = it.revision + 1) }
        _state.value.legs.forEachIndexed { i, leg ->
            if (leg.snap && (leg.result == null || leg.result.isFallback)) routeLeg(i)
        }
    }

    /** Stage 8: load a timeline route segment into the editor (legs re-routed from the cache). */
    fun loadSegment(
        mode: EditMode,
        snap: Boolean,
        a: LatLon,
        b: LatLon,
        waypoints: List<LatLon>,
        profile: RoutingProfile,
        lockA: Boolean = false,
        lockB: Boolean = false,
    ) {
        legJobs.values.forEach { it.cancel() }
        legJobs.clear()
        val hasB = b != a
        when (mode) {
            EditMode.PointToPoint -> _state.value = RouteEditorState(
                mode = mode, profile = profile, a = a, b = b.takeIf { hasB || lockB }, aIsRealLocation = false,
                lockA = lockA, lockB = lockB,
            )
            else -> {
                _state.value = RouteEditorState(mode = EditMode.Streets, profile = profile, snap = snap)
                val pts = (listOf(a) + waypoints + if (hasB || lockB) listOf(b) else emptyList()).distinct()
                pts.forEach { addWaypoint(it) }
                _state.update { it.copy(lockA = lockA, lockB = lockB) }
            }
        }
    }

    /** Stage 8: restore the editor exactly as it was before editing a timeline segment. */
    fun restore(st: RouteEditorState) {
        legJobs.values.forEach { it.cancel() }
        legJobs.clear()
        _state.value = st
    }

    private fun insertBeforeLast(p: LatLon) {
        val s = _state.value
        val end = s.waypoints.last()
        val prev = s.waypoints[s.waypoints.size - 2]
        val k = s.legs.lastIndex
        legJobs.remove(k)?.cancel()
        _state.update {
            it.copy(
                waypoints = it.waypoints.dropLast(1) + p + end,
                legs = it.legs.dropLast(1) + Leg(prev, p, it.snap) + Leg(p, end, it.snap),
            )
        }
        routeLeg(k)
        routeLeg(k + 1)
    }

    fun undo() {
        val s = _state.value
        if (s.lockB && s.waypoints.size > 2) {
            // Remove the last free point before the fixed end; reconnect to the end.
            val end = s.waypoints.last()
            val before = s.waypoints[s.waypoints.size - 3]
            legJobs.remove(s.legs.lastIndex)?.cancel()
            legJobs.remove(s.legs.lastIndex - 1)?.cancel()
            _state.update { it.copy(waypoints = it.waypoints.dropLast(2) + end, legs = it.legs.dropLast(2) + Leg(before, end, it.snap)) }
            routeLeg(_state.value.legs.lastIndex)
            return
        }
        if ((s.lockA && s.waypoints.size <= 1) || (s.lockB && s.waypoints.size <= 2)) return
        if (s.waypoints.isEmpty()) return
        legJobs.remove(s.legs.lastIndex)?.cancel()
        _state.update { it.copy(waypoints = it.waypoints.dropLast(1), legs = it.legs.dropLast(1)) }
    }

    fun clear() {
        val s = _state.value
        if (s.lockA && s.lockB && s.mode == EditMode.Streets && s.waypoints.size >= 2) {
            // Keep the fixed start and end; drop everything in between.
            legJobs.values.forEach { it.cancel() }
            legJobs.clear()
            val a = s.waypoints.first()
            val b = s.waypoints.last()
            _state.update { it.copy(waypoints = listOf(a, b), legs = listOf(Leg(a, b, it.snap))) }
            routeLeg(0)
            return
        }
        legJobs.values.forEach { it.cancel() }
        legJobs.clear()
        _state.update {
            when {
                it.mode == EditMode.PointToPoint -> if (it.lockB) it else it.copy(b = null, p2pResult = null)
                it.lockA -> it.copy(waypoints = it.waypoints.take(1), legs = emptyList())
                else -> it.copy(waypoints = emptyList(), legs = emptyList())
            }
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 400L
    }
}
