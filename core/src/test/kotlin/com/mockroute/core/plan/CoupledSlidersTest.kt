package com.mockroute.core.plan

import com.mockroute.core.plan.CoupledSliders.Companion.T_CAP
import com.mockroute.core.plan.CoupledSliders.Companion.V_CAP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoupledSlidersTest {
    private val kmh = 1 / 3.6
    private val d = 10_000.0
    private val a = 1.3

    private fun start() = CoupledSliders.propose(d, a, vMax = 50 * kmh, tMax = 600.0)

    @Test
    fun proposalKeepsVMaxAndRoundsTimeUpToTheMinute() {
        val s = start()
        assertEquals(50 * kmh, s.vMax, 1e-12)
        assertEquals(13 * 60.0, s.tMax, 1e-9) // 730.7 s → 13:00
        assertTrue(s.isFeasible)
    }

    @Test
    fun decreasingVMaxPushesTMaxExactlyToRequirement() {
        val s = start().withVMax(30 * kmh)
        assertEquals(30 * kmh, s.vMax, 1e-12)
        assertEquals(s.requiredT(30 * kmh), s.tMax, 1e-9)
        assertTrue(s.isFeasible)
    }

    @Test
    fun decreasingTMaxPushesVMaxExactlyToRequirement() {
        val s = start().withTMax(9 * 60.0)
        assertEquals(9 * 60.0, s.tMax, 1e-9)
        assertEquals(s.requiredV(9 * 60.0), s.vMax, 1e-9)
        assertEquals(9 * 60.0, s.requiredT(s.vMax), 1e-6)
        assertTrue(s.isFeasible)
    }

    @Test
    fun decreasesThatAlreadyFitDoNotMoveTheOther() {
        val roomy = CoupledSliders(d, a, vMax = 80 * kmh, tMax = 3600.0)
        val lowerV = roomy.withVMax(60 * kmh) // requiredT(60) ≈ 613 s < 3600 s
        assertEquals(3600.0, lowerV.tMax)
        val lowerT = roomy.withTMax(1800.0) // requiredV(1800) ≈ 5.6 m/s < 22 m/s
        assertEquals(80 * kmh, lowerT.vMax)
    }

    @Test
    fun increasesChangeNothingElse() {
        val s = start()
        val faster = s.withVMax(70 * kmh)
        assertEquals(s.tMax, faster.tMax)
        val longer = s.withTMax(40 * 60.0)
        assertEquals(s.vMax, longer.vMax)
    }

    @Test
    fun capsAndFloorsRespected() {
        val s = start()
        assertEquals(V_CAP, s.withVMax(500.0).vMax)
        assertEquals(T_CAP, s.withTMax(99_999.0).tMax)
        // Floors: v ≥ requiredV(6 h), t ≥ requiredT(100 km/h); pushing to a floor keeps the other within caps.
        val slowest = s.withVMax(0.0)
        assertEquals(s.vFloor, slowest.vMax, 1e-12)
        assertTrue(slowest.tMax <= T_CAP + 1e-9)
        val quickest = s.withTMax(0.0)
        assertEquals(s.tFloor, quickest.tMax, 1e-12)
        assertTrue(quickest.vMax <= V_CAP + 1e-12)
        assertTrue(slowest.isFeasible && quickest.isFeasible)
    }

    @Test
    fun routeTooLongForCaps() {
        // 700 km cannot be done in 6 h at 100 km/h.
        val s = CoupledSliders.propose(700_000.0, a, 50 * kmh, 3600.0)
        assertTrue(s.tooLongForCaps)
        assertFalse(s.isFeasible)
        assertEquals(s, s.withVMax(10.0))
        assertEquals(s, s.withTMax(100.0))
    }

    @Test
    fun applyingToSettingsKeepsRangesOrdered() {
        val settings = SegmentPlanSettings.defaults(com.mockroute.core.route.RoutingProfile.Car, d)
            .let { it.copy(speed = it.speed.withRange(70 * kmh, 80 * kmh)) }
        val c = CoupledSliders.propose(d, settings.aMaxMps2, 40 * kmh, settings.tMax)
        val applied = settings.applyCoupled(c)
        assertEquals(40 * kmh, applied.vMax, 1e-12)
        assertTrue(applied.vMin <= applied.vMax)
        assertTrue(applied.tMin <= applied.tMax)
    }
}
