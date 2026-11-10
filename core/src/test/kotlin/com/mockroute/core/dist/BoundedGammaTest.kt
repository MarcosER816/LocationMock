package com.mockroute.core.dist

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundedGammaTest {
    /** Composite Simpson on [a, b] with n (even) intervals. */
    private fun simpson(a: Double, b: Double, n: Int, f: (Double) -> Double): Double {
        val h = (b - a) / n
        var s = f(a) + f(b)
        for (i in 1 until n) s += f(a + i * h) * if (i % 2 == 1) 4 else 2
        return s * h / 3
    }

    private val cases = listOf(
        // lo, hi, shape, mean  (speeds in m/s, times in s)
        doubleArrayOf(0.8, 1.8, 6.0, 1.4),
        doubleArrayOf(2.0, 16.0, 1.5, 5.0),
        doubleArrayOf(600.0, 1800.0, 4.0, 1000.0),
        doubleArrayOf(0.0, 27.7, 30.0, 20.0),
        doubleArrayOf(0.0, 10.0, 1.0, 3.0),
    )

    @Test
    fun pdfIntegratesToOne() {
        for (c in cases) {
            val g = BoundedGamma.withMean(c[0], c[1], c[2], c[3])
            val integral = simpson(g.lo, g.hi, 20_000) { g.pdf(it) }
            assertEquals(1.0, integral, 1e-3, "∫pdf for $g")
        }
        // Shape < 1 has an integrable singularity at lo: substitute x = lo + u² (dx = 2u du) to make it smooth.
        val s = BoundedGamma.withMean(0.0, 10.0, 0.5, 2.0)
        val integral = simpson(1e-12, kotlin.math.sqrt(10.0), 20_000) { u -> s.pdf(u * u) * 2 * u }
        assertEquals(1.0, integral, 1e-3)
    }

    @Test
    fun truncatedMeanMatchesTarget() {
        for (c in cases) {
            val g = BoundedGamma.withMean(c[0], c[1], c[2], c[3])
            assertEquals(c[3], g.mean, 1e-6 * (c[1] - c[0]), "solved mean for $g")
            val numeric = simpson(g.lo, g.hi, 20_000) { it * g.pdf(it) }
            assertEquals(c[3], numeric, 1e-3 * c[3], "numeric mean for $g")
        }
    }

    @Test
    fun samplesInsideBoundsWithCorrectMean() {
        val rnd = Random(42)
        for (c in cases) {
            val g = BoundedGamma.withMean(c[0], c[1], c[2], c[3])
            var sum = 0.0
            val n = 100_000
            repeat(n) {
                val x = g.sample(rnd)
                assertTrue(x >= g.lo && x <= g.hi, "sample $x outside [${g.lo}, ${g.hi}]")
                sum += x
            }
            val m = sum / n
            assertTrue(abs(m - c[3]) <= 0.02 * c[3], "sample mean $m vs ${c[3]} for $g")
        }
    }

    @Test
    fun quantileInvertsCdf() {
        for (c in cases) {
            val g = BoundedGamma.withMean(c[0], c[1], c[2], c[3])
            for (i in 1..49) {
                val x = g.lo + (g.hi - g.lo) * i / 50.0
                assertEquals(x, g.quantile(g.cdf(x)), 1e-6 * (g.hi - g.lo), "q(F(x)) at $x for $g")
            }
            assertEquals(g.lo, g.quantile(0.0))
            assertEquals(g.hi, g.quantile(1.0))
            assertEquals(0.0, g.cdf(g.lo - 1))
            assertEquals(1.0, g.cdf(g.hi + 1))
        }
    }

    @Test
    fun modeParameterisation() {
        val g = BoundedGamma.withMode(1.0, 2.0, 5.0, 1.3)
        assertEquals(1.3, g.mode, 1e-12)
        // Density peaks at the mode.
        assertTrue(g.pdf(1.3) > g.pdf(1.25) && g.pdf(1.3) > g.pdf(1.35))
    }

    @Test
    fun unreachableMeanIsClampedToMaxMean() {
        // Shape 1 on [0,10]: the largest truncated mean is 10·1/2 = 5.
        val g = BoundedGamma.withMean(0.0, 10.0, 1.0, 9.0)
        assertEquals(BoundedGamma.maxMean(0.0, 10.0, 1.0), g.mean, 1e-3)
        assertTrue(g.mean < 9.0)
    }

    @Test
    fun degeneratePointMass() {
        val g = BoundedGamma.withMean(5.0, 5.0, 3.0, 5.0)
        assertTrue(g.isPointMass)
        assertEquals(5.0, g.sample(Random(1)))
        assertEquals(5.0, g.quantile(0.3))
        assertEquals(0.0, g.cdf(4.9))
        assertEquals(1.0, g.cdf(5.0))
        assertEquals(5.0, g.mean)
    }

    @Test
    fun momentsConstructor() {
        val (k, theta) = BoundedGamma.shapeScaleFromMoments(4.0, 2.0)
        assertEquals(8.0, k, 1e-12)
        assertEquals(0.5, theta, 1e-12)
        // Wide bounds → truncation negligible → mean ≈ lo + m.
        val g = BoundedGamma.fromMoments(10.0, 1000.0, 4.0, 2.0)
        assertEquals(14.0, g.mean, 1e-9)
    }
}
