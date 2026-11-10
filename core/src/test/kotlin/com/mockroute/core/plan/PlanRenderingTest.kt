package com.mockroute.core.plan

import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.route.RoutingProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanRenderingTest {
    @Test
    fun colorScaleEndpointsAndOklabRoundTrip() {
        val s = SpeedColorScale(1.0, 2.0, 3.0)
        assertEquals(SpeedColorScale.GREEN, s.colorFor(0.5))
        assertEquals(SpeedColorScale.GREEN, s.colorFor(1.0))
        assertEquals(SpeedColorScale.YELLOW, s.colorFor(2.0))
        assertEquals(SpeedColorScale.RED, s.colorFor(3.0))
        assertEquals(SpeedColorScale.RED, s.colorFor(9.0))
        for (c in listOf(SpeedColorScale.GREEN, SpeedColorScale.YELLOW, SpeedColorScale.RED, 0xFF336699.toInt())) {
            assertEquals(c, SpeedColorScale.fromOklab(SpeedColorScale.toOklab(c)))
        }
        // The midpoint between green and yellow stays bright (no grey/brown dip): OKLab lightness in between.
        val l0 = SpeedColorScale.toOklab(SpeedColorScale.GREEN)[0]
        val l1 = SpeedColorScale.toOklab(SpeedColorScale.YELLOW)[0]
        val lm = SpeedColorScale.toOklab(s.colorFor(1.5))[0]
        assertTrue(lm >= minOf(l0, l1) - 1e-3 && lm <= maxOf(l0, l1) + 1e-3)
        assertEquals("#1E9E48", SpeedColorScale.toHex(SpeedColorScale.GREEN))
    }

    @Test
    fun chunksAreContinuousAndCoverTheRoute() {
        val a = LatLon(41.38, 2.17)
        val g = RouteGeometry.of(a, GeoMath.destination(a, 30.0, 600.0), GeoMath.destination(a, 60.0, 1100.0))
        val st = SegmentPlanSettings.defaults(RoutingProfile.Walk, g.lengthM)
        val plan = SpeedProfileGenerator.generate(SpeedProfileGenerator.Request(g, st, RoutingProfile.Walk, 2))
        val chunks = PlanRendering.chunks(g, plan, SpeedColorScale.forPlan(plan, st), chunkM = 30.0)
        assertTrue(chunks.size in 30..50)
        assertEquals(0.0, chunks.first().startM)
        assertEquals(g.lengthM, chunks.last().endM, 1e-9)
        for (i in 0 until chunks.size - 1) {
            assertEquals(chunks[i].endM, chunks[i + 1].startM, 1e-9)
            assertEquals(0.0, GeoMath.haversineMeters(chunks[i].points.last(), chunks[i + 1].points.first()), 1e-6)
        }
        // The original corner vertex is kept inside its chunk.
        assertTrue(chunks.any { c -> c.points.any { GeoMath.haversineMeters(it, g.points[1]) < 1e-6 } })
        // Time-weighted: Σ chunk length / speed == T.
        val t = chunks.sumOf { (it.endM - it.startM) / it.meanSpeedMps }
        assertEquals(plan.totalTimeS, t, 1.0)
    }

    @Test
    fun projectFindsNearestPoint() {
        val a = LatLon(41.38, 2.17)
        val b = GeoMath.destination(a, 90.0, 1000.0)
        val g = RouteGeometry.of(a, b)
        val off = GeoMath.destination(GeoMath.destination(a, 90.0, 400.0), 0.0, 25.0)
        val (along, dist) = g.project(off)
        assertEquals(400.0, along, 0.5)
        assertEquals(25.0, dist, 0.5)
    }
}
