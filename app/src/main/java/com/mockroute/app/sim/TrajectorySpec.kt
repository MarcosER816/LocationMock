package com.mockroute.app.sim

import android.content.Intent
import com.mockroute.core.geo.LatLon
import com.mockroute.core.trajectory.StaticTrajectory
import com.mockroute.core.trajectory.StraightLineTrajectory
import com.mockroute.core.trajectory.Trajectory

/** Serializable description of what the service should play (passed via Intent extras). */
sealed interface TrajectorySpec {
    val altitudeM: Double
    fun build(): Trajectory
    val label: String

    data class Static(val point: LatLon, override val altitudeM: Double) : TrajectorySpec {
        override fun build(): Trajectory = StaticTrajectory(point, altitudeM)
        override val label: String get() = "Holding position"
    }

    data class StraightLine(val from: LatLon, val to: LatLon, override val altitudeM: Double) : TrajectorySpec {
        override fun build(): Trajectory = StraightLineTrajectory(from, to, altitudeM = altitudeM)
        override val label: String get() = "Walking straight"
    }

    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_ALT, altitudeM)
        when (val s = this@TrajectorySpec) {
            is Static -> {
                putExtra(EXTRA_TYPE, TYPE_STATIC)
                putExtra(EXTRA_TO_LAT, s.point.lat)
                putExtra(EXTRA_TO_LON, s.point.lon)
            }
            is StraightLine -> {
                putExtra(EXTRA_TYPE, TYPE_LINE)
                putExtra(EXTRA_FROM_LAT, s.from.lat)
                putExtra(EXTRA_FROM_LON, s.from.lon)
                putExtra(EXTRA_TO_LAT, s.to.lat)
                putExtra(EXTRA_TO_LON, s.to.lon)
            }
        }
    }

    companion object {
        private const val EXTRA_TYPE = "spec_type"
        private const val EXTRA_ALT = "spec_alt"
        private const val EXTRA_FROM_LAT = "spec_from_lat"
        private const val EXTRA_FROM_LON = "spec_from_lon"
        private const val EXTRA_TO_LAT = "spec_to_lat"
        private const val EXTRA_TO_LON = "spec_to_lon"
        private const val TYPE_STATIC = "static"
        private const val TYPE_LINE = "line"

        fun readFrom(intent: Intent): TrajectorySpec? = runCatching {
            val alt = intent.getDoubleExtra(EXTRA_ALT, 0.0)
            val to = LatLon(intent.getDoubleExtra(EXTRA_TO_LAT, Double.NaN), intent.getDoubleExtra(EXTRA_TO_LON, Double.NaN))
            when (intent.getStringExtra(EXTRA_TYPE)) {
                TYPE_STATIC -> Static(to, alt)
                TYPE_LINE -> StraightLine(
                    LatLon(intent.getDoubleExtra(EXTRA_FROM_LAT, Double.NaN), intent.getDoubleExtra(EXTRA_FROM_LON, Double.NaN)),
                    to,
                    alt,
                )
                else -> null
            }
        }.getOrNull()
    }
}
