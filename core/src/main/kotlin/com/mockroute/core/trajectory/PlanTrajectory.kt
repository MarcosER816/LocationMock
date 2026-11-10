package com.mockroute.core.trajectory

import com.mockroute.core.plan.SpeedPlan
import com.mockroute.core.route.RouteGeometry

/**
 * Plays a [SpeedPlan] along its [geometry]: binary search on t_i, constant-acceleration interpolation inside a
 * node interval (continuous speed, piecewise-constant acceleration). Rests at the end after T.
 */
class PlanTrajectory(
    val geometry: RouteGeometry,
    val plan: SpeedPlan,
    private val altitudeM: Double = Trajectory.DEFAULT_ALTITUDE_M,
    private val accuracyM: Double = Trajectory.DEFAULT_ACCURACY_M,
) : Trajectory {
    init {
        require(!geometry.isEmpty) { "empty geometry" }
    }

    override val durationSeconds: Double = plan.totalTimeS

    private val scale = if (plan.distanceM > 0) geometry.lengthM / plan.distanceM else 1.0

    override fun stateAt(tSeconds: Double): KinematicState {
        val k = plan.stateAtTime(tSeconds)
        val p = geometry.pointAtDistance(k.distanceM * scale)
        val speed = if (tSeconds >= durationSeconds) 0.0 else k.speedMps
        return KinematicState(p.position.lat, p.position.lon, altitudeM, speed, p.bearingDeg, accuracyM)
    }

    /** Distance travelled at [tSeconds] (m). */
    fun distanceAt(tSeconds: Double): Double = plan.stateAtTime(tSeconds).distanceM * scale
}
