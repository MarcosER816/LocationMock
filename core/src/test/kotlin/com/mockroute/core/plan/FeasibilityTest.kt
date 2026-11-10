package com.mockroute.core.plan

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeasibilityTest {
    private val kmh = 1 / 3.6

    @Test
    fun tenKmAtFiftyKmh() {
        // t = d/v + v/a = 10000/13.889 + 13.889/1.3 = 720 + 10.68 s ≈ 12.2 min.
        val tPhys = Feasibility.tMinPhys(10_000.0, 50 * kmh, 1.3)
        assertEquals(720.0 + (50 * kmh) / 1.3, tPhys, 1e-9)
        val tenMin = Feasibility.check(10_000.0, 50 * kmh, 10 * 60.0, 0.0, 0.0, 1.3)
        assertFalse(tenMin.feasible)
        assertEquals(listOf(Feasibility.Issue.TooSlow), tenMin.issues)
        val fifteen = Feasibility.check(10_000.0, 50 * kmh, 15 * 60.0, 0.0, 0.0, 1.3)
        assertTrue(fifteen.feasible)
        assertEquals(10_000.0 / 900.0, fifteen.requiredAverageMps, 1e-12)
        assertTrue(fifteen.displayRuleOk)
    }

    @Test
    fun triangularProfileForShortDistances() {
        // d = 10 m < v²/a = 13.889²/1.3 ≈ 148 m → never reaches v_max: t = 2√(d/a).
        assertEquals(2 * sqrt(10.0 / 1.3), Feasibility.tMinPhys(10.0, 50 * kmh, 1.3), 1e-12)
        // Continuous at the switch point d = v²/a.
        val v = 10.0
        val a = 2.0
        val dStar = v * v / a
        assertEquals(Feasibility.tMinPhys(dStar - 1e-9, v, a), Feasibility.tMinPhys(dStar, v, a), 1e-6)
    }

    @Test
    fun requiredVInvertsTMinPhys() {
        val d = 3000.0
        val a = 1.0
        for (v in listOf(1.0, 3.0, 8.0, 20.0, 27.7)) {
            if (v > sqrt(d * a)) continue // flat region: not invertible
            val t = Feasibility.tMinPhys(d, v, a)
            assertEquals(v, Feasibility.requiredV(d, t, a), 1e-9, "v=$v")
        }
        assertEquals(Double.POSITIVE_INFINITY, Feasibility.requiredV(d, Feasibility.absoluteMinTime(d, a) * 0.99, a))
    }

    @Test
    fun otherIssues() {
        // v_min·t_min = 2 m/s · 600 s = 1200 m > 1000 m.
        val short = Feasibility.check(1000.0, 3.0, 1200.0, 2.0, 600.0, 1.0)
        assertEquals(listOf(Feasibility.Issue.TooShort), short.issues)
        val inverted = Feasibility.check(1000.0, 1.0, 100.0, 2.0, 200.0, 1.0)
        assertTrue(Feasibility.Issue.SpeedRangeInverted in inverted.issues)
        assertTrue(Feasibility.Issue.TimeRangeInverted in inverted.issues)
        assertTrue(Feasibility.Issue.NoSpeed in Feasibility.check(10.0, 0.0, 100.0, 0.0, 0.0, 1.0).issues)
        assertTrue(Feasibility.check(0.0, 0.0, 0.0, 0.0, 0.0, 1.0).feasible)
    }

    @Test
    fun matchesClaudeMdFormulation() {
        // d ≤ v·(t − v/a)  ⇔  d/v + v/a ≤ t  (trapezoid case).
        val d = 5000.0
        val v = 12.0
        val a = 1.3
        val t = d / v + v / a
        assertEquals(d, v * (t - v / a), 1e-9)
    }
}
