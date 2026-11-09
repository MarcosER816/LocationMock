package com.mockroute.core.trajectory

import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrajectoryTest {
    private val a = LatLon(40.4168, -3.7038)
    private val b = GeoMath.destination(a, 60.0, 1400.0)

    @Test
    fun staticNeverEnds() {
        val t = StaticTrajectory(a, altitudeM = 650.0)
        assertNull(t.durationSeconds)
        assertFalse(t.isFinished(1e9))
        val s = t.stateAt(1234.0)
        assertEquals(a, s.position)
        assertEquals(0.0, s.speedMps)
        assertEquals(650.0, s.altitudeM)
    }

    @Test
    fun straightLineWalksAtFiveKmh() {
        val t = StraightLineTrajectory(a, b)
        assertEquals(1.4, t.speedMps)
        assertEquals(1000.0, t.durationSeconds, 1e-6) // 1400 m / 1.4 m/s

        val s0 = t.stateAt(0.0)
        assertEquals(0.0, GeoMath.haversineMeters(s0.position, a), 1e-6)
        assertEquals(1.4, s0.speedMps)
        assertEquals(60.0, s0.bearingDeg, 0.1)

        val half = t.stateAt(500.0)
        assertEquals(700.0, GeoMath.haversineMeters(a, half.position), 1e-3)
        assertEquals(700.0, GeoMath.haversineMeters(half.position, b), 1e-3)

        // Successive 1 Hz samples are 1.4 m apart, consistent with the reported speed.
        val s1 = t.stateAt(100.0)
        val s2 = t.stateAt(101.0)
        assertEquals(s1.speedMps, GeoMath.haversineMeters(s1.position, s2.position), 1e-3)
    }

    @Test
    fun straightLineRestsAtDestination() {
        val t = StraightLineTrajectory(a, b)
        assertFalse(t.isFinished(999.0))
        assertTrue(t.isFinished(1000.0))
        val end = t.stateAt(5000.0)
        assertEquals(0.0, GeoMath.haversineMeters(end.position, b), 1e-6)
        assertEquals(0.0, end.speedMps)
    }

    @Test
    fun zeroLengthWalkIsImmediatelyFinished() {
        val t = StraightLineTrajectory(a, a)
        assertEquals(0.0, t.durationSeconds)
        assertTrue(t.isFinished(0.0))
        assertEquals(a, t.stateAt(0.0).position)
    }
}
