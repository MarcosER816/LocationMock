package com.mockroute.core.streets

import com.mockroute.core.geo.LatLon
import com.mockroute.core.plan.CenterKind
import com.mockroute.core.plan.GammaSetting
import com.mockroute.core.plan.SegmentPlanSettings
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.zone.GaussianComponent
import com.mockroute.core.zone.GaussianMixture2D
import com.mockroute.core.zone.LocalFrame
import com.mockroute.core.zone.Polygon2
import com.mockroute.core.zone.Vec2
import com.mockroute.core.zone.WanderMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreetTest {
    private val frame = LocalFrame(LatLon(40.4085, -3.7010))
    private val ways = Overpass.parse(requireNotNull(javaClass.getResource("/fixtures/overpass_grid.json")).readText())

    /** L-shaped neighbourhood: the 440 m square minus its north-east corner block. */
    private val area = Polygon2(
        listOf(Vec2(-20.0, -20.0), Vec2(420.0, -20.0), Vec2(420.0, 280.0), Vec2(280.0, 280.0), Vec2(280.0, 420.0), Vec2(-20.0, 420.0)),
    )

    private val walkGraph = RoadGraph.build(ways, area, frame, RoutingProfile.Walk)
    private val carGraph = RoadGraph.build(ways, area, frame, RoutingProfile.Car)

    private val walkSettings = SegmentPlanSettings(
        speed = GammaSetting(0.9, 1.8, 1.35, 6.0, CenterKind.Mean),
        time = GammaSetting(600.0, 1800.0, 1200.0, 4.0),
        aMaxMps2 = 1.0,
    )
    private val carSettings = SegmentPlanSettings(
        speed = GammaSetting(3.0, 14.0, 8.0, 4.0, CenterKind.Mean),
        time = GammaSetting(240.0, 900.0, 480.0, 4.0),
        aMaxMps2 = 1.3,
    )

    private val dominant = GaussianComponent(1.0, 100.0, 300.0, 40.0, 40.0)
    private val biased = GaussianMixture2D(listOf(dominant))
    private val uniform = GaussianMixture2D(listOf(GaussianComponent(1.0, 200.0, 200.0, 1e5, 1e5)))

    private val a = Vec2(-15.0, 2.0) // near the SW corner, snapped onto a road
    private val b = Vec2(205.0, 95.0) // inside, near the (200,100) junction

    @Test
    fun parsesOverpassGeom() {
        assertEquals(16, ways.size)
        assertTrue(ways.all { it.nodeIds.size == it.points.size && it.highway != null })
        val q = Overpass.query(listOf(LatLon(40.0, -3.0), LatLon(40.0, -2.9), LatLon(40.1, -2.9)))
        assertTrue(q.contains("""(poly:"40.000000 -3.000000 40.000000 -2.900000 40.100000 -2.900000")"""), q)
        assertTrue(q.contains("out geom;") && q.startsWith("[out:json]"))
        assertEquals(32, Overpass.cacheKey(q).length)
        assertEquals(50.0, RoadGraph.parseMaxSpeed("50"))
        assertEquals(48.28, RoadGraph.parseMaxSpeed("30 mph")!!, 0.01)
        assertNull(RoadGraph.parseMaxSpeed("none"))
        assertNull(RoadGraph.parseMaxSpeed("ES:urban"))
    }

    @Test
    fun graphRespectsModeFiltersPolygonAndComponents() {
        val walkClasses = walkGraph.edges.map { it.highway }.toSet()
        val carClasses = carGraph.edges.map { it.highway }.toSet()
        assertTrue("footway" in walkClasses && "steps" in walkClasses && "path" in walkClasses)
        assertFalse("motorway" in walkClasses)
        assertFalse(carClasses.any { it in setOf("footway", "steps", "path") })
        // Private service road: nobody. foot=no service road: cars only.
        val privateId = ways.first { it.tags["access"] == "private" }.id
        val noFootId = ways.first { it.tags["foot"] == "no" }.id
        assertTrue(walkGraph.edges.none { it.wayId == privateId } && carGraph.edges.none { it.wayId == privateId })
        assertTrue(walkGraph.edges.none { it.wayId == noFootId } && carGraph.edges.any { it.wayId == noFootId })
        // Motorway segment isn't connected to the grid → dropped as a minor component.
        assertTrue(carGraph.edges.none { it.highway == "motorway" })
        // Every edge lies inside the polygon (the notch and the western stub are cut).
        for (g in listOf(walkGraph, carGraph)) for (e in g.edges) for ((p, q) in e.points.zipWithNext()) {
            assertTrue(area.segmentInside(p, q), "edge segment $p→$q leaves the polygon")
        }
        assertTrue(walkGraph.nodes.none { it.x > 280 && it.y > 280 })
        // One-way street (y = 200) only eastbound for cars, both ways on foot.
        val onewayId = ways.first { it.tags["oneway"] == "yes" }.id
        val carOneway = carGraph.edges.filter { it.wayId == onewayId }
        assertTrue(carOneway.isNotEmpty() && carOneway.all { it.forward && it.points.last().x > it.points.first().x })
        assertTrue(walkGraph.edges.any { it.wayId == onewayId && !it.forward })
        // Strongly connected: from any node you can reach every other.
        for (g in listOf(walkGraph, carGraph)) {
            val (d, _) = g.dijkstra(0)
            assertTrue(d.all { it.isFinite() })
            val (r, _) = g.dijkstra(0, reverse = true)
            assertTrue(r.all { it.isFinite() })
        }
        println("Graph sizes (fixture): walk ${walkGraph.nodeCount} nodes / ${walkGraph.edgeCount} edges, car ${carGraph.nodeCount} / ${carGraph.edgeCount}")
    }

    @Test
    fun snapInsertsNodesOnEdges() {
        val g = walkGraph.copy()
        val before = g.nodeCount
        val n = g.insertNode(Vec2(37.0, 103.0)) // next to the (0..100, 100) street
        assertEquals(before + 1, g.nodeCount)
        assertEquals(0.0, g.nodes[n].distanceTo(Vec2(37.0, 100.0)), 0.01) // fixture coords rounded to 1e-7°
        // Splitting kept the graph strongly connected and twins consistent.
        assertTrue(g.dijkstra(n).first.all { it.isFinite() } && g.dijkstra(n, true).first.all { it.isFinite() })
        // Near an existing node → reuse it.
        assertEquals(before + 1, g.copy().also { it.insertNode(Vec2(100.2, 100.1)) }.nodeCount)
    }

    private fun checkPlan(sp: StreetPlan, settings: SegmentPlanSettings, label: String) {
        val z = sp.zone
        val p = z.plan
        assertTrue(abs(p.totalTimeS - z.targetTimeS) < 1.0, "$label: T ${p.totalTimeS} vs ${z.targetTimeS}")
        assertTrue(z.localPositionAt(0.0).first.distanceTo(sp.graph.nodes[sp.nodeA]) < 1e-6, "$label start")
        assertTrue(z.localPositionAt(p.totalTimeS).first.distanceTo(sp.graph.nodes[sp.nodeB]) < 1e-6, "$label end")
        var t = 0.0
        while (t <= p.totalTimeS) {
            val q = z.localPositionAt(t).first
            assertTrue(sp.graph.distanceToGraph(q) <= 0.5, "$label: $q off the graph at t=$t")
            assertTrue(area.contains(q, 1e-6), "$label: $q outside")
            t += 2.0
        }
        for (i in 0 until p.v.size - 1) {
            val dt = p.t[i + 1] - p.t[i]
            assertTrue(dt > 0 && abs(p.v[i + 1] - p.v[i]) / dt <= settings.aMaxMps2 + 1e-6, "$label accel at $i")
        }
        // The path is a chain of graph edges in their allowed direction.
        for ((e1, e2) in sp.edgePath.zipWithNext()) assertEquals(sp.graph.edges[e1].to, sp.graph.edges[e2].from, "$label chain")
    }

    @Test
    fun walkPlansStayOnRoadsInsideAndOnTime() {
        for (seed in 1L..150L) {
            val sp = StreetPlanner.plan(StreetPlanner.Request(walkGraph, area, frame, a, b, biased, walkSettings, RoutingProfile.Walk, seed, dwellProbability = 0.3))
            checkPlan(sp, walkSettings, "walk $seed")
        }
    }

    @Test
    fun carPlansRespectOnewayAndTurnCaps() {
        val onewayId = ways.first { it.tags["oneway"] == "yes" }.id
        for (seed in 1L..150L) {
            val mode = if (seed % 2 == 0L) WanderMode.Waypoints else WanderMode.Langevin
            val sp = StreetPlanner.plan(StreetPlanner.Request(carGraph, area, frame, a, b, uniform, carSettings, RoutingProfile.Car, seed, mode = mode))
            checkPlan(sp, carSettings, "car $seed")
            // Oneway: every traversed edge of the one-way street goes eastbound.
            for (ei in sp.edgePath) {
                val e = sp.graph.edges[ei]
                if (e.wayId == onewayId) assertTrue(e.forward && e.points.last().x >= e.points.first().x, "car $seed against oneway")
            }
            // ≤ 15 km/h at sharp turns (plus the 50 km/h limit on the primary road).
            val z = sp.zone
            val turns = StreetPlanner.turnPoints(z.path)
            for (s in turns) {
                val (_, v) = z.plan.timeAtDistance(s * z.plan.distanceM / z.lengthM)
                assertTrue(v <= 15 / 3.6 + 0.2, "car $seed: ${v * 3.6} km/h in a turn")
            }
        }
    }

    @Test
    fun dominantGaussianAttractsTime() {
        fun share(mix: GaussianMixture2D): Double {
            var near = 0.0
            var total = 0.0
            for (seed in 1L..60L) {
                val z = StreetPlanner.plan(StreetPlanner.Request(walkGraph, area, frame, a, b, mix, walkSettings, RoutingProfile.Walk, seed)).zone
                var t = 0.0
                while (t < z.durationSeconds) {
                    val q = z.localPositionAt(t).first
                    if (dominant.mahalanobis2(q.x, q.y) <= 4.0) near++
                    total++
                    t += 2.0
                }
            }
            return near / total
        }
        val biasedShare = share(biased)
        val uniformShare = share(uniform)
        println("Time near the dominant Gaussian: biased ${"%.1f".format(biasedShare * 100)} % vs uniform ${"%.1f".format(uniformShare * 100)} %")
        assertTrue(biasedShare > uniformShare + 0.1)
    }

    @Test
    fun planningSpeedOnALargerGrid() {
        // Synthetic 30×30 grid (~3 km × 3 km, 900 junctions) to measure build + plan time.
        val big = ArrayList<OsmWay>()
        var wid = 1L
        fun id(i: Int, j: Int) = 100_000L + i * 1000 + j
        val n = 30
        for (j in 0 until n) big += OsmWay(wid++, LongArray(n) { id(it, j) }, List(n) { frame.toLatLon(Vec2(it * 100.0, j * 100.0)) }, mapOf("highway" to "residential"))
        for (i in 0 until n) big += OsmWay(wid++, LongArray(n) { id(i, it) }, List(n) { frame.toLatLon(Vec2(i * 100.0, it * 100.0)) }, mapOf("highway" to "residential"))
        val poly = Polygon2(listOf(Vec2(-10.0, -10.0), Vec2(2910.0, -10.0), Vec2(2910.0, 2910.0), Vec2(-10.0, 2910.0)))
        val t0 = System.nanoTime()
        val g = RoadGraph.build(big, poly, frame, RoutingProfile.Walk)
        val t1 = System.nanoTime()
        val sp = StreetPlanner.plan(StreetPlanner.Request(g, poly, frame, Vec2(0.0, 0.0), Vec2(2900.0, 2900.0), biased, walkSettings.copy(time = GammaSetting(3600.0, 7200.0, 5400.0, 4.0)), RoutingProfile.Walk, 1))
        val t2 = System.nanoTime()
        println("30×30 grid: ${g.nodeCount} nodes / ${g.edgeCount} edges, build ${(t1 - t0) / 1_000_000} ms, plan ${(t2 - t1) / 1_000_000} ms (${sp.zone.waypoints.size} targets, ${"%.0f".format(sp.zone.lengthM)} m)")
        assertTrue(abs(sp.zone.plan.totalTimeS - sp.zone.targetTimeS) < 1.0)
    }
}
