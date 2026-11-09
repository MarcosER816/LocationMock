package com.mockroute.core.trajectory

import com.mockroute.core.route.RouteGeometry

/**
 * Temporary Stage 2 playback: constant speed along a [RouteGeometry], then rests at the end.
 * Replaced by speed profiles in Stage 4.
 */
class PolylineTrajectory(
    val geometry: RouteGeometry,
    val speedMps: Double,
    private val altitudeM: Double = Trajectory.DEFAULT_ALTITUDE_M,
    private val accuracyM: Double = Trajectory.DEFAULT_ACCURACY_M,
) : Trajectory {
    init {
        require(!geometry.isEmpty) { "empty geometry" }
        require(speedMps > 0) { "speed must be > 0" }
    }

    override val durationSeconds: Double = geometry.lengthM / speedMps

    private val finalBearing = geometry.pointAtDistance(geometry.lengthM).bearingDeg

    override fun stateAt(tSeconds: Double): KinematicState {
        val t = tSeconds.coerceAtLeast(0.0)
        if (t >= durationSeconds) {
            val end = geometry.end!!
            return KinematicState(end.lat, end.lon, altitudeM, 0.0, finalBearing, accuracyM)
        }
        val p = geometry.pointAtDistance(t * speedMps)
        return KinematicState(p.position.lat, p.position.lon, altitudeM, speedMps, p.bearingDeg, accuracyM)
    }
}
