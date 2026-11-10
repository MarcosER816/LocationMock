package com.mockroute.app.ui

import com.mockroute.core.geo.LatLon
import com.mockroute.core.zone.GaussianMixture2D
import com.mockroute.core.zone.LocalFrame
import com.mockroute.core.zone.Polygon2
import com.mockroute.core.zone.StoredComponent
import com.mockroute.core.zone.WanderMode
import com.mockroute.core.zone.ZoneDefinition
import com.mockroute.core.zone.ZonePlanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ZoneTool { Draw, Entry, Exit, Hotspots }

data class ZoneEditorState(
    val def: ZoneDefinition = ZoneDefinition(),
    val tool: ZoneTool = ZoneTool.Draw,
    val selected: Int = 0,
) {
    val frame: LocalFrame? get() = def.frame()
    /**
     * Why the outline can't be closed, or null if it can. (0.6.0 bug: written as `frame?.let { validate(…) } ?: TooFewVertices`,
     * so a VALID shape — validate() == null — fell through the elvis and read "needs at least 3 corners".)
     */
    val problem: Polygon2.Companion.Problem?
        get() {
            val f = frame ?: return Polygon2.Companion.Problem.TooFewVertices
            return Polygon2.validate(def.vertices.map(f::toLocal))
        }
    val polygon: Polygon2? get() = frame?.let { def.polygon(it) }

    /** The mixture as stored components (materialises the default when the list is empty). */
    fun components(): List<StoredComponent> {
        if (def.components.isNotEmpty()) return def.components
        val f = frame ?: return emptyList()
        val p = polygon ?: return emptyList()
        val c = GaussianMixture2D.default(p).components[0]
        return listOf(StoredComponent(1.0, f.toLatLon(com.mockroute.core.zone.Vec2(c.meanX, c.meanY)), c.sigmaX, c.sigmaY, 0.0))
    }
}

/** Polygon tool, entry/exit snapping and the Gaussian-mixture ("hotspots") editor for the INDOOR zone. */
class ZoneEditor(initial: ZoneDefinition = ZoneDefinition()) {
    companion object {
        const val MIN_CORNER_SPACING_M = 1.5
        const val STREET_EDGE_TOLERANCE_M = 0.5
    }

    private val _state = MutableStateFlow(ZoneEditorState(initial, if (initial.closed) ZoneTool.Hotspots else ZoneTool.Draw))
    val state: StateFlow<ZoneEditorState> = _state.asStateFlow()

    fun load(def: ZoneDefinition) = _state.update { ZoneEditorState(def, if (def.closed) ZoneTool.Hotspots else ZoneTool.Draw) }

    /** Timeline: entry/exit shared with a neighbouring step are fixed — never snapped, dragged or re-placed. */
    @Volatile var lockEntry: Boolean = false
    @Volatile var lockExit: Boolean = false

    /** The first corner is the fixed entry (it guarantees the entry is on the edge) → it can't be moved/undone either. */
    private fun firstCornerLocked(d: ZoneDefinition) = lockEntry && d.vertices.isNotEmpty() && d.vertices.first() == d.entry

    fun setTool(t: ZoneTool) = _state.update { it.copy(tool = t) }

    fun onMapTap(p: LatLon) {
        val s = _state.value
        when (s.tool) {
            ZoneTool.Draw -> if (!s.def.closed) {
                // Ignore a tap on (≈ the same spot as) the previous corner: a double tap must not duplicate it.
                val last = s.def.vertices.lastOrNull()
                val tooClose = last != null && com.mockroute.core.geo.GeoMath.haversineMeters(last, p) < MIN_CORNER_SPACING_M
                if (!tooClose) update { it.copy(vertices = it.vertices + p) }
            }
            ZoneTool.Entry -> if (!lockEntry) snap(p)?.let { q -> update { it.copy(entry = q) } }
            ZoneTool.Exit -> if (!lockExit) snap(p)?.let { q -> update { it.copy(exit = q) } }
            ZoneTool.Hotspots -> moveComponent(s.selected, p)
        }
    }

    /** Stage 7: snaps a point to the nearest road inside the zone (set by the ViewModel when roads are loaded). */
    @Volatile var roadSnapper: ((LatLon) -> LatLon?)? = null

    /**
     * Indoor: nearest point of the polygon's edge (A/B never inside). Streets: A/B may be inside or on the edge —
     * outside points are pulled onto the edge first — and then onto the nearest road when roads are loaded.
     */
    fun snap(p: LatLon): LatLon? {
        val s = _state.value
        val f = s.frame ?: return null
        val poly = s.polygon ?: return null
        if (s.def.kind == com.mockroute.core.zone.ZoneKind.Indoor) return ZonePlanner.snapToEdge(poly, f, p)
        val local = f.toLocal(p)
        val inside = if (poly.contains(local, 0.0)) p else ZonePlanner.snapToEdge(poly, f, p)
        // The road snap must never take the point OUTSIDE the zone (e.g. a road graph left over from another
        // zone, or a road just beyond the edge): if it would, keep the inside/edge point.
        val onRoad = roadSnapper?.invoke(inside)
        return if (onRoad != null && poly.contains(f.toLocal(onRoad), STREET_EDGE_TOLERANCE_M)) onRoad else inside
    }

    /** Called with a short explanation when an edit is refused (shown to the user). */
    @Volatile var onRefused: ((String) -> Unit)? = null

    /**
     * Street zones: entry and exit must be inside the zone or on its edge, never outside. Free points are pulled
     * in by [snap]; FIXED points can't move, so a shape that leaves one outside is refused. Returns the letter-less
     * name of the offending point, or null if the shape is fine.
     */
    private fun fixedPointLeftOutside(d: ZoneDefinition): String? {
        if (d.kind != com.mockroute.core.zone.ZoneKind.Streets) return null
        val f = d.frame() ?: return null
        val poly = d.polygon(f) ?: return null
        fun outside(p: LatLon?) = p != null && !poly.contains(f.toLocal(p), STREET_EDGE_TOLERANCE_M)
        return when {
            lockEntry && outside(d.entry) -> "start"
            lockExit && outside(d.exit) -> "end"
            else -> null
        }
    }

    fun setKind(kind: com.mockroute.core.zone.ZoneKind) {
        update { it.copy(kind = kind) }
        resnapEndpoints()
    }

    fun setProfile(profile: com.mockroute.core.route.RoutingProfile) = update { it.copy(profile = profile) }

    fun setRoadsPolygon(v: List<LatLon>) = update { it.copy(roadsPolygon = v) }

    /**
     * Re-snap A/B after the shape, kind or roads changed. Only moves a point by > 0.5 m: re-snapping an already
     * snapped point must not change the definition (that would trigger re-planning → re-snapping → … forever).
     */
    fun resnapEndpoints() {
        fun moved(p: LatLon?, locked: Boolean): LatLon? {
            if (locked) return p
            val q = p?.let(::snap) ?: return p
            return if (com.mockroute.core.geo.GeoMath.haversineMeters(p, q) > 0.5) q else p
        }
        val d = _state.value.def
        val e = moved(d.entry, lockEntry)
        val x = moved(d.exit, lockExit)
        if (e != d.entry || x != d.exit) update { it.copy(entry = e, exit = x) }
    }

    /** A tap (not a drag) on a handle: the FIRST corner closes the shape; other corners do nothing. */
    fun onHandleTap(id: String): String? {
        val s = _state.value
        if (id == "v0" && !s.def.closed && s.def.vertices.size >= 3) return close()
        return null
    }

    fun undoVertex() = update { if (it.closed || (firstCornerLocked(it) && it.vertices.size <= 1)) it else it.copy(vertices = it.vertices.dropLast(1)) }

    /** Closes the shape; returns a user message if it can't be closed. */
    fun close(): String? {
        when (_state.value.problem) {
            null -> Unit
            Polygon2.Companion.Problem.TooFewVertices -> return "Add at least 3 corners first."
            Polygon2.Companion.Problem.TooSmall -> return "The shape is too small (needs ≥ 4 m²)."
            Polygon2.Companion.Problem.SelfIntersecting -> return "The outline crosses itself: drag a corner (or Undo) so the edges don't cross."
        }
        fixedPointLeftOutside(_state.value.def.copy(closed = true))?.let {
            return "The zone's $it point is fixed and would be outside this shape. Draw the shape around it (or with it on the edge)."
        }
        update { it.copy(closed = true) }
        // Re-snap A/B to the new outline; materialise the default hotspot so it can be edited.
        val s = _state.value
        _state.update {
            it.copy(
                def = it.def.copy(
                    entry = if (lockEntry) it.def.entry else it.def.entry?.let(::snap),
                    exit = if (lockExit) it.def.exit else it.def.exit?.let(::snap),
                    components = s.components(),
                ),
                tool = if (it.def.entry == null) ZoneTool.Entry else ZoneTool.Hotspots,
            )
        }
        return null
    }

    fun reopen() = _state.update { it.copy(def = it.def.copy(closed = false), tool = ZoneTool.Draw) }

    fun clear() = _state.update {
        if (lockEntry || lockExit) {
            // Keep the fixed points (and the fixed first corner).
            val d = it.def
            ZoneEditorState(ZoneDefinition(vertices = if (firstCornerLocked(d)) d.vertices.take(1) else emptyList(), entry = d.entry, exit = d.exit, kind = d.kind, profile = d.profile))
        } else ZoneEditorState()
    }

    /** Drag of a handle: "v<i>" vertex, "entry", "exit", "c<i>" hotspot mean. */
    fun drag(id: String, p: LatLon) {
        when {
            id == "entry" -> if (!lockEntry) snap(p)?.let { q -> update { it.copy(entry = q) } }
            id == "exit" -> if (!lockExit) snap(p)?.let { q -> update { it.copy(exit = q) } }
            id.startsWith("v") -> {
                val i = id.drop(1).toIntOrNull() ?: return
                if (i == 0 && firstCornerLocked(_state.value.def)) return
                val cur = _state.value.def
                if (i !in cur.vertices.indices) return
                val moved = cur.copy(vertices = cur.vertices.toMutableList().also { it[i] = p })
                // A closed street zone must keep its fixed start/end inside (or on the edge).
                if (cur.closed) fixedPointLeftOutside(moved)?.let {
                    onRefused?.invoke("Can't move that corner: the zone's fixed $it point would be outside the zone.")
                    return
                }
                update { moved }
                // Keep the free A/B on the (moved) edge; fixed ones stay put.
                update {
                    it.copy(
                        entry = if (lockEntry) it.entry else it.entry?.let(::snap) ?: it.entry,
                        exit = if (lockExit) it.exit else it.exit?.let(::snap) ?: it.exit,
                    )
                }
            }
            id.startsWith("c") -> id.drop(1).toIntOrNull()?.let { moveComponent(it, p) }
        }
    }

    // ---- hotspots (mixture components) ----------------------------------------------------------

    fun select(i: Int) = _state.update { it.copy(selected = i) }

    fun addComponent() {
        val s = _state.value
        val comps = s.components()
        val base = comps.getOrNull(s.selected) ?: comps.firstOrNull() ?: return
        val f = s.frame ?: return
        val m = f.toLocal(base.mean)
        val shifted = f.toLatLon(com.mockroute.core.zone.Vec2(m.x + base.sigmaXM, m.y))
        update { it.copy(components = comps + base.copy(mean = shifted, weight = 0.5)) }
        _state.update { it.copy(selected = comps.size) }
    }

    fun removeComponent(i: Int) {
        val comps = _state.value.components()
        if (comps.size <= 1 || i !in comps.indices) return
        update { it.copy(components = comps.filterIndexed { j, _ -> j != i }) }
        _state.update { it.copy(selected = 0) }
    }

    fun updateComponent(i: Int, f: (StoredComponent) -> StoredComponent) {
        val comps = _state.value.components()
        if (i !in comps.indices) return
        update { it.copy(components = comps.toMutableList().also { l -> l[i] = f(l[i]) }) }
    }

    private fun moveComponent(i: Int, p: LatLon) = updateComponent(i) { it.copy(mean = p) }

    fun setDwellProbability(v: Double) = update { it.copy(dwellProbability = v.coerceIn(0.0, 1.0)) }
    fun setDwellMean(v: Double) = update { it.copy(dwellMeanS = v.coerceIn(1.0, 600.0)) }
    fun setWobble(on: Boolean) = update { it.copy(wobble = on) }
    fun setMode(m: WanderMode) = update { it.copy(mode = m) }

    private fun update(f: (ZoneDefinition) -> ZoneDefinition) = _state.update { it.copy(def = f(it.def)) }
}
