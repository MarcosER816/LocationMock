package com.mockroute.app.ui

import com.mockroute.core.geo.LatLon
import com.mockroute.core.plan.PlanRendering
import com.mockroute.core.plan.SegmentPlanSettings
import com.mockroute.core.plan.SpeedColorScale
import com.mockroute.core.plan.SpeedPlan
import com.mockroute.core.plan.SpeedProfileGenerator
import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.route.RoutingProfile
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
import kotlin.math.roundToLong
import kotlin.random.Random

/** Everything the map/sheet needs to show a generated plan (rendering precomputed off the main thread). */
class GeneratedPlan(
    val plan: SpeedPlan,
    val geometry: RouteGeometry,
    val settings: SegmentPlanSettings,
    val scale: SpeedColorScale,
    /** GeoJSON FeatureCollection of coloured LineString chunks (property "color"). */
    val chunksJson: String,
    val midpoint: LatLon,
    /** "1 h 12 min · 5.1 km/h". */
    val label: String,
)

sealed interface PlanStatus {
    data object NoRoute : PlanStatus
    data object Infeasible : PlanStatus
    data object Generating : PlanStatus
    data class Ready(val generated: GeneratedPlan) : PlanStatus
}

/**
 * Regenerates the plan (debounced 250 ms, on Dispatchers.Default, newest wins) whenever the route, the plan
 * settings, the profile, the seed or "lock time" change.
 */
@OptIn(FlowPreview::class)
class PlanController(
    private val scope: CoroutineScope,
    private val route: StateFlow<Pair<RouteGeometry?, RoutingProfile>>,
    private val settings: StateFlow<SegmentPlanSettings?>,
) {
    private val _seed = MutableStateFlow(Random.nextLong(1, Long.MAX_VALUE))
    val seed: StateFlow<Long> = _seed.asStateFlow()

    private val _lockTime = MutableStateFlow(false)
    val lockTime: StateFlow<Boolean> = _lockTime.asStateFlow()

    private val _status = MutableStateFlow<PlanStatus>(PlanStatus.NoRoute)
    val status: StateFlow<PlanStatus> = _status.asStateFlow()

    fun reroll() {
        _seed.value = Random.nextLong(1, Long.MAX_VALUE)
    }

    fun setLockTime(lock: Boolean) {
        _lockTime.value = lock
    }

    init {
        scope.launch {
            combine(route, settings, _seed, _lockTime) { r, s, seed, lock -> Inputs(r.first, r.second, s, seed, lock) }
                .distinctUntilChanged()
                .debounce(250)
                .collectLatest { inp -> _status.value = compute(inp) }
        }
    }

    private data class Inputs(
        val geometry: RouteGeometry?,
        val profile: RoutingProfile,
        val settings: SegmentPlanSettings?,
        val seed: Long,
        val lock: Boolean,
    )

    private suspend fun compute(inp: Inputs): PlanStatus {
        val g = inp.geometry
        if (g == null || g.size < 2 || g.lengthM < 1.0) return PlanStatus.NoRoute
        val st = inp.settings ?: SegmentPlanSettings.defaults(inp.profile, g.lengthM)
        if (!st.feasibility(g.lengthM).feasible) return PlanStatus.Infeasible
        _status.value = PlanStatus.Generating
        return withContext(Dispatchers.Default) {
            val plan = SpeedProfileGenerator.generate(
                SpeedProfileGenerator.Request(g, st, inp.profile, inp.seed, lockTime = inp.lock),
            )
            val scale = SpeedColorScale.forPlan(plan, st)
            // ≤ ~1000 features even for long routes.
            val chunkM = maxOf(30.0, g.lengthM / 1000.0)
            val chunks = PlanRendering.chunks(g, plan, scale, chunkM)
            val json = buildString(chunks.size * 120) {
                append("""{"type":"FeatureCollection","features":[""")
                chunks.forEachIndexed { i, c ->
                    if (i > 0) append(',')
                    append("""{"type":"Feature","properties":{"color":"""")
                    append(SpeedColorScale.toHex(c.argb))
                    append(""""},"geometry":{"type":"LineString","coordinates":[""")
                    c.points.forEachIndexed { j, p ->
                        if (j > 0) append(',')
                        append('[').append(p.lon).append(',').append(p.lat).append(']')
                    }
                    append("]}}")
                }
                append("]}")
            }
            PlanStatus.Ready(
                GeneratedPlan(
                    plan = plan,
                    geometry = g,
                    settings = st,
                    scale = scale,
                    chunksJson = json,
                    midpoint = g.pointAtDistance(g.lengthM / 2).position,
                    label = "${durationLabel(plan.totalTimeS)} · ${String.format(Locale.US, "%.1f km/h", plan.averageSpeedMps * 3.6)}",
                ),
            )
        }
    }

    companion object {
        /** "1 h 12 min" / "24 min" / "45 s". */
        fun durationLabel(seconds: Double): String {
            val total = seconds.roundToLong()
            val h = total / 3600
            val m = (total % 3600 + 30) / 60
            return when {
                h > 0 -> if (m == 60L) "${h + 1} h 0 min" else "$h h $m min"
                total >= 60 -> "${(total + 30) / 60} min"
                else -> "$total s"
            }
        }
    }
}
