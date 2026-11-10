package com.mockroute.core.traffic

import com.mockroute.core.dist.BoundedGamma
import com.mockroute.core.plan.AccelerationPreset
import com.mockroute.core.plan.CenterKind
import com.mockroute.core.plan.CoupledSliders
import com.mockroute.core.plan.Feasibility
import com.mockroute.core.plan.GammaSetting
import com.mockroute.core.plan.SegmentPlanSettings
import com.mockroute.core.route.RouteGeometry
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Serializable
data class WorstPoint(val atM: Double, val arrivalEpochS: Double, val speedMps: Double, val delayS: Double)

/** Result of a traffic analysis for one route + departure. SI units, epoch seconds. */
class TrafficAnalysis(
    val departureEpochS: Long,
    val mode: TrafficMode,
    val boundariesM: DoubleArray,
    val durationsS: DoubleArray,
    val arrivalsEpochS: DoubleArray,
    val freeFlowS: DoubleArray,
    val totalS: Double,
    val totalMinus30S: Double,
    val totalPlus30S: Double,
    val liveVsHistoricS: Double?,
    val settings: SegmentPlanSettings,
    val timeHeuristic: Boolean,
    val worst: List<WorstPoint>,
    val provider: String,
    val requestsMade: Int,
    val cacheHits: Int,
    val iterations: Int,
    val routeKey: String,
    /** How closely the provider followed our path (worst over the samples used). */
    val match: PathMatch = PathMatch.Exact,
) {
    val chunkSpeedsMps: DoubleArray = DoubleArray(durationsS.size) { (boundariesM[it + 1] - boundariesM[it]) / durationsS[it] }

    /** Predicted speed at [s] metres (piecewise linear between chunk centres) — the copula's local median. */
    fun medianSpeedAt(s: Double): Double {
        val n = chunkSpeedsMps.size
        if (n == 1) return chunkSpeedsMps[0]
        val centres = DoubleArray(n) { 0.5 * (boundariesM[it] + boundariesM[it + 1]) }
        if (s <= centres[0]) return chunkSpeedsMps[0]
        if (s >= centres[n - 1]) return chunkSpeedsMps[n - 1]
        var i = 0
        while (centres[i + 1] < s) i++
        val f = (s - centres[i]) / (centres[i + 1] - centres[i])
        return chunkSpeedsMps[i] + f * (chunkSpeedsMps[i + 1] - chunkSpeedsMps[i])
    }
}

/** Where samples are cached (app: disk, 7 days). Keys come from [TrafficPlanner.cacheKey]. */
interface TrafficCacheStore {
    suspend fun get(key: String): SpeedProfileSample?
    suspend fun put(key: String, sample: SpeedProfileSample)
}

object TrafficPlanner {
    const val MAX_REQUESTS_PER_PLAN = 12
    const val HEURISTIC_CV = 0.12

    /** Route identity for caching: mode + geometry rounded to ~10 m (so a re-traced identical route hits). */
    fun routeKey(route: RouteGeometry, mode: TrafficMode): String {
        val g = route.simplify(5.0)
        val raw = buildString {
            append(mode.name)
            g.points.forEach { append(String.format(Locale.US, "|%.4f,%.4f", it.lat, it.lon)) }
        }
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    /** Cache key (route hash, weekday, 15-min bin of day) in the user's zone: Tuesday 08:15 reuses last Tuesday's. */
    fun cacheKey(routeKey: String, binEpochS: Long, zone: ZoneId): String {
        val z = Instant.ofEpochSecond(binEpochS).atZone(zone)
        val bin = (z.hour * 60 + z.minute) / 15
        return "$routeKey-${z.dayOfWeek.value}-$bin"
    }

    /**
     * Full Stage 5 pipeline: query ≤ [maxRequests] departure bins (cache first), propagate the ETA through
     * time-dependent chunk speeds, fit the speed/time gammas and pick the worst points.
     */
    suspend fun analyze(
        route: RouteGeometry,
        departureEpochS: Long,
        mode: TrafficMode,
        provider: TrafficProvider,
        cache: TrafficCacheStore?,
        nowEpochS: Long,
        zone: ZoneId,
        maxRequests: Int = MAX_REQUESTS_PER_PLAN,
    ): TrafficAnalysis {
        val key = routeKey(route, mode)
        var requests = 0
        var hits = 0
        suspend fun sampleAt(bin: Long): SpeedProfileSample {
            val ck = cacheKey(key, bin, zone)
            cache?.get(ck)?.let {
                hits++
                return it.copy(departureEpochS = bin) // same weekday/bin of an earlier week
            }
            requests++
            return provider.profile(route, bin, mode).copy(departureEpochS = bin).also { cache?.put(ck, it) }
        }

        val first = TrafficPropagation.firstBin(departureEpochS, nowEpochS)
        val s0 = sampleAt(first)
        val bins = TrafficPropagation.binTimes(departureEpochS, s0.travelTimeS, nowEpochS, first, maxRequests)
        val samples = bins.map { if (it == first) s0 else sampleAt(it) }

        val model = ChunkModel(ChunkModel.boundaries(route.lengthM), samples, mode)
        val main = TrafficPropagation.propagate(model, departureEpochS.toDouble())
        val minus = TrafficPropagation.propagate(model, departureEpochS - 1800.0)
        val plus = TrafficPropagation.propagate(model, departureEpochS + 1800.0)
        val free = DoubleArray(model.count) { model.freeFlowDuration(it) }

        val live = s0.liveTimeS
        val hist = s0.historicTimeS
        val liveDiff = if (live != null && hist != null) abs(live - hist) else null

        val settings = fitSettings(route.lengthM, model, main, minus.totalS, plus.totalS, liveDiff, mode)
        return TrafficAnalysis(
            departureEpochS = departureEpochS,
            mode = mode,
            boundariesM = model.boundariesM,
            durationsS = main.durationsS,
            arrivalsEpochS = main.arrivalsEpochS,
            freeFlowS = free,
            totalS = main.totalS,
            totalMinus30S = minus.totalS,
            totalPlus30S = plus.totalS,
            liveVsHistoricS = liveDiff,
            settings = settings.first,
            timeHeuristic = settings.second,
            worst = worstPoints(model, main, free),
            provider = s0.provider,
            requestsMade = requests,
            cacheHits = hits,
            iterations = main.iterations,
            routeKey = key,
            match = samples.maxOf { it.match },
        )
    }

    /** Speed gamma from time-weighted chunk speeds, time gamma from the propagated duration and its spread. */
    internal fun fitSettings(
        d: Double,
        model: ChunkModel,
        main: TrafficPropagation.Result,
        totalMinus30: Double,
        totalPlus30: Double,
        liveDiff: Double?,
        mode: TrafficMode,
    ): Pair<SegmentPlanSettings, Boolean> {
        val speeds = DoubleArray(model.count) { model.lengthOf(it) / main.durationsS[it] }
        val speed = fitSpeed(speeds, main.durationsS)
        val preset = if (mode == TrafficMode.Bus) AccelerationPreset.BusComfort else AccelerationPreset.CarNormal
        val (time, heuristic) = fitTime(main.totalS, listOf(totalMinus30 - main.totalS, totalPlus30 - main.totalS, liveDiff))
        var st = SegmentPlanSettings(speed = speed, time = time, aMaxMps2 = preset.aMps2, accelPreset = preset, autoFromTraffic = true)
        // Keep it feasible: t_max ≥ physical minimum at v_max; v_min·t_min ≤ d.
        val tPhys = Feasibility.tMinPhys(d, st.vMax, st.aMaxMps2)
        if (st.tMax < tPhys) st = st.copy(time = st.time.withRange(st.tMin, min(CoupledSliders.T_CAP, tPhys * 1.02)))
        if (st.vMin * st.tMin > d) st = st.copy(time = st.time.withRange(d / st.vMin * 0.98, st.tMax))
        return st to heuristic
    }

    /** Time-weighted moments → BoundedGamma on [max(0, P5), min(100 km/h, 1.1·P95)]. */
    fun fitSpeed(speeds: DoubleArray, weights: DoubleArray): GammaSetting {
        val total = weights.sum()
        val mean = speeds.indices.sumOf { speeds[it] * weights[it] } / total
        val variance = speeds.indices.sumOf { (speeds[it] - mean).let { d -> d * d } * weights[it] } / total
        val p5 = weightedPercentile(speeds, weights, 0.05)
        val p95 = weightedPercentile(speeds, weights, 0.95)
        var lo = max(0.0, p5)
        var hi = min(CoupledSliders.V_CAP, 1.1 * p95)
        if (hi - lo < 1.0) {
            lo = max(0.0, mean - 0.5)
            hi = min(CoupledSliders.V_CAP, mean + 0.5)
        }
        val m = mean.coerceIn(lo + 1e-3 * (hi - lo), hi - 1e-3 * (hi - lo))
        val v = if (variance > 0) variance else ((hi - lo) / 6).let { it * it }
        val g = BoundedGamma.withMeanVariance(lo, hi, m, v)
        return GammaSetting(lo, hi, m, g.shape, CenterKind.Mean)
    }

    /** Mean = propagated duration; sd from ±30 min / live-vs-historic deviations, else CV 12 % ("heuristic"). */
    fun fitTime(meanS: Double, deviations: List<Double?>): Pair<GammaSetting, Boolean> {
        val devs = deviations.filterNotNull().filter { it.isFinite() }
        var sd = if (devs.isEmpty()) 0.0 else sqrt(devs.sumOf { it * it } / devs.size)
        val heuristic = sd < max(5.0, 0.02 * meanS)
        if (heuristic) sd = HEURISTIC_CV * meanS
        val lo = max(0.0, meanS - 3 * sd)
        val hi = min(CoupledSliders.T_CAP, meanS + 3 * sd)
        val m = meanS.coerceIn(lo, hi)
        if (hi - lo < 1.0) return GammaSetting(lo, hi, m, 4.0) to heuristic
        val g = BoundedGamma.withMeanVariance(lo, hi, m.coerceIn(lo + 1e-3 * (hi - lo), hi - 1e-3 * (hi - lo)), sd * sd)
        return GammaSetting(lo, hi, m, g.shape, CenterKind.Mean) to heuristic
    }

    fun weightedPercentile(x: DoubleArray, w: DoubleArray, p: Double): Double {
        val idx = x.indices.sortedBy { x[it] }
        val total = w.sum()
        var cum = 0.0
        for (i in idx) {
            cum += w[i]
            if (cum >= p * total) return x[i]
        }
        return x[idx.last()]
    }

    /** Top 3 chunks by delay (ties: lower speed / free-flow ratio first); delays under 30 s are not reported. */
    fun worstPoints(model: ChunkModel, main: TrafficPropagation.Result, free: DoubleArray, count: Int = 3): List<WorstPoint> =
        (0 until model.count)
            .map { k ->
                val delay = main.durationsS[k] - free[k]
                Triple(k, delay, free[k] / main.durationsS[k])
            }
            .filter { it.second >= 30.0 }
            .sortedWith(compareByDescending<Triple<Int, Double, Double>> { it.second }.thenBy { it.third })
            .take(count)
            .map { (k, delay, _) ->
                val mid = 0.5 * (model.boundariesM[k] + model.boundariesM[k + 1])
                WorstPoint(
                    atM = mid,
                    arrivalEpochS = 0.5 * (main.arrivalsEpochS[k] + main.arrivalsEpochS[k + 1]),
                    speedMps = model.lengthOf(k) / main.durationsS[k],
                    delayS = delay,
                )
            }
}
