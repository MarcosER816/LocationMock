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
    val problem: Polygon2.Companion.Problem? get() = frame?.let { f -> Polygon2.validate(def.vertices.map(f::toLocal)) } ?: Polygon2.Companion.Problem.TooFewVertices
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
    private val _state = MutableStateFlow(ZoneEditorState(initial, if (initial.closed) ZoneTool.Hotspots else ZoneTool.Draw))
    val state: StateFlow<ZoneEditorState> = _state.asStateFlow()

    fun load(def: ZoneDefinition) = _state.update { ZoneEditorState(def, if (def.closed) ZoneTool.Hotspots else ZoneTool.Draw) }

    fun setTool(t: ZoneTool) = _state.update { it.copy(tool = t) }

    fun onMapTap(p: LatLon) {
        val s = _state.value
        when (s.tool) {
            ZoneTool.Draw -> if (!s.def.closed) update { it.copy(vertices = it.vertices + p) }
            ZoneTool.Entry -> snap(p)?.let { q -> update { it.copy(entry = q) } }
            ZoneTool.Exit -> snap(p)?.let { q -> update { it.copy(exit = q) } }
            ZoneTool.Hotspots -> moveComponent(s.selected, p)
        }
    }

    /** Snap to the nearest point of the closed polygon's edge (A/B are never inside). */
    fun snap(p: LatLon): LatLon? {
        val s = _state.value
        val f = s.frame ?: return null
        val poly = s.polygon ?: return null
        return ZonePlanner.snapToEdge(poly, f, p)
    }

    fun undoVertex() = update { if (it.closed) it else it.copy(vertices = it.vertices.dropLast(1)) }

    fun close() {
        if (_state.value.problem != null) return
        update { it.copy(closed = true) }
        // Re-snap A/B to the new outline; materialise the default hotspot so it can be edited.
        val s = _state.value
        _state.update {
            it.copy(
                def = it.def.copy(
                    entry = it.def.entry?.let(::snap),
                    exit = it.def.exit?.let(::snap),
                    components = s.components(),
                ),
                tool = if (it.def.entry == null) ZoneTool.Entry else ZoneTool.Hotspots,
            )
        }
    }

    fun reopen() = _state.update { it.copy(def = it.def.copy(closed = false), tool = ZoneTool.Draw) }

    fun clear() = _state.update { ZoneEditorState() }

    /** Drag of a handle: "v<i>" vertex, "entry", "exit", "c<i>" hotspot mean. */
    fun drag(id: String, p: LatLon) {
        when {
            id == "entry" -> snap(p)?.let { q -> update { it.copy(entry = q) } }
            id == "exit" -> snap(p)?.let { q -> update { it.copy(exit = q) } }
            id.startsWith("v") -> {
                val i = id.drop(1).toIntOrNull() ?: return
                update { d ->
                    if (i !in d.vertices.indices) d else d.copy(vertices = d.vertices.toMutableList().also { it[i] = p })
                }
                // Keep A/B on the (moved) edge.
                update { it.copy(entry = it.entry?.let(::snap) ?: it.entry, exit = it.exit?.let(::snap) ?: it.exit) }
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
