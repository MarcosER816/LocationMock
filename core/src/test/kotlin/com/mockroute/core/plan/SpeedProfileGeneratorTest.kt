package com.mockroute.core.plan

import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.trajectory.PlanTrajectory
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedProfileGeneratorTest {
    private val kmh = 1 / 3.6
    private val start = LatLon(40.4168, -3.7038)

    private fun straight(d: Double) = RouteGeometry.of(start, GeoMath.destination(start, 70.0, d)).densify(100.0)

    private fun carSettings(d: Double) = SegmentPlanSettings(
        speed = GammaSetting(15 * kmh, 70 * kmh, 40 * kmh, 6.0),
        time = GammaSetting(Feasibility.tMinPhys(d, 70 * kmh, 1.3) + 60, d / (15 * kmh) * 0.6, 0.0, 4.0)
            .let { it.copy(center = (it.lo + it.hi) / 2) },
        aMaxMps2 = 1.3,
    )

    private fun walkSettings(d: Double) = SegmentPlanSettings.defaults(RoutingProfile.Walk, d)

    private fun req(g: RouteGeometry, st: SegmentPlanSettings, p: RoutingProfile, seed: Long, v0: Double = 0.0, vEnd: Double = 0.0) =
        SpeedProfileGenerator.Request(g, st, p, seed, v0 = v0, vEnd = vEnd)

    /** All the per-plan invariants from the acceptance criteria. */
    private fun assertValid(plan: SpeedPlan, st: SegmentPlanSettings, v0: Double, vEnd: Double, checkFloor: Boolean = true) {
        assertNull(plan.warning, "unexpected warning")
        assertTrue(abs(plan.totalTimeS - plan.targetTimeS) < 1.0, "T ${plan.totalTimeS} vs target ${plan.targetTimeS}")
        val a = st.aMaxMps2
        val floor = maxOf(st.vMin, SpeedProfileGenerator.MIN_SPEED_MPS)
        assertEquals(v0, plan.v.first(), 1e-12, "start speed")
        assertEquals(vEnd, plan.v.last(), 1e-12, "end speed")
        for (i in plan.v.indices) {
            assertTrue(plan.v[i] <= st.vMax + 1e-9, "v[$i]=${plan.v[i]} > vMax")
            if (checkFloor) {
                // Below the floor only where the boundary ramps force it.
                val ramp = minOf(sqrt(v0 * v0 + 2 * a * plan.s[i]), sqrt(vEnd * vEnd + 2 * a * (plan.distanceM - plan.s[i])))
                assertTrue(plan.v[i] >= minOf(floor, ramp) - 1e-9, "v[$i]=${plan.v[i]} below floor/ramp")
            }
        }
        for (i in 0 until plan.v.size - 1) {
            val dt = plan.t[i + 1] - plan.t[i]
            assertTrue(dt > 0, "non-increasing time at $i")
            val acc = abs(plan.v[i + 1] - plan.v[i]) / dt
            assertTrue(acc <= a + 1e-6, "|Δv/Δt|=$acc > a_max at $i")
        }
    }

    @Test
    fun walkPlanMeetsAllInvariants() {
        val d = 1_800.0
        val st = walkSettings(d)
        for (seed in 1L..20L) assertValid(SpeedProfileGenerator.generate(req(straight(d), st, RoutingProfile.Walk, seed)), st, 0.0, 0.0)
    }

    @Test
    fun carPlanMeetsAllInvariantsWithBoundarySpeeds() {
        val d = 8_000.0
        val st = carSettings(d)
        for (seed in 1L..10L) {
            val plan = SpeedProfileGenerator.generate(req(straight(d), st, RoutingProfile.Car, seed, v0 = 8.0, vEnd = 3.0))
            assertValid(plan, st, 8.0, 3.0)
        }
    }

    @Test
    fun sameSeedSamePlanDifferentSeedDifferentPlan() {
        val d = 3_000.0
        val st = walkSettings(d)
        val a = SpeedProfileGenerator.generate(req(straight(d), st, RoutingProfile.Walk, 7))
        val b = SpeedProfileGenerator.generate(req(straight(d), st, RoutingProfile.Walk, 7))
        val c = SpeedProfileGenerator.generate(req(straight(d), st, RoutingProfile.Walk, 8))
        assertTrue(a.contentEquals(b))
        assertFalse(a.contentEquals(c))
    }

    @Test
    fun lockTimeUsesTheTimeMean() {
        val d = 2_000.0
        val st = walkSettings(d)
        val r = req(straight(d), st, RoutingProfile.Walk, 3).copy(lockTime = true)
        val plan = SpeedProfileGenerator.generate(r)
        assertEquals(st.time.distribution().mean, plan.targetTimeS, 1e-6)
        assertTrue(abs(plan.totalTimeS - plan.targetTimeS) < 1.0)
    }

    @Test
    fun unreachableTargetReturnsClosestWithWarning() {
        val d = 2_000.0
        // Point-mass time far beyond what v_min allows... clamped to d/v_min; force with a tiny v range instead.
        val st = SegmentPlanSettings(
            speed = GammaSetting(4 * kmh, 5 * kmh, 4.5 * kmh, 6.0),
            time = GammaSetting(10 * 60.0, 10 * 60.0, 10 * 60.0, 4.0), // 10 min for 2 km needs 12 km/h
            aMaxMps2 = 1.0,
        )
        val plan = SpeedProfileGenerator.generate(req(straight(d), st, RoutingProfile.Walk, 1))
        assertTrue(plan.warning != null)
        assertTrue(plan.v.all { it <= st.vMax + 1e-9 })
    }

    @Test
    fun curvatureCapSlowsCarsInSharpBends() {
        // 1 km east, then a hairpin back west: radius ~ a few metres at the turn.
        val a = start
        val b = GeoMath.destination(a, 90.0, 1000.0)
        val c = GeoMath.destination(b, 180.0, 20.0)
        val e = GeoMath.destination(c, 270.0, 1000.0)
        val g = RouteGeometry.of(a, b, c, e).densify(5.0)
        val st = carSettings(g.lengthM)
        val plan = SpeedProfileGenerator.generate(req(g, st, RoutingProfile.Car, 5))
        val iTurn = plan.segmentAtDistance(1010.0)
        assertTrue(plan.v[iTurn] < 25 * kmh, "speed in the hairpin ${plan.v[iTurn] * 3.6} km/h")
        // Still physically consistent.
        for (i in 0 until plan.v.size - 1) {
            assertTrue(abs(plan.v[i + 1] - plan.v[i]) / (plan.t[i + 1] - plan.t[i]) <= st.aMaxMps2 + 1e-6)
        }
    }

    /**
     * Copula marginal: with δ = 0 the TIME-weighted speed distribution over many plans should match the user's
     * speed distribution f. Reports the Kolmogorov–Smirnov statistic.
     */
    @Test
    fun timeWeightedSpeedHistogramMatchesDistribution() {
        val d = 40_000.0
        val st = SegmentPlanSettings(
            speed = GammaSetting(20 * kmh, 90 * kmh, 50 * kmh, 4.0),
            time = GammaSetting(1800.0, 7200.0, 3600.0, 4.0),
            aMaxMps2 = 2.0,
        )
        val g = straight(d)
        val speeds = ArrayList<Double>()
        val weights = ArrayList<Double>()
        for (seed in 1L..40L) {
            val r = SpeedProfileGenerator.Request(g, st, RoutingProfile.Car, seed, curvatureCap = false, stepM = 10.0)
            val plan = SpeedProfileGenerator.generateInternal(r, fixedDelta = 0.0)
            for (i in 0 until plan.v.size - 1) {
                speeds += 0.5 * (plan.v[i] + plan.v[i + 1])
                weights += plan.t[i + 1] - plan.t[i]
            }
        }
        val order = speeds.indices.sortedBy { speeds[it] }
        val total = weights.sum()
        val f = st.speed.distribution()
        var cum = 0.0
        var ks = 0.0
        for (idx in order) {
            val before = cum / total
            cum += weights[idx]
            val after = cum / total
            val model = f.cdf(speeds[idx])
            ks = maxOf(ks, abs(before - model), abs(after - model))
        }
        println("KS statistic (time-weighted speeds vs speed BoundedGamma, 40 plans × 40 km): ${"%.4f".format(ks)}")
        assertTrue(ks < 0.05, "KS $ks too large")
    }

    @Test
    fun twentyKmGenerationTime() {
        val d = 20_000.0
        val st = carSettings(d)
        val g = straight(d)
        SpeedProfileGenerator.generate(req(g, st, RoutingProfile.Car, 1)) // warm-up (JIT)
        val runs = 5
        val t0 = System.nanoTime()
        repeat(runs) { SpeedProfileGenerator.generate(req(g, st, RoutingProfile.Car, it.toLong())) }
        val ms = (System.nanoTime() - t0) / 1e6 / runs
        println("20 km car plan (2000 nodes, curvature cap on): %.1f ms per plan (JVM, after warm-up)".format(ms))
        assertTrue(ms < 2_000)
    }

    @Test
    fun planTrajectoryIsContinuousAndConsistent() {
        val d = 1_500.0
        val st = walkSettings(d)
        val g = straight(d)
        val plan = SpeedProfileGenerator.generate(req(g, st, RoutingProfile.Walk, 11))
        val traj = PlanTrajectory(g, plan)
        assertEquals(plan.totalTimeS, traj.durationSeconds)
        assertEquals(0.0, GeoMath.haversineMeters(traj.stateAt(0.0).position, g.start!!), 1e-6)
        assertEquals(0.0, GeoMath.haversineMeters(traj.stateAt(plan.totalTimeS + 10).position, g.end!!), 1e-6)
        var t = 0.0
        while (t + 1 <= plan.totalTimeS) {
            val moved = traj.distanceAt(t + 1) - traj.distanceAt(t)
            // Distance covered in 1 s lies between the min and max speed within that second.
            val speeds = (0..20).map { traj.stateAt(t + it / 20.0).speedMps }
            assertTrue(moved >= speeds.min() - 1e-3 && moved <= speeds.max() + 1e-3, "t=$t moved=$moved speeds=${speeds.min()}..${speeds.max()}")
            t += 1.0
        }
        // timeAtDistance inverts stateAtTime.
        for (frac in listOf(0.1, 0.37, 0.5, 0.93)) {
            val (tt, _) = plan.timeAtDistance(frac * d)
            assertEquals(frac * d, plan.stateAtTime(tt).distanceM, 1e-6)
        }
    }
}
