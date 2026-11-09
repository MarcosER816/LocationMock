package com.mockroute.core.trajectory

import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon

/** Instantaneous simulated state. SI units: metres, m/s, degrees. */
data class KinematicState(
    val lat: Double,
    val lon: Double,
    val altitudeM: Double,
    val speedMps: Double,
    /** Degrees clockwise from true north, [0, 360). */
    val bearingDeg: Double,
    /** Horizontal 68 % accuracy radius in metres. */
    val accuracyM: Double,
) {
    val position: LatLon get() = LatLon(lat, lon)
}

/** A time-parameterised path. The mock service samples it once per second. */
interface Trajectory {
    /** Total duration in seconds, or `null` if the trajectory never ends (e.g. a static point). */
    val durationSeconds: Double?

    /** State at simulation time [tSeconds] (≥ 0). After [durationSeconds] the final state is held at rest. */
    fun stateAt(tSeconds: Double): KinematicState

    fun isFinished(tSeconds: Double): Boolean = durationSeconds?.let { tSeconds >= it } ?: false

    companion object {
        const val DEFAULT_ACCURACY_M: Double = 5.0
        const val DEFAULT_ALTITUDE_M: Double = 0.0
    }
}

/** Stays at one point forever. */
class StaticTrajectory(
    val point: LatLon,
    private val altitudeM: Double = Trajectory.DEFAULT_ALTITUDE_M,
    private val accuracyM: Double = Trajectory.DEFAULT_ACCURACY_M,
    private val bearingDeg: Double = 0.0,
) : Trajectory {
    override val durationSeconds: Double? = null

    override fun stateAt(tSeconds: Double): KinematicState =
        KinematicState(point.lat, point.lon, altitudeM, 0.0, bearingDeg, accuracyM)
}

/** Constant-speed great-circle walk from [from] to [to], then rests at [to]. */
class StraightLineTrajectory(
    val from: LatLon,
    val to: LatLon,
    val speedMps: Double = WALKING_SPEED_MPS,
    private val altitudeM: Double = Trajectory.DEFAULT_ALTITUDE_M,
    private val accuracyM: Double = Trajectory.DEFAULT_ACCURACY_M,
) : Trajectory {
    init {
        require(speedMps > 0) { "speed must be > 0" }
    }

    val distanceM: Double = GeoMath.haversineMeters(from, to)
    private val path = listOf(from, to)
    private val finalBearing: Double = GeoMath.initialBearingDeg(from, to)

    override val durationSeconds: Double = distanceM / speedMps

    override fun stateAt(tSeconds: Double): KinematicState {
        val t = tSeconds.coerceAtLeast(0.0)
        if (t >= durationSeconds) {
            return KinematicState(to.lat, to.lon, altitudeM, 0.0, finalBearing, accuracyM)
        }
        val p = GeoMath.pointAtDistance(path, t * speedMps)
        // Bearing toward the destination from the current point (great-circle heading changes slowly).
        val bearing = GeoMath.initialBearingDeg(p.position, to)
        return KinematicState(p.position.lat, p.position.lon, altitudeM, speedMps, bearing, accuracyM)
    }

    companion object {
        /** ≈ 5 km/h. */
        const val WALKING_SPEED_MPS: Double = 1.4
    }
}
