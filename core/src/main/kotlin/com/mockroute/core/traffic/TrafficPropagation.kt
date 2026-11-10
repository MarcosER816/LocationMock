package com.mockroute.core.traffic

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Time-dependent travel over a fixed path, split into chunks of ~1–2 km.
 *
 * Each provider sample b (a departure at dep_b) tells us, for chunk k, when that departure ENTERS the chunk
 * (e_bk = dep_b + T_b(s_k)) and how long it takes to cross it (tt_bk). So tt_k(t), the crossing time for a
 * vehicle entering chunk k at clock time t, is the linear interpolation of tt_bk over e_bk (clamped outside).
 * Bus rules are applied per chunk: speed ≤ min(0.85 × car, 60 km/h).
 */
class ChunkModel(
    /** Chunk boundaries along the route, metres: b_0 = 0 < … < b_n = d. */
    val boundariesM: DoubleArray,
    samples: List<SpeedProfileSample>,
    val mode: TrafficMode,
) {
    val count: Int = boundariesM.size - 1
    val samples: List<SpeedProfileSample> = samples.sortedBy { it.departureEpochS }

    private val entry = Array(count) { k -> DoubleArray(this.samples.size) { b -> this.samples[b].departureEpochS + this.samples[b].timeAt(boundariesM[k]) } }
    /** Car crossing times as reported by each sample. */
    private val carCross = Array(count) { k ->
        DoubleArray(this.samples.size) { b ->
            val s = this.samples[b]
            (s.timeAt(boundariesM[k + 1]) - s.timeAt(boundariesM[k])).coerceAtLeast(0.1)
        }
    }

    /** Crossing times for [mode] (bus rules applied). */
    private val cross = Array(count) { k -> DoubleArray(this.samples.size) { b -> busRule(k, carCross[k][b]) } }

    init {
        require(count >= 1 && this.samples.isNotEmpty()) { "need ≥ 1 chunk and ≥ 1 sample" }
    }

    fun lengthOf(k: Int): Double = boundariesM[k + 1] - boundariesM[k]

    private fun busRule(k: Int, carDuration: Double): Double {
        if (mode != TrafficMode.Bus) return carDuration
        val len = lengthOf(k)
        return max(carDuration / BUS_FACTOR, len / BUS_MAX_MPS)
    }

    /** Crossing time of chunk [k] when entering it at clock time [t] (epoch s). */
    fun durationAt(k: Int, t: Double): Double {
        val e = entry[k]
        val c = cross[k]
        if (e.size == 1 || t <= e[0]) return c[0]
        if (t >= e[e.size - 1]) return c[e.size - 1]
        var i = 0
        while (i < e.size - 2 && e[i + 1] < t) i++
        val span = e[i + 1] - e[i]
        val f = if (span > 0) (t - e[i]) / span else 0.0
        return c[i] + f * (c[i + 1] - c[i])
    }

    /**
     * Free-flow crossing time: the fastest sample for that chunk, also bounded by the provider's no-traffic total
     * (spread proportionally) so all-rush-hour samples still show delays.
     */
    fun freeFlowDuration(k: Int): Double {
        var best = cross[k].min()
        for ((b, s) in samples.withIndex()) {
            val nt = s.noTrafficTimeS ?: continue
            if (s.travelTimeS <= 0) continue
            best = min(best, busRule(k, carCross[k][b] * (nt / s.travelTimeS).coerceAtMost(1.0)))
        }
        return best
    }

    companion object {
        const val BUS_FACTOR = 0.85
        const val BUS_MAX_MPS = 60.0 / 3.6

        /** Even chunks of about [targetM] (1–2 km range), at least one. */
        fun boundaries(lengthM: Double, targetM: Double = 1500.0): DoubleArray {
            val n = max(1, (lengthM / targetM).roundToInt())
            return DoubleArray(n + 1) { lengthM * it / n }
        }
    }
}

object TrafficPropagation {
    const val TOLERANCE_S = 5.0
    const val BIN_S = 15 * 60L

    class Result(
        /** Clock time (epoch s) at each chunk boundary; arrivals[0] = departure. */
        val arrivalsEpochS: DoubleArray,
        val durationsS: DoubleArray,
        val iterations: Int,
        val converged: Boolean,
    ) {
        val totalS: Double get() = arrivalsEpochS.last() - arrivalsEpochS.first()
    }

    /**
     * Fixed-point (Jacobi) iteration: start from free-flow durations, arrivals t_k = t0 + Σ_{j<k} dur_j,
     * recompute dur_k = tt_k(t_k), repeat until no arrival moves by ≥ [toleranceS]. On a fixed path with FIFO
     * links this converges in at most n iterations to the forward (sequential) solution.
     */
    fun propagate(model: ChunkModel, departureEpochS: Double, toleranceS: Double = TOLERANCE_S, maxIterations: Int = 200): Result {
        val n = model.count
        val dur = DoubleArray(n) { model.freeFlowDuration(it) }
        val arr = DoubleArray(n + 1)
        var iter = 0
        var converged = false
        while (iter < maxIterations) {
            iter++
            arr[0] = departureEpochS
            for (k in 0 until n) arr[k + 1] = arr[k] + dur[k]
            var maxMove = 0.0
            val newArr = DoubleArray(n + 1)
            newArr[0] = departureEpochS
            for (k in 0 until n) {
                dur[k] = model.durationAt(k, arr[k])
                newArr[k + 1] = newArr[k] + dur[k]
            }
            for (k in 0..n) maxMove = max(maxMove, abs(newArr[k] - arr[k]))
            System.arraycopy(newArr, 0, arr, 0, n + 1)
            if (maxMove < toleranceS) {
                converged = true
                break
            }
        }
        return Result(arr, dur, iter, converged)
    }

    /** Floor/ceil to the 15-minute grid (epoch-aligned; matches the local clock grid in whole-quarter zones). */
    fun alignDown(t: Long): Long = Math.floorDiv(t, BIN_S) * BIN_S
    fun alignUp(t: Long): Long = alignDown(t + BIN_S - 1)

    /** The first request: the bin at/just before departure, but never in the past (TomTom needs a future departAt). */
    fun firstBin(departureEpochS: Long, nowEpochS: Long): Long = max(alignUp(nowEpochS + 60), alignDown(departureEpochS))

    /**
     * Departure bins to query: on the 15-min grid from (departure − 30 min) to (departure + 30 min + 1.5 × expected),
     * never in the past, at most [maxRequests] (including [first]). If the span needs more, the step widens to
     * 30/45/60… min.
     */
    fun binTimes(departureEpochS: Long, expectedDurationS: Double, nowEpochS: Long, first: Long, maxRequests: Int = 12): List<Long> {
        require(maxRequests >= 1)
        val earliest = alignUp(nowEpochS + 60)
        val start = max(earliest, alignDown(departureEpochS - 1800))
        val end = departureEpochS + 1800 + (1.5 * expectedDurationS).toLong()
        var step = BIN_S
        fun grid(st: Long): List<Long> {
            val out = mutableListOf<Long>()
            var t = start
            while (true) {
                out += t
                if (t >= end) break
                t += st
            }
            return out
        }
        var bins = grid(step)
        while ((bins + first).distinct().size > maxRequests) {
            step += BIN_S
            bins = grid(step)
        }
        return (bins + first).distinct().sorted()
    }
}
