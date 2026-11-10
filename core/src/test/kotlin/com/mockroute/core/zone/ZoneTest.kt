package com.mockroute.core.zone

import com.mockroute.core.geo.LatLon
import com.mockroute.core.plan.CenterKind
import com.mockroute.core.plan.GammaSetting
import com.mockroute.core.plan.SegmentPlanSettings
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZoneTest {
    private val frame = LocalFrame(LatLon(40.4168, -3.7038))

    /** L-shaped room: a 30×10 m bar plus a 10×30 m bar (area 500 m²), given clockwise to test re-orientation. */
    private val lVertices = listOf(Vec2(0.0, 0.0), Vec2(0.0, 30.0), Vec2(10.0, 30.0), Vec2(10.0, 10.0), Vec2(30.0, 10.0), Vec2(30.0, 0.0))
    private val room = Polygon2(lVertices)

    private val settings = SegmentPlanSettings(
        speed = GammaSetting(0.8, 1.8, 1.25, 6.0, CenterKind.Mean),
        time = GammaSetting(300.0, 900.0, 600.0, 4.0, CenterKind.Mean),
        aMaxMps2 = 1.0,
    )

    private val dominant = GaussianComponent(0.85, 5.0, 20.0, 2.5, 2.5)
    private val mixture = GaussianMixture2D(listOf(dominant, GaussianComponent(0.15, 25.0, 5.0, 3.0, 2.0, 0.4)))

    private val a = ZonePlanner.snapToEdge(room, Vec2(15.0, -2.0)) // bottom wall
    private val b = ZonePlanner.snapToEdge(room, Vec2(-3.0, 25.0)) // left wall

    @Test
    fun polygonBasics() {
        assertEquals(500.0, room.area, 1e-9)
        assertTrue(Polygon2.signedArea(room.vertices) > 0, "stored CCW")
        assertTrue(room.contains(Vec2(5.0, 25.0)))
        assertTrue(room.contains(Vec2(25.0, 5.0)))
        assertFalse(room.contains(Vec2(20.0, 20.0)), "the notch is outside")
        assertTrue(room.contains(Vec2(0.0, 15.0)), "boundary counts as inside")
        // Through the notch: not inside. Around it: inside.
        assertFalse(room.segmentInside(Vec2(25.0, 5.0), Vec2(5.0, 25.0)))
        assertTrue(room.segmentInside(Vec2(25.0, 5.0), Vec2(5.0, 5.0)))
        assertNull(Polygon2.validate(lVertices))
        // Bow-tie is rejected, as are tiny/degenerate ones.
        assertEquals(Polygon2.Companion.Problem.SelfIntersecting, Polygon2.validate(listOf(Vec2(0.0, 0.0), Vec2(10.0, 10.0), Vec2(10.0, 0.0), Vec2(0.0, 10.0))))
        assertEquals(Polygon2.Companion.Problem.TooSmall, Polygon2.validate(listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(0.0, 1.0))))
        assertEquals(Polygon2.Companion.Problem.TooFewVertices, Polygon2.validate(listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0))))
        // A duplicated corner (double tap) is cleaned, not reported as self-intersecting.
        val sq = listOf(Vec2(0.0, 0.0), Vec2(20.0, 0.0), Vec2(20.0, 0.0), Vec2(20.0, 20.0), Vec2(0.0, 20.0), Vec2(0.0, 0.0))
        assertNull(Polygon2.validate(sq))
        assertEquals(4, Polygon2(sq).size)
        // The only reflex vertex of the L is the inner corner (10,10).
        assertEquals(listOf(Vec2(10.0, 10.0)), room.vertices.indices.filter { room.isReflex(it) }.map { room.vertices[it] })
    }

    @Test
    fun snappingAndFrame() {
        assertTrue(room.distanceToBoundary(a) < 0.01 && room.distanceToBoundary(b) < 0.01)
        assertEquals(Vec2(15.0, 0.0), a)
        val ll = frame.toLatLon(Vec2(12.3, -4.5))
        val back = frame.toLocal(ll)
        assertEquals(12.3, back.x, 1e-9)
        assertEquals(-4.5, back.y, 1e-9)
        // An inside point snaps to the nearest wall.
        assertEquals(Vec2(5.0, 30.0), ZonePlanner.snapToEdge(room, Vec2(5.0, 29.0)))
    }

    @Test
    fun shortestPathBendsAtTheInsetCorner() {
        val p = ZonePlanner.shortestPath(room, Vec2(25.0, 5.0), Vec2(5.0, 25.0))
        assertEquals(3, p.size)
        assertTrue(p[1].distanceTo(Vec2(10.0, 10.0)) < 1.0 && room.containsWithMargin(p[1], 0.1), "${p[1]}")
        for ((u, v) in p.zipWithNext()) assertTrue(room.segmentInside(u, v))
    }

    @Test
    fun mixtureDensityAndSampling() {
        val rnd = Random(5)
        // Density integrates to ~1 over a big area.
        var sum = 0.0
        val g = mixture.grid(200, 200, -30.0, 60.0, -30.0, 60.0)
        for (x in g) sum += x
        assertEquals(1.0, sum * (90.0 / 200) * (90.0 / 200), 0.01)
        repeat(2000) { assertTrue(room.containsWithMargin(mixture.sampleInside(room, rnd, 0.4), 0.4)) }
        val def = GaussianMixture2D.default(room)
        assertEquals(1, def.components.size)
        assertTrue(room.contains(Vec2(def.components[0].meanX, def.components[0].meanY)))
    }

    private fun request(seed: Long, mode: WanderMode = WanderMode.Waypoints) = ZonePlanner.Request(
        room, frame, a, b, mixture, settings, seed, dwellProbability = 0.3, dwellMeanS = 30.0, wobble = true, mode = mode,
    )

    @Test
    fun thousandPlansInTheLShapedRoom() {
        var maxTimeErr = 0.0
        for (seed in 1L..1000L) {
            val z = ZonePlanner.plan(request(seed))
            val p = z.plan
            // |T_plan − T| < 1 s.
            val err = abs(p.totalTimeS - z.targetTimeS)
            maxTimeErr = maxOf(maxTimeErr, err)
            assertTrue(err < 1.0, "seed $seed: T ${p.totalTimeS} vs ${z.targetTimeS}")
            // Starts exactly at A, ends exactly at B (A/B on the edge).
            assertTrue(z.localPositionAt(0.0).first.distanceTo(a) < 1e-6, "seed $seed start")
            assertTrue(z.localPositionAt(p.totalTimeS).first.distanceTo(b) < 1e-6, "seed $seed end")
            // Every sampled point (with wobble) inside, path vertices inside, legs inside.
            var t = 0.0
            while (t <= p.totalTimeS) {
                val q = z.localPositionAt(t).first
                assertTrue(room.contains(q, 1e-6), "seed $seed: $q outside at t=$t")
                t += 0.5
            }
            for ((u, v) in z.path.zipWithNext()) assertTrue(room.segmentInside(u, v), "seed $seed leg $u→$v")
            // Acceleration respected (dwell holds have Δv = 0).
            for (i in 0 until p.v.size - 1) {
                val dt = p.t[i + 1] - p.t[i]
                assertTrue(dt > 0)
                assertTrue(abs(p.v[i + 1] - p.v[i]) / dt <= settings.aMaxMps2 + 1e-6, "seed $seed accel at $i")
            }
            assertTrue(p.v.all { it <= settings.vMax + 1e-9 })
        }
        println("1000 zone plans OK, max |T_plan − T| = ${"%.3f".format(maxTimeErr)} s")
    }

    @Test
    fun mostTimeNearTheDominantComponent() {
        var near = 0.0
        var total = 0.0
        for (seed in 1L..60L) {
            val z = ZonePlanner.plan(request(seed))
            var t = 0.0
            while (t < z.durationSeconds) {
                val q = z.localPositionAt(t).first
                if (dominant.mahalanobis2(q.x, q.y) <= 4.0) near++
                total++
                t += 1.0
            }
        }
        val frac = near / total
        println("Time within 2σ of the dominant component: ${"%.1f".format(frac * 100)} %")
        assertTrue(frac > 0.60, "only $frac")
    }

    @Test
    fun langevinModeAlsoStaysInsideAndOnTime() {
        for (seed in 1L..100L) {
            val z = ZonePlanner.plan(request(seed, WanderMode.Langevin))
            assertTrue(abs(z.plan.totalTimeS - z.targetTimeS) < 1.0)
            var t = 0.0
            while (t <= z.durationSeconds) {
                assertTrue(room.contains(z.localPositionAt(t).first, 1e-6))
                t += 1.0
            }
        }
    }

    @Test
    fun feasibilityUsesTheShortestInsidePath() {
        val d = InsidePathFinder.length(ZonePlanner.shortestPath(room, a, b))
        assertTrue(d > 15.0 && d < 40.0, "$d")
        assertTrue(ZonePlanner.feasibility(d, settings).feasible)
        val tooFast = settings.copy(time = GammaSetting(5.0, 10.0, 7.0, 4.0))
        assertFalse(ZonePlanner.feasibility(d, tooFast).feasible)
        // v_min·t_min > d would make a ROUTE infeasible, but not a zone (wandering adds length).
        assertFalse(settings.feasibility(d).feasible)
        assertTrue(ZonePlanner.feasibility(d, settings).feasible)
    }

    @Test
    fun definitionRoundTripAndReadiness() {
        val verts = lVertices.map(frame::toLatLon)
        val def = ZoneDefinition(verts, closed = true, entry = frame.toLatLon(a), exit = frame.toLatLon(b))
        assertTrue(def.isReady())
        val f = assertNotNull(def.frame())
        val poly = assertNotNull(def.polygon(f))
        assertEquals(500.0, poly.area, 0.01)
        val json = kotlinx.serialization.json.Json.encodeToString(ZoneDefinition.serializer(), def)
        assertEquals(def, kotlinx.serialization.json.Json.decodeFromString(ZoneDefinition.serializer(), json))
        assertFalse(def.copy(closed = false).isReady())
    }
}
