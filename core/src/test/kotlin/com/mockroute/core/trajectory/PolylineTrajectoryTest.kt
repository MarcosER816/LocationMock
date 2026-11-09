package com.mockroute.core.trajectory

import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.RouteGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolylineTrajectoryTest {
    private val a = LatLon(40.0, -3.0)
    private val b = GeoMath.destination(a, 90.0, 100.0)
    private val c = GeoMath.destination(b, 0.0, 50.0)
    private val traj = PolylineTrajectory(RouteGeometry.of(a, b, c), speedMps = 5.0)

    @Test
    fun followsTheBendAtConstantSpeed() {
        assertEquals(30.0, traj.durationSeconds, 1e-6)
        val s10 = traj.stateAt(10.0) // 50 m along first leg
        assertEquals(50.0, GeoMath.haversineMeters(a, s10.position), 1e-3)
        assertEquals(90.0, s10.bearingDeg, 0.1)
        assertEquals(5.0, s10.speedMps)
        val s25 = traj.stateAt(25.0) // 125 m → 25 m up the second leg
        assertEquals(25.0, GeoMath.haversineMeters(b, s25.position), 1e-3)
        assertEquals(0.0, s25.bearingDeg, 0.1)
    }

    @Test
    fun restsAtEnd() {
        assertTrue(traj.isFinished(30.001))
        val end = traj.stateAt(99.0)
        assertEquals(c, end.position)
        assertEquals(0.0, end.speedMps)
    }
}
