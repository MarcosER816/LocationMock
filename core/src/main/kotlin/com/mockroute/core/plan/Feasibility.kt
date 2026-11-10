package com.mockroute.core.plan

import kotlin.math.sqrt

/**
 * Can a segment of length d be driven within the user's limits? All SI (m, m/s, s, m/s²).
 *
 * Physics: start and end at rest, |a| ≤ a_max, v ≤ v_max. The fastest profile is a trapezoid (accelerate,
 * cruise at v_max, brake) needing t = d/v + v/a when d ≥ v²/a; for shorter d it never reaches v_max and is a
 * triangle with t = 2·√(d/a). Equivalently d ≤ v_max·(t_max − v_max/a_max), as in CLAUDE.md.
 *
 * NOTE: the user's original text said "max_velocity/max_time"; that was a typo — the display rule is
 * distance ≤ v_max × t_max (an optimistic, acceleration-free bound shown next to the strict check).
 */
object Feasibility {
    /** Minimum physically possible time for [d] metres with top speed [v] and acceleration [a]. */
    fun tMinPhys(d: Double, v: Double, a: Double): Double {
        require(v > 0 && a > 0) { "v and a must be > 0" }
        if (d <= 0) return 0.0
        return if (d >= v * v / a) d / v + v / a else 2.0 * sqrt(d / a)
    }

    /** Shortest time over [d] for any top speed: the triangular profile, 2·√(d/a). */
    fun absoluteMinTime(d: Double, a: Double): Double = if (d <= 0) 0.0 else 2.0 * sqrt(d / a)

    /**
     * Smallest top speed v with tMinPhys(d, v, a) ≤ [t]. tMinPhys decreases in v up to v* = √(d·a) and is
     * constant (= 2√(d/a)) beyond, so for t ≥ 2√(d/a) the answer is the smaller root of v²/a − t·v + d = 0:
     * v = (a/2)·(t − √(t² − 4d/a)). Returns +∞ if [t] < 2√(d/a).
     */
    fun requiredV(d: Double, t: Double, a: Double): Double {
        require(a > 0) { "a must be > 0" }
        if (d <= 0) return 0.0
        if (t <= 0) return Double.POSITIVE_INFINITY
        val disc = t * t - 4.0 * d / a
        if (disc < 0) return Double.POSITIVE_INFINITY
        // Numerically stable form of the smaller root: 2d / (t + √disc).
        return 2.0 * d / (t + sqrt(disc))
    }

    enum class Issue {
        /** tMinPhys(v_max) > t_max: can't make it in time. Fix: raise t_max and/or v_max. */
        TooSlow,
        /** v_min·t_min > d: even the slowest allowed speed for the shortest allowed time overshoots. */
        TooShort,
        TimeRangeInverted,
        SpeedRangeInverted,
        /** v_max = 0 with d > 0. */
        NoSpeed,
    }

    data class Result(
        val feasible: Boolean,
        val issues: List<Issue>,
        /** Strict minimum time at v_max (s); +∞ if v_max = 0. */
        val tMinPhysS: Double,
        /** d / t_max: average speed the segment needs at the latest allowed arrival (m/s). */
        val requiredAverageMps: Double,
        /** The optimistic display rule d ≤ v_max·t_max. */
        val displayRuleOk: Boolean,
    )

    fun check(d: Double, vMax: Double, tMax: Double, vMin: Double, tMin: Double, aMax: Double): Result {
        require(aMax > 0) { "a_max must be > 0" }
        val issues = mutableListOf<Issue>()
        if (vMin > vMax) issues += Issue.SpeedRangeInverted
        if (tMin > tMax) issues += Issue.TimeRangeInverted
        val tPhys = if (vMax > 0) tMinPhys(d, vMax, aMax) else if (d > 0) Double.POSITIVE_INFINITY else 0.0
        if (vMax <= 0 && d > 0) issues += Issue.NoSpeed
        else if (tPhys > tMax) issues += Issue.TooSlow
        if (d < vMin * tMin) issues += Issue.TooShort
        return Result(
            feasible = issues.isEmpty(),
            issues = issues,
            tMinPhysS = tPhys,
            requiredAverageMps = if (tMax > 0) d / tMax else Double.POSITIVE_INFINITY,
            displayRuleOk = d <= vMax * tMax,
        )
    }
}
