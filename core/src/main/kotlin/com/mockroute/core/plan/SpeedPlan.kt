package com.mockroute.core.plan

/**
 * A speed profile over a segment: nodes i = 0..n at distances [s] (m) with speeds [v] (m/s) reached at times
 * [t] (s). Between nodes the acceleration is constant: a_i = (v_{i+1}² − v_i²) / (2Δs_i), so the node time step
 * is Δt_i = 2Δs_i / (v_i + v_{i+1}) — continuous speed, piecewise-constant acceleration.
 */
class SpeedPlan(
    val s: DoubleArray,
    val t: DoubleArray,
    val v: DoubleArray,
    /** Requested total time (after clamping to what is physically possible). */
    val targetTimeS: Double,
    val seed: Long,
    /** Copula shift δ found by the bisection. */
    val delta: Double,
    /** Non-null when the target time could not be met exactly (closest plan returned). */
    val warning: String? = null,
    val generationMs: Long = 0,
) {
    init {
        require(s.size == t.size && t.size == v.size && s.size >= 2) { "inconsistent plan arrays" }
    }

    val distanceM: Double get() = s.last()
    val totalTimeS: Double get() = t.last()
    val averageSpeedMps: Double get() = if (totalTimeS > 0) distanceM / totalTimeS else 0.0
    val minSpeedMps: Double get() = v.min()
    val maxSpeedMps: Double get() = v.max()

    /** Node index i with t[i] ≤ time < t[i+1] (binary search), clamped to the last segment. */
    fun segmentAtTime(time: Double): Int = search(t, time)

    /** Node index i with s[i] ≤ dist < s[i+1], clamped. */
    fun segmentAtDistance(dist: Double): Int = search(s, dist)

    /** Distance, speed and acceleration at [time] (clamped to [0, T]). */
    fun stateAtTime(time: Double): Kin {
        val tc = time.coerceIn(0.0, totalTimeS)
        val i = segmentAtTime(tc)
        val dt = t[i + 1] - t[i]
        val a = if (dt > 0) (v[i + 1] - v[i]) / dt else 0.0
        val tau = (tc - t[i]).coerceIn(0.0, dt)
        val dist = (s[i] + v[i] * tau + 0.5 * a * tau * tau).coerceIn(s[i], s[i + 1])
        return Kin(dist, (v[i] + a * tau).coerceAtLeast(0.0), a)
    }

    /** Time and speed when the plan passes [dist] (clamped). Inverse of [stateAtTime] for the distance. */
    fun timeAtDistance(dist: Double): Pair<Double, Double> {
        val dc = dist.coerceIn(0.0, distanceM)
        val i = segmentAtDistance(dc)
        val ds = s[i + 1] - s[i]
        if (ds <= 0) return t[i] to v[i]
        // v² is linear in s under constant acceleration.
        val frac = (dc - s[i]) / ds
        val vHere = kotlin.math.sqrt((v[i] * v[i] + frac * (v[i + 1] * v[i + 1] - v[i] * v[i])).coerceAtLeast(0.0))
        val sum = v[i] + vHere
        val tHere = t[i] + if (sum > 0) 2 * (dc - s[i]) / sum else 0.0
        return tHere.coerceIn(t[i], t[i + 1]) to vHere
    }

    data class Kin(val distanceM: Double, val speedMps: Double, val accelMps2: Double)

    private fun search(arr: DoubleArray, x: Double): Int {
        var lo = 0
        var hi = arr.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (arr[mid] <= x) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun contentEquals(o: SpeedPlan): Boolean =
        s.contentEquals(o.s) && t.contentEquals(o.t) && v.contentEquals(o.v) && targetTimeS == o.targetTimeS &&
            seed == o.seed && delta == o.delta && warning == o.warning
}
