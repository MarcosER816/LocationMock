package com.mockroute.app.sim

import android.content.Intent
import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.trajectory.PolylineTrajectory
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

    /** Constant-speed playback along a route (temporary until Stage 4 speed profiles). */
    data class Polyline(
        val geometry: RouteGeometry,
        val speedMps: Double,
        override val altitudeM: Double,
    ) : TrajectorySpec {
        override fun build(): Trajectory = PolylineTrajectory(geometry, speedMps, altitudeM)
        override val label: String get() = "Following route"
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
            is Polyline -> {
                putExtra(EXTRA_TYPE, TYPE_POLYLINE)
                putExtra(EXTRA_SPEED, s.speedMps)
                // Flat [lat0, lon0, lat1, lon1, ...]: ~16 B/point, fine for routes of a few thousand points.
                putExtra(EXTRA_POINTS, DoubleArray(s.geometry.size * 2) { i ->
                    val p = s.geometry.points[i / 2]
                    if (i % 2 == 0) p.lat else p.lon
                })
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
        private const val TYPE_POLYLINE = "polyline"
        private const val EXTRA_SPEED = "spec_speed"
        private const val EXTRA_POINTS = "spec_points"

        fun readFrom(intent: Intent): TrajectorySpec? = runCatching {
            val alt = intent.getDoubleExtra(EXTRA_ALT, 0.0)
            val to by lazy { LatLon(intent.getDoubleExtra(EXTRA_TO_LAT, Double.NaN), intent.getDoubleExtra(EXTRA_TO_LON, Double.NaN)) }
            when (intent.getStringExtra(EXTRA_TYPE)) {
                TYPE_STATIC -> Static(to, alt)
                TYPE_LINE -> StraightLine(
                    LatLon(intent.getDoubleExtra(EXTRA_FROM_LAT, Double.NaN), intent.getDoubleExtra(EXTRA_FROM_LON, Double.NaN)),
                    to,
                    alt,
                )
                TYPE_POLYLINE -> {
                    val flat = intent.getDoubleArrayExtra(EXTRA_POINTS) ?: return null
                    val pts = (0 until flat.size / 2).map { LatLon(flat[2 * it], flat[2 * it + 1]) }
                    Polyline(RouteGeometry.of(pts), intent.getDoubleExtra(EXTRA_SPEED, 1.4), alt)
                }
                else -> null
            }
        }.getOrNull()
    }
}
