package com.mockroute.core.route

import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteGeometryTest {
    private val a = LatLon(49.41461, 8.681495)
    private val b = GeoMath.destination(a, 90.0, 95.0)
    private val c = GeoMath.destination(b, 0.0, 33.0)
    private val d = GeoMath.destination(c, 225.0, 7.0)
    private val g = RouteGeometry.of(a, b, c, d)

    @Test
    fun cumulativeDistances() {
        assertEquals(0.0, g.cumulativeM(0))
        assertEquals(95.0, g.cumulativeM(1), 1e-6)
        assertEquals(128.0, g.cumulativeM(2), 1e-6)
        assertEquals(135.0, g.cumulativeM(3), 1e-6)
        assertEquals(135.0, g.lengthM, 1e-6)
    }

    @Test
    fun densifyKeepsShapeAndSpacing() {
        val dense = g.densify(10.0)
        val cum = dense.cumulativeDistances()
        for (i in 1 until cum.size) {
            val gap = cum[i] - cum[i - 1]
            assertTrue(gap <= 10.0 + 1e-9, "gap $gap at $i")
            assertTrue(gap >= 0.0)
        }
        assertEquals(g.lengthM, dense.lengthM, 1e-6)
        // 95 m → 10 pieces, 33 m → 4, 7 m → 1: 15 segments, 16 points.
        assertEquals(16, dense.size)
        // Original vertices are preserved.
        assertTrue(listOf(a, b, c, d).all { it in dense.points })
        assertEquals(dense, dense.densify(10.0))
    }

    @Test
    fun pointAtDistanceBinarySearch() {
        val p = g.pointAtDistance(100.0)
        assertEquals(1, p.segmentIndex)
        assertEquals(5.0, GeoMath.haversineMeters(b, p.position), 1e-3)
        assertEquals(0.0, p.bearingDeg, 0.1)
        assertEquals(0.0, GeoMath.haversineMeters(a, g.pointAtDistance(-1.0).position), 1e-9)
        assertEquals(0.0, GeoMath.haversineMeters(d, g.pointAtDistance(1e6).position), 1e-9)
        // Agrees with the linear GeoMath version everywhere.
        var s = 0.0
        while (s <= g.lengthM) {
            val fast = g.pointAtDistance(s).position
            val slow = GeoMath.pointAtDistance(g.points, s).position
            assertEquals(0.0, GeoMath.haversineMeters(fast, slow), 1e-6, "at $s")
            s += 3.7
        }
    }

    @Test
    fun simplifyDropsCollinearPoints() {
        val dense = g.densify(5.0)
        val simple = dense.simplify(0.5)
        assertEquals(4, simple.size)
        assertEquals(dense.start, simple.start)
        assertEquals(dense.end, simple.end)
    }

    @Test
    fun concatenationDropsDuplicateJoint() {
        val first = RouteGeometry.of(a, b)
        val second = RouteGeometry.of(b, c)
        val joined = first + second
        assertEquals(3, joined.size)
        assertEquals(128.0, joined.lengthM, 1e-6)
        assertEquals(first, first + RouteGeometry.EMPTY)
        assertEquals(second, RouteGeometry.EMPTY + second)
    }

    @Test
    fun serializationRoundTrip() {
        val json = Json.encodeToString(RouteGeometry.serializer(), g)
        assertEquals(g, Json.decodeFromString(RouteGeometry.serializer(), json))
    }
}
