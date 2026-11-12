package com.mockroute.core.plan

import com.mockroute.core.route.RouteResult
import com.mockroute.core.route.SpeedSpan
import kotlin.math.max
import kotlin.math.min

/**
 * The routing provider's speed at every point of a route (1.2.0, user request): what the first plan follows before
 * the user reshapes it with the speed/time gammas.
 *
 * Built from [RouteResult.providerSpeeds] (ORS step distance/duration, GraphHopper average_speed). Gaps are filled
 * with the route's average speed, and all speeds are scaled by one factor so that driving the profile takes exactly
 * the provider's total time (distance-weighted: Σ len/v = ETA).
 */
class ProviderSpeedProfile private constructor(private val starts: DoubleArray, private val speeds: DoubleArray) {
    /** Speed (m/s) at distance [s] along the route. */
    fun at(s: Double): Double {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= s) lo = mid else hi = mid - 1
        }
        return speeds[lo]
    }

    val minMps: Double get() = speeds.min()
    val maxMps: Double get() = speeds.max()

    companion object {
        /** Null when the provider gave no per-street speeds (or no total time). */
        fun of(r: RouteResult): ProviderSpeedProfile? =
            of(r.providerSpeeds, r.geometry.lengthM, r.durationS)

        fun of(spans: List<SpeedSpan>, lengthM: Double, durationS: Double?): ProviderSpeedProfile? {
            if (spans.isEmpty() || lengthM <= 0) return null
            val eta = durationS?.takeIf { it > 0 } ?: spans.sumOf { (it.endM - it.startM) / it.speedMps }.takeIf { it > 0 } ?: return null
            val mean = lengthM / eta
            val sorted = spans.filter { it.speedMps > 0 && it.endM > it.startM }.sortedBy { it.startM }
            if (sorted.isEmpty()) return null
            // Piecewise-constant pieces covering [0, length] (gaps → mean speed).
            val st = ArrayList<Double>()
            val sp = ArrayList<Double>()
            var pos = 0.0
            for (span in sorted) {
                val a = max(span.startM, pos)
                val b = min(span.endM, lengthM)
                if (b <= a) continue
                if (a > pos + 0.5) { st += pos; sp += mean }
                st += a; sp += span.speedMps
                pos = b
            }
            if (pos < lengthM - 0.5) { st += pos; sp += mean }
            if (st.isEmpty()) return null
            st[0] = 0.0
            // Scale so the profile takes exactly the provider's time.
            var time = 0.0
            for (i in st.indices) {
                val end = if (i + 1 < st.size) st[i + 1] else lengthM
                time += (end - st[i]) / sp[i]
            }
            val k = time / eta // >1 → profile too slow → speed up
            val maxSpeed = 100 / 3.6
            return ProviderSpeedProfile(st.toDoubleArray(), DoubleArray(sp.size) { (sp[it] * k).coerceIn(0.3, maxSpeed) })
        }
    }
}
