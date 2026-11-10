package com.mockroute.app.ui

import com.mockroute.core.geo.LatLon
import com.mockroute.core.plan.PlanRendering
import com.mockroute.core.plan.SegmentPlanSettings
import com.mockroute.core.plan.SpeedColorScale
import com.mockroute.core.zone.InsidePathFinder
import com.mockroute.core.zone.ZoneDefinition
import com.mockroute.core.zone.ZonePlan
import com.mockroute.core.zone.ZonePlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.random.Random

class GeneratedZone(
    val zone: ZonePlan,
    val settings: SegmentPlanSettings,
    val scale: SpeedColorScale,
    val chunksJson: String,
    val midpoint: LatLon,
    val label: String,
)

sealed interface ZoneStatus {
    data class NotReady(val reason: String) : ZoneStatus
    data class Infeasible(val shortestM: Double) : ZoneStatus
    data object Generating : ZoneStatus
    data class Ready(val generated: GeneratedZone) : ZoneStatus
}

/** Regenerates the zone plan (debounced 300 ms, off the main thread, newest wins). */
@OptIn(FlowPreview::class)
class ZonePlanController(
    scope: CoroutineScope,
    private val def: StateFlow<ZoneDefinition>,
    private val settings: StateFlow<SegmentPlanSettings>,
) {
    private val _seed = MutableStateFlow(Random.nextLong(1, Long.MAX_VALUE))
    val seed: StateFlow<Long> = _seed.asStateFlow()
    private val _lock = MutableStateFlow(false)
    val lockTime: StateFlow<Boolean> = _lock.asStateFlow()
    private val _status = MutableStateFlow<ZoneStatus>(ZoneStatus.NotReady("Draw the zone"))
    val status: StateFlow<ZoneStatus> = _status.asStateFlow()

    fun reroll() {
        _seed.value = Random.nextLong(1, Long.MAX_VALUE)
    }

    fun setLockTime(v: Boolean) {
        _lock.value = v
    }

    init {
        scope.launch {
            combine(def, settings, _seed, _lock) { d, s, seed, lock -> Quad(d, s, seed, lock) }
                .distinctUntilChanged()
                .debounce(300)
                .collectLatest { _status.value = compute(it) }
        }
    }

    private data class Quad(val d: ZoneDefinition, val s: SegmentPlanSettings, val seed: Long, val lock: Boolean)

    /** Shortest inside path A→B (feasibility distance), or null if the zone isn't ready. */
    fun shortestPath(d: ZoneDefinition): Double? {
        val f = d.frame() ?: return null
        val poly = d.polygon(f) ?: return null
        val a = d.entry ?: return null
        val b = d.exit ?: return null
        return InsidePathFinder.length(ZonePlanner.shortestPath(poly, f.toLocal(a), f.toLocal(b)))
    }

    private suspend fun compute(q: Quad): ZoneStatus {
        val d = q.d
        if (d.vertices.size < 3) return ZoneStatus.NotReady("Tap the map to add the zone's corners")
        if (!d.closed) return ZoneStatus.NotReady("Close the shape")
        val f = d.frame() ?: return ZoneStatus.NotReady("Draw the zone")
        val poly = d.polygon(f) ?: return ZoneStatus.NotReady("The shape crosses itself — fix it")
        val a = d.entry ?: return ZoneStatus.NotReady("Place entry A on the edge")
        val b = d.exit ?: return ZoneStatus.NotReady("Place exit B on the edge")
        val shortest = shortestPath(d) ?: return ZoneStatus.NotReady("Draw the zone")
        if (!ZonePlanner.feasibility(shortest, q.s).feasible) return ZoneStatus.Infeasible(shortest)
        _status.value = ZoneStatus.Generating
        return withContext(Dispatchers.Default) {
            val zone = ZonePlanner.plan(
                ZonePlanner.Request(
                    poly, f, f.toLocal(a), f.toLocal(b), d.mixture(f, poly), q.s, q.seed, q.lock,
                    d.dwellProbability, d.dwellMeanS, d.wobble, d.mode,
                ),
            )
            val scale = SpeedColorScale.forPlan(zone.plan, q.s)
            val chunks = PlanRendering.chunks(zone.geometry, zone.plan, scale, chunkM = maxOf(3.0, zone.geometry.lengthM / 1000))
            val json = buildString {
                append("""{"type":"FeatureCollection","features":[""")
                chunks.forEachIndexed { i, c ->
                    if (i > 0) append(',')
                    append("""{"type":"Feature","properties":{"color":"""").append(SpeedColorScale.toHex(c.argb))
                    append(""""},"geometry":{"type":"LineString","coordinates":[""")
                    c.points.forEachIndexed { j, p ->
                        if (j > 0) append(',')
                        append('[').append(p.lon).append(',').append(p.lat).append(']')
                    }
                    append("]}}")
                }
                append("]}")
            }
            ZoneStatus.Ready(
                GeneratedZone(
                    zone, q.s, scale, json, f.toLatLon(poly.centroid),
                    "${PlanController.durationLabel(zone.plan.totalTimeS)} · ${zone.waypoints.size} stops · " +
                        String.format(Locale.US, "%.0f m walked", zone.lengthM),
                ),
            )
        }
    }
}
