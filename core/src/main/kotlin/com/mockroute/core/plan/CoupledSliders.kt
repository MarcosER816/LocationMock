package com.mockroute.core.plan

import kotlin.math.ceil

/**
 * The linked (v_max, t_max) pair shown when a segment is infeasible because it is too slow.
 *
 * Rules: lowering one raises the other just enough to stay feasible; raising one never changes the other.
 * Caps: v_max ≤ 100 km/h, t_max ≤ 6 h. Floors keep every reachable state feasible:
 * v_max ≥ requiredV(6 h) and t_max ≥ requiredT(100 km/h). If even (100 km/h, 6 h) is infeasible the route is
 * too long for the caps ([tooLongForCaps]) and the sliders must be blocked.
 *
 * Immutable: every setter returns a new state. SI units.
 */
data class CoupledSliders(
    val d: Double,
    val aMax: Double,
    val vMax: Double,
    val tMax: Double,
) {
    init {
        require(aMax > 0) { "a_max must be > 0" }
    }

    fun requiredT(v: Double): Double = Feasibility.tMinPhys(d, v, aMax)
    fun requiredV(t: Double): Double = Feasibility.requiredV(d, t, aMax)

    val tooLongForCaps: Boolean get() = requiredT(V_CAP) > T_CAP
    val vFloor: Double get() = requiredV(T_CAP).coerceAtMost(V_CAP)
    val tFloor: Double get() = requiredT(V_CAP).coerceAtMost(T_CAP)

    val isFeasible: Boolean get() = !tooLongForCaps && requiredT(vMax) <= tMax * (1 + 1e-12)

    /** Moves v_max (clamped to [vFloor, V_CAP]); a decrease raises t_max to exactly requiredT(v). */
    fun withVMax(newV: Double): CoupledSliders {
        if (tooLongForCaps) return this
        val v = newV.coerceIn(vFloor, V_CAP)
        return if (v < vMax) copy(vMax = v, tMax = maxOf(tMax, requiredT(v)).coerceAtMost(T_CAP)) else copy(vMax = v)
    }

    /** Moves t_max (clamped to [tFloor, T_CAP]); a decrease raises v_max to exactly requiredV(t). */
    fun withTMax(newT: Double): CoupledSliders {
        if (tooLongForCaps) return this
        val t = newT.coerceIn(tFloor, T_CAP)
        return if (t < tMax) copy(tMax = t, vMax = maxOf(vMax, requiredV(t)).coerceAtMost(V_CAP)) else copy(tMax = t)
    }

    companion object {
        const val V_CAP: Double = 100.0 / 3.6
        const val T_CAP: Double = 6.0 * 3600.0

        /**
         * Default proposal for an infeasible segment: keep v_max (clamped to the floor/cap) and raise t_max to
         * requiredT(v_max), rounded UP to the whole minute (then capped at 6 h).
         */
        fun propose(d: Double, aMax: Double, vMax: Double, tMax: Double): CoupledSliders {
            val base = CoupledSliders(d, aMax, vMax, tMax)
            if (base.tooLongForCaps) return base
            val v = vMax.coerceIn(base.vFloor, V_CAP)
            val t = (ceil(base.requiredT(v) / 60.0 - 1e-9) * 60.0).coerceAtMost(T_CAP)
            return base.copy(vMax = v, tMax = maxOf(t, base.requiredT(v)).coerceAtMost(T_CAP))
        }
    }
}
