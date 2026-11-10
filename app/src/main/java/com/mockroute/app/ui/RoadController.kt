package com.mockroute.app.ui

import com.mockroute.app.data.SettingsRepository
import com.mockroute.app.net.OverpassClient
import com.mockroute.app.net.OverpassException
import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.streets.Overpass
import com.mockroute.core.streets.OsmWay
import com.mockroute.core.streets.RoadGraph
import com.mockroute.core.zone.LocalFrame
import com.mockroute.core.zone.Polygon2
import com.mockroute.core.zone.ZoneDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

sealed interface RoadsStatus {
    data object None : RoadsStatus
    data object Loading : RoadsStatus
    /** Raw ways for [queriedPolygon] (clipped to the current shape when graphs are built). */
    data class Ready(val ways: List<OsmWay>, val queriedPolygon: List<LatLon>, val savedAtMs: Long, val fromCache: Boolean) : RoadsStatus
    data class Error(val message: String) : RoadsStatus
}

/**
 * Street-zone roads: ONE Overpass query per zone (all road classes for walk + car), cached 30 days. Opening a zone
 * whose roads are cached makes 0 requests; only "Refresh roads" (or a first load) queries. Graphs are built per
 * (shape, profile) on Dispatchers.Default and memoised.
 */
class RoadController(
    private val scope: CoroutineScope,
    private val repo: SettingsRepository,
    private val client: OverpassClient,
    private val onQueried: (List<LatLon>) -> Unit,
) {
    private val _status = MutableStateFlow<RoadsStatus>(RoadsStatus.None)
    val status: StateFlow<RoadsStatus> = _status.asStateFlow()
    private val mutex = Mutex()

    private var graphKey: Triple<List<LatLon>, RoutingProfile, Int>? = null
    private var graph: RoadGraph? = null

    /** Load roads for [def]: from the cache if possible; from Overpass only if [force] or nothing is cached. */
    fun load(def: ZoneDefinition, force: Boolean, requestsToday: Int) {
        if (def.vertices.size < 3 || !def.closed) return
        scope.launch {
            mutex.withLock {
                val polygon = if (!force && def.roadsPolygon.size >= 3) def.roadsPolygon else def.vertices
                val query = Overpass.query(polygon)
                if (!force) {
                    client.cached(query)?.let { (body, at) ->
                        _status.value = parse(body, polygon, at, fromCache = true)
                        return@withLock
                    }
                    if (def.roadsPolygon.isNotEmpty() && _status.value is RoadsStatus.Ready) return@withLock
                }
                _status.value = RoadsStatus.Loading
                _status.value = try {
                    val body = client.fetch(Overpass.query(def.vertices), requestsToday) {
                        repo.countOverpassRequest(LocalDate.now().toString())
                    }
                    onQueried(def.vertices)
                    parse(body, def.vertices, System.currentTimeMillis(), fromCache = false)
                } catch (e: OverpassException) {
                    RoadsStatus.Error(e.userMessage)
                }
            }
        }
    }

    private suspend fun parse(body: String, polygon: List<LatLon>, at: Long, fromCache: Boolean): RoadsStatus =
        withContext(Dispatchers.Default) {
            runCatching { RoadsStatus.Ready(Overpass.parse(body), polygon, at, fromCache) }
                .getOrElse { RoadsStatus.Error("Couldn't read the Overpass answer: ${it.javaClass.simpleName}") }
        }

    /** Road graph for the current shape and profile (clipped from the cached ways); null if not available. */
    suspend fun graphFor(def: ZoneDefinition, frame: LocalFrame, polygon: Polygon2): RoadGraph? {
        val ready = _status.value as? RoadsStatus.Ready ?: return null
        val key = Triple(def.vertices, def.profile, ready.ways.size)
        graph?.takeIf { graphKey == key }?.let { return it }
        val g = withContext(Dispatchers.Default) { RoadGraph.build(ready.ways, polygon, frame, def.profile) }
        graph = g
        graphKey = key
        return g
    }

    /** The last built graph (for the overlay and A/B snapping); may lag behind edits by one build. */
    fun lastGraph(): RoadGraph? = graph

    /** True if the shape extends beyond the polygon the roads were fetched for. */
    fun coverageStale(def: ZoneDefinition): Boolean {
        val ready = _status.value as? RoadsStatus.Ready ?: return false
        if (ready.queriedPolygon == def.vertices) return false
        val f = LocalFrame(ready.queriedPolygon.first())
        val q = runCatching { Polygon2(ready.queriedPolygon.map(f::toLocal)) }.getOrNull() ?: return true
        return def.vertices.any { !q.contains(f.toLocal(it), 1.0) }
    }
}
