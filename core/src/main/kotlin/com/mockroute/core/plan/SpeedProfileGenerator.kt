package com.mockroute.core.plan

import com.mockroute.core.dist.BoundedGamma
import com.mockroute.core.geo.GeoMath
import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.route.RoutingProfile
import org.apache.commons.math3.special.Erf
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Turns a feasible segment into a [SpeedPlan]. Deterministic for a given seed.
 *
 * 1. T ~ time BoundedGamma (or its mean when [Request.lockTime]), clamped to [physical min time, t_max] and to
 *    ≤ d/v_min when v_min > 0.
 * 2. Nodes every Δs (5 m up to 10 km, else 10 m). Latent Ornstein–Uhlenbeck z(s): unit variance,
 *    ρ = exp(−Δs/L), correlation length L (150 m walk, 400 m car/bus, or the setting).
 * 3. Gaussian copula with shift δ: v_i = G⁻¹(Φ(z_i + δ)), clipped to [max(v_min, 0.3), v_max]. G is the
 *    **distance-weighted** marginal g(v) ∝ v·f(v), where f is the user's speed BoundedGamma, so that the
 *    **time-weighted** speed distribution of the plan (what a speedometer log shows) is f. (Each Δs takes
 *    Δs/v seconds, and that 1/v cancels the v in g.)
 * 4. Optional curvature cap v ≤ √(2·R) (car/bus), then acceleration limiting: a forward pass from v_0, a backward
 *    pass to v_end (both |a| ≤ a_max).
 * 5. τ(δ) = Σ 2Δs/(v_i + v_{i+1}) decreases monotonically in δ, so bisect δ ∈ [−6, 6] until |τ − T| < 0.5 s; if T
 *    is out of reach, return the closest plan plus a warning.
 *
 * Cost: the map z → v is tabulated once per request (no per-node gamma inversions), so the 50+ bisection
 * iterations are pure array passes.
 */
object SpeedProfileGenerator {
    const val MIN_SPEED_MPS = 0.3
    const val DELTA_RANGE = 6.0
    const val TIME_TOLERANCE_S = 0.5
    const val LATERAL_ACCEL_MPS2 = 2.0

    data class Request(
        val geometry: RouteGeometry,
        val settings: SegmentPlanSettings,
        val profile: RoutingProfile,
        val seed: Long,
        /** Speed at the start (0, or the previous segment's end speed). */
        val v0: Double = 0.0,
        /** Speed at the end (0 by default). */
        val vEnd: Double = 0.0,
        /** Use the time distribution's mean instead of sampling T. */
        val lockTime: Boolean = false,
        /** Correlation length L of the speed variations; null = profile default. */
        val correlationLengthM: Double? = null,
        /** Cap speeds in bends at √(2·R) (lateral 2 m/s²); default on for car/bus. */
        val curvatureCap: Boolean = profile != RoutingProfile.Walk,
        /** Node spacing; null = 5 m (≤ 10 km) or 10 m. */
        val stepM: Double? = null,
        /**
         * Stage 5: predicted speed at distance s (m/s). When set, node i's latent value is shifted so that the
         * copula's local median (z = 0) equals that speed: v_i = G⁻¹(Φ(z_i + δ + ζ_i)), with ζ_i = Φ⁻¹(G(m(s_i))).
         */
        val localMedian: ((Double) -> Double)? = null,
        /**
         * Stage 6: dwell stops (distance along the route, seconds). The speed is forced to 0 at the nearest node,
         * and the hold is inserted as a duplicated node (same s, v = 0, +duration), so the total time includes it.
         */
        val stops: List<Stop> = emptyList(),
        /** Stage 6: use this total time instead of sampling it from the time distribution (no d/v_min clamp). */
        val targetTimeS: Double? = null,
        /** Stage 7: extra per-position speed cap (m/s) at distance s, e.g. maxspeed or sharp turns. */
        val speedCap: ((Double) -> Double)? = null,
    )

    data class Stop(val atM: Double, val durationS: Double)

    fun defaultCorrelationLength(profile: RoutingProfile): Double = when (profile) {
        RoutingProfile.Walk -> 150.0
        RoutingProfile.Car, RoutingProfile.Bus -> 400.0
    }

    fun generate(req: Request): SpeedPlan = generateInternal(req, fixedDelta = null)

    /** Test hook: skip the bisection and use [fixedDelta] (δ = 0 shows the copula's own marginal). */
    internal fun generateInternal(req: Request, fixedDelta: Double?): SpeedPlan {
        val started = System.nanoTime()
        val geometry = req.geometry
        require(geometry.size >= 2 && geometry.lengthM > 0) { "empty route" }
        val st = req.settings
        val a = st.aMaxMps2
        val d = geometry.lengthM
        val rng = Random(req.seed)

        // 1. Total time.
        val vFloor = max(st.vMin, MIN_SPEED_MPS).coerceAtMost(st.vMax)
        val timeDist = st.time.distribution()
        val tSampled = if (req.lockTime) timeDist.mean else timeDist.sample(rng)
        val tPhys = Feasibility.tMinPhys(d, st.vMax, a)
        var tHigh = st.tMax
        if (st.vMin > 0) tHigh = min(tHigh, d / st.vMin)
        val target = req.targetTimeS ?: tSampled.coerceIn(min(tPhys, tHigh), max(tPhys, tHigh))

        // 2. Nodes + latent OU.
        val step = req.stepM ?: if (d <= 10_000) 5.0 else 10.0
        val n = max(2, ceil(d / step).toInt())
        val ds = d / n
        val sArr = DoubleArray(n + 1) { it * ds }
        sArr[n] = d
        val L = req.correlationLengthM ?: st.correlationLengthM ?: defaultCorrelationLength(req.profile)
        val rho = exp(-ds / L)
        val innov = sqrt(1 - rho * rho)
        val z = DoubleArray(n + 1)
        z[0] = gaussian(rng)
        for (i in 1..n) z[i] = rho * z[i - 1] + innov * gaussian(rng)

        // 3. Speed table v(z) = G⁻¹(Φ(z)) for the distance-weighted marginal, clipped.
        val table = SpeedTable.build(st.speed.distribution(), vFloor, st.vMax)

        val zShift = DoubleArray(n + 1)
        req.localMedian?.let { m -> for (i in 0..n) zShift[i] = table.zFor(m(sArr[i])) }

        // 4. Static caps (curvature) independent of δ.
        val cap = DoubleArray(n + 1) { st.vMax }
        if (req.curvatureCap) {
            for (i in 0..n) cap[i] = min(cap[i], curvatureSpeedCap(geometry, sArr[i]))
        }
        req.speedCap?.let { f -> for (i in 0..n) cap[i] = min(cap[i], f(sArr[i]).coerceAtLeast(MIN_SPEED_MPS)) }

        // Boundary envelope: with a non-zero v0 (or v_end) the nodes near the ends must be fast enough to brake from
        // (or accelerate to) it at a_max, otherwise the accel passes would have to violate the boundary speed.
        val v0 = req.v0.coerceIn(0.0, st.vMax)
        val vEnd = req.vEnd.coerceIn(0.0, st.vMax)
        val envelope = DoubleArray(n + 1) { i ->
            max(sqrt(max(0.0, v0 * v0 - 2 * a * sArr[i])), sqrt(max(0.0, vEnd * vEnd - 2 * a * (d - sArr[i]))))
        }

        // Stops: node index → total dwell there. Merged when they land on the same or adjacent nodes (two
        // consecutive zero-speed nodes would need infinite time to cross the gap between them).
        // The start/end nodes are zero-speed nodes too when v0/v_end = 0, so stops next to them merge into them.
        val dwellAt = java.util.TreeMap<Int, Double>()
        val zeroNodes = java.util.TreeSet<Int>()
        if (req.v0 <= 0.0) zeroNodes += 0
        if (req.vEnd <= 0.0) zeroNodes += n
        for (stop in req.stops) {
            if (stop.durationS <= 0) continue
            var idx = (stop.atM / ds).roundToIntCompat().coerceIn(0, n)
            zeroNodes.floor(idx)?.let { k -> if (idx - k <= 1) idx = k }
            zeroNodes.ceiling(idx)?.let { k -> if (k - idx <= 1) idx = k }
            zeroNodes += idx
            dwellAt[idx] = (dwellAt[idx] ?: 0.0) + stop.durationS
        }
        val dwellTotal = dwellAt.values.sum()

        val v = DoubleArray(n + 1)
        fun build(delta: Double): Double {
            for (i in 0..n) v[i] = min(max(min(table.speedAt(z[i] + delta + zShift[i]), cap[i]), envelope[i]), st.vMax)
            v[0] = v0
            v[n] = vEnd
            for (i in dwellAt.keys) v[i] = 0.0
            accelLimit(v, ds, a)
            var tau = dwellTotal
            for (i in 0 until n) tau += 2 * ds / (v[i] + v[i + 1]).coerceAtLeast(1e-9)
            return tau
        }

        // 5. Bisection on δ (τ decreasing in δ).
        var lo = -DELTA_RANGE
        var hi = DELTA_RANGE
        val tauSlow = build(lo)
        val tauFast = build(hi)
        var warning: String? = null
        val delta: Double = when {
            fixedDelta != null -> fixedDelta
            target >= tauSlow -> {
                if (target - tauSlow > TIME_TOLERANCE_S) {
                    warning = "Can't be slow enough for ${fmt(target)}; slowest plan takes ${fmt(tauSlow)}."
                }
                lo
            }
            target <= tauFast -> {
                if (tauFast - target > TIME_TOLERANCE_S) {
                    warning = "Can't be fast enough for ${fmt(target)}; fastest plan takes ${fmt(tauFast)}."
                }
                hi
            }
            else -> {
                var mid = 0.0
                for (iter in 0 until 200) {
                    mid = 0.5 * (lo + hi)
                    val tau = build(mid)
                    if (abs(tau - target) < TIME_TOLERANCE_S * 0.1) break
                    if (tau > target) lo = mid else hi = mid
                }
                mid
            }
        }
        build(delta)
        // Time integration with the dwell holds inserted as duplicated nodes.
        val outS = ArrayList<Double>(n + 1 + dwellAt.size)
        val outT = ArrayList<Double>(n + 1 + dwellAt.size)
        val outV = ArrayList<Double>(n + 1 + dwellAt.size)
        var time = 0.0
        for (i in 0..n) {
            if (i > 0) time += 2 * ds / (v[i - 1] + v[i]).coerceAtLeast(1e-9)
            outS += sArr[i]
            outT += time
            outV += v[i]
            dwellAt[i]?.let { hold ->
                time += hold
                outS += sArr[i]
                outT += time
                outV += 0.0
            }
        }

        return SpeedPlan(
            s = outS.toDoubleArray(),
            t = outT.toDoubleArray(),
            v = outV.toDoubleArray(),
            targetTimeS = target,
            seed = req.seed,
            delta = delta,
            warning = warning,
            generationMs = (System.nanoTime() - started) / 1_000_000,
        )
    }

    private fun Double.roundToIntCompat(): Int = kotlin.math.round(this).toInt()

    /** Forward pass from v[0] then backward pass to v[n]: |v_{i+1}² − v_i²| ≤ 2·a·Δs. In place. */
    internal fun accelLimit(v: DoubleArray, ds: Double, a: Double) {
        val k = 2 * a * ds
        for (i in 0 until v.size - 1) v[i + 1] = min(v[i + 1], sqrt(v[i] * v[i] + k))
        for (i in v.size - 2 downTo 0) v[i] = min(v[i], sqrt(v[i + 1] * v[i + 1] + k))
    }

    /** √(a_lat·R) with R the circumradius through points 15 m before/after [s] (∞ on straight parts). */
    internal fun curvatureSpeedCap(g: RouteGeometry, s: Double, halfSpanM: Double = 15.0): Double {
        val p0 = g.pointAtDistance(s - halfSpanM).position
        val p1 = g.pointAtDistance(s).position
        val p2 = g.pointAtDistance(s + halfSpanM).position
        val kx = GeoMath.EARTH_RADIUS_M * Math.toRadians(1.0) * cos(Math.toRadians(p1.lat))
        val ky = GeoMath.EARTH_RADIUS_M * Math.toRadians(1.0)
        val ax = (p0.lon - p1.lon) * kx
        val ay = (p0.lat - p1.lat) * ky
        val bx = (p2.lon - p1.lon) * kx
        val by = (p2.lat - p1.lat) * ky
        val cross = abs(ax * by - ay * bx)
        if (cross < 1e-6) return Double.POSITIVE_INFINITY
        val la = sqrt(ax * ax + ay * ay)
        val lb = sqrt(bx * bx + by * by)
        val cx = bx - ax
        val cy = by - ay
        val lc = sqrt(cx * cx + cy * cy)
        val r = la * lb * lc / (2 * cross)
        return sqrt(LATERAL_ACCEL_MPS2 * r)
    }

    /** Standard normal via Box–Muller on the seeded RNG (deterministic across platforms). */
    private fun gaussian(rng: Random): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-300)
        val u2 = rng.nextDouble()
        return sqrt(-2 * ln(u1)) * cos(2 * Math.PI * u2)
    }

    private fun fmt(s: Double): String {
        val m = (s / 60).toInt()
        return "%d:%02d".format(m / 60, m % 60)
    }

    /** Φ(x). */
    internal fun phi(x: Double): Double = 0.5 * Erf.erfc(-x / sqrt(2.0))
}

/**
 * Lookup table z → v = G⁻¹(Φ(z)) on z ∈ [−Z, Z], with G the distance-weighted marginal g(v) ∝ v·f(v),
 * clipped to [vFloor, vMax]. Linear interpolation; values outside the z range clamp to the ends.
 */
internal class SpeedTable private constructor(private val zMin: Double, private val dz: Double, private val values: DoubleArray) {
    fun speedAt(z: Double): Double {
        val x = (z - zMin) / dz
        if (x <= 0) return values[0]
        val last = values.size - 1
        if (x >= last) return values[last]
        val i = x.toInt()
        val f = x - i
        return values[i] + f * (values[i + 1] - values[i])
    }

    /** Inverse of [speedAt]: the z where the (non-decreasing) table reaches [v]; clamped to the table range. */
    fun zFor(v: Double): Double {
        if (v <= values[0]) return zMin
        val last = values.size - 1
        if (v >= values[last]) return zMin + last * dz
        var lo = 0
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (values[mid] < v) lo = mid else hi = mid
        }
        val span = values[hi] - values[lo]
        val f = if (span > 0) (v - values[lo]) / span else 0.0
        return zMin + (lo + f) * dz
    }

    companion object {
        private const val Z = 12.0
        private const val Z_POINTS = 4801
        private const val V_CELLS = 4096

        fun build(f: BoundedGamma, vFloor: Double, vMax: Double): SpeedTable {
            val dz = 2 * Z / (Z_POINTS - 1)
            if (f.isPointMass || f.hi - f.lo <= 0) {
                val c = f.lo.coerceIn(vFloor, vMax)
                return SpeedTable(-Z, dz, DoubleArray(Z_POINTS) { c })
            }
            // Distance-weighted CDF on a grid: cell mass ∝ (F(x_{j+1}) − F(x_j)) · v_mid (robust for shape < 1).
            val xs = DoubleArray(V_CELLS + 1) { f.lo + f.width * it / V_CELLS }
            val cum = DoubleArray(V_CELLS + 1)
            var prevF = 0.0
            for (j in 1..V_CELLS) {
                val fj = f.cdf(xs[j])
                val mid = max(0.5 * (xs[j - 1] + xs[j]), 1e-6)
                cum[j] = cum[j - 1] + (fj - prevF).coerceAtLeast(0.0) * mid
                prevF = fj
            }
            val total = cum[V_CELLS]
            val values = DoubleArray(Z_POINTS)
            var j = 0
            for (k in 0 until Z_POINTS) {
                val u = SpeedProfileGenerator.phi(-Z + k * dz) * total
                while (j < V_CELLS - 1 && cum[j + 1] < u) j++
                val c0 = cum[j]
                val c1 = cum[j + 1]
                val frac = if (c1 > c0) ((u - c0) / (c1 - c0)).coerceIn(0.0, 1.0) else 0.0
                values[k] = (xs[j] + frac * (xs[j + 1] - xs[j])).coerceIn(vFloor, vMax)
            }
            return SpeedTable(-Z, dz, values)
        }
    }
}
