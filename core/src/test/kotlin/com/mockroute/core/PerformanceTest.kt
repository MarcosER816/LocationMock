package com.mockroute.core

import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import com.mockroute.core.plan.PlanRendering
import com.mockroute.core.plan.SegmentPlanSettings
import com.mockroute.core.plan.SpeedColorScale
import com.mockroute.core.plan.SpeedProfileGenerator
import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.zone.GaussianMixture2D
import com.mockroute.core.zone.LocalFrame
import com.mockroute.core.zone.Polygon2
import com.mockroute.core.zone.Vec2
import com.mockroute.core.zone.ZonePlanner
import kotlin.test.Test
import kotlin.test.assertTrue

/** Stage 10 performance budgets (JVM, after warm-up; the phone is a few times slower, hence the margins). */
class PerformanceTest {
    private fun timeMs(runs: Int, block: (Int) -> Unit): Double {
        block(-1) // warm-up
        val t0 = System.nanoTime()
        repeat(runs) { block(it) }
        return (System.nanoTime() - t0) / 1e6 / runs
    }

    @Test
    fun fiftyKmRoutePlanUnder300ms() {
        // A winding 50 km route: 500 legs of 100 m with alternating headings.
        val pts = ArrayList<LatLon>()
        var p = LatLon(40.0, -3.7)
        pts += p
        repeat(500) { i ->
            p = GeoMath.destination(p, 60.0 + (i % 7) * 12.0, 100.0)
            pts += p
        }
        val g = RouteGeometry.of(pts)
        val st = SegmentPlanSettings.defaults(RoutingProfile.Car, g.lengthM)
        val ms = timeMs(5) { SpeedProfileGenerator.generate(SpeedProfileGenerator.Request(g, st, RoutingProfile.Car, it.toLong() + 1)) }
        println("50 km car plan: %.1f ms".format(ms))
        assertTrue(ms < 300, "50 km plan took $ms ms")
        // Gradient chunks for it (capped to ≤ ~1000 features by the app; 5,000 here as the stress case).
        val plan = SpeedProfileGenerator.generate(SpeedProfileGenerator.Request(g, st, RoutingProfile.Car, 1))
        val chunkMs = timeMs(3) { PlanRendering.chunks(g, plan, SpeedColorScale.forPlan(plan, st), chunkM = g.lengthM / 5000) }
        println("5,000 gradient chunks: %.1f ms".format(chunkMs))
        assertTrue(chunkMs < 300, "5k chunks took $chunkMs ms")
    }

    @Test
    fun zonePlanUnderOneSecond() {
        val frame = LocalFrame(LatLon(40.4168, -3.7038))
        val room = Polygon2(listOf(Vec2(0.0, 0.0), Vec2(0.0, 30.0), Vec2(10.0, 30.0), Vec2(10.0, 10.0), Vec2(30.0, 10.0), Vec2(30.0, 0.0)))
        val st = SegmentPlanSettings.zoneDefaults()
        val a = ZonePlanner.snapToEdge(room, Vec2(15.0, -2.0))
        val b = ZonePlanner.snapToEdge(room, Vec2(-3.0, 25.0))
        val ms = timeMs(10) { ZonePlanner.plan(ZonePlanner.Request(room, frame, a, b, GaussianMixture2D.default(room), st, it.toLong() + 1)) }
        println("Indoor zone plan: %.1f ms".format(ms))
        assertTrue(ms < 1000, "zone plan took $ms ms")
    }
}
