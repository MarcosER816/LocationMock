package com.mockroute.core.dist

import org.apache.commons.math3.distribution.GammaDistribution
import org.apache.commons.math3.special.Gamma
import kotlin.math.exp
import kotlin.random.Random

/**
 * X = lo + Y with Y ~ Gamma(shape k, scale θ) truncated to [0, hi − lo].
 *
 * pdf(x) = f(x − lo) / Z, cdf(x) = (F(x − lo) − F(0)) / Z with Z = F(W) − F(0), W = hi − lo (F(0) = 0 for a
 * gamma, kept in the formulas for clarity). Sampling is inverse-CDF: quantile(U), U ~ Uniform(0,1).
 * Degenerate lo == hi → point mass at lo.
 *
 * Build with [withMean] / [withMode] (θ solved so the TRUNCATED distribution matches), [fromMoments] or
 * [pointMass]. Immutable and thread-safe.
 */
class BoundedGamma private constructor(
    val lo: Double,
    val hi: Double,
    /** Shape k (> 0). */
    val shape: Double,
    /** Scale θ (> 0); NaN for a point mass. */
    val scale: Double,
) {
    val width: Double = hi - lo
    val isPointMass: Boolean = width <= 0.0

    private val gamma: GammaDistribution? =
        if (isPointMass) null else GammaDistribution(null, shape, scale, GammaDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY)
    private val f0: Double = 0.0
    /** Probability mass of the untruncated gamma inside [0, W]. */
    private val mass: Double = if (isPointMass) 1.0 else Gamma.regularizedGammaP(shape, width / scale) - f0

    /** Mean of the truncated distribution (closed form via regularized incomplete gamma). */
    val mean: Double = if (isPointMass) lo else lo + truncatedMeanY(shape, scale, width)

    /** Mode of the truncated distribution. */
    val mode: Double = when {
        isPointMass -> lo
        shape <= 1.0 -> lo
        else -> lo + ((shape - 1.0) * scale).coerceAtMost(width)
    }

    fun pdf(x: Double): Double {
        if (isPointMass) return if (x == lo) Double.POSITIVE_INFINITY else 0.0
        val y = x - lo
        if (y < 0.0 || y > width) return 0.0
        return gamma!!.density(y) / mass
    }

    fun cdf(x: Double): Double {
        if (isPointMass) return if (x < lo) 0.0 else 1.0
        val y = x - lo
        if (y <= 0.0) return 0.0
        if (y >= width) return 1.0
        return ((Gamma.regularizedGammaP(shape, y / scale) - f0) / mass).coerceIn(0.0, 1.0)
    }

    /** quantile(p) = lo + F⁻¹(F(0) + p·(F(W) − F(0))), clamped to [lo, hi]. */
    fun quantile(p: Double): Double {
        require(p in 0.0..1.0) { "p must be in [0,1]: $p" }
        if (isPointMass) return lo
        if (p == 0.0) return lo
        if (p == 1.0) return hi
        val target = f0 + p * mass
        var y = runCatching { gamma!!.inverseCumulativeProbability(target) }.getOrDefault(Double.NaN)
        if (y.isFinite()) {
            // Polish with Newton steps on the regularized gamma.
            repeat(3) {
                val err = Gamma.regularizedGammaP(shape, y / scale) - target
                val d = gamma!!.density(y)
                if (d > 0 && d.isFinite()) y -= err / d
            }
        }
        // commons' inverse loses relative accuracy deep in the tails (target ≪ 1e-15): verify and fall back to
        // bisection on the regularized gamma, which stays accurate there.
        if (!y.isFinite() || y < 0 || y > width || !close(Gamma.regularizedGammaP(shape, y / scale), target)) {
            y = bisectQuantile(target)
        }
        return lo + y.coerceIn(0.0, width)
    }

    private fun close(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) <= 1e-9 * maxOf(kotlin.math.abs(b), 1e-300)

    private fun bisectQuantile(target: Double): Double {
        var a = 0.0
        var b = width
        repeat(200) {
            val m = 0.5 * (a + b)
            if (Gamma.regularizedGammaP(shape, m / scale) < target) a = m else b = m
            if (b - a <= 1e-13 * width) return 0.5 * (a + b)
        }
        return 0.5 * (a + b)
    }

    fun sample(random: Random): Double = quantile(random.nextDouble().coerceIn(1e-12, 1.0 - 1e-12))

    override fun toString(): String =
        if (isPointMass) "BoundedGamma(point $lo)" else "BoundedGamma([$lo,$hi], k=$shape, θ=$scale, mean=$mean)"

    companion object {
        const val MIN_SHAPE = 0.5
        const val MAX_SHAPE = 30.0

        fun pointMass(x: Double) = BoundedGamma(x, x, 1.0, Double.NaN)

        /** Untruncated method of moments for Y: k = m²/s², θ = s²/m. */
        fun shapeScaleFromMoments(mean: Double, variance: Double): Pair<Double, Double> {
            require(mean > 0 && variance > 0) { "mean and variance must be > 0" }
            return mean * mean / variance to variance / mean
        }

        /** Bounded gamma whose untruncated Y = X − lo has mean [meanY] and variance [varianceY]. */
        fun fromMoments(lo: Double, hi: Double, meanY: Double, varianceY: Double): BoundedGamma {
            if (hi <= lo) return pointMass(lo)
            val (k, theta) = shapeScaleFromMoments(meanY, varianceY)
            return BoundedGamma(lo, hi, k, theta)
        }

        /**
         * Largest truncated mean reachable with shape [shape] on [lo, hi]: as θ → ∞ the truncated density tends
         * to ∝ y^(k−1), whose mean is W·k/(k+1).
         */
        fun maxMean(lo: Double, hi: Double, shape: Double): Double = lo + (hi - lo) * shape / (shape + 1.0)

        /**
         * Solves θ by bisection (on log θ) so that the TRUNCATED mean equals [mean]. The mean is clamped to
         * the reachable interval (lo, [maxMean]); check [BoundedGamma.mean] for the value actually achieved.
         */
        fun withMean(lo: Double, hi: Double, shape: Double, mean: Double): BoundedGamma {
            require(shape > 0) { "shape must be > 0" }
            if (hi <= lo) return pointMass(lo)
            val w = hi - lo
            val maxY = w * shape / (shape + 1.0)
            val targetY = (mean - lo).coerceIn(w * 1e-9, maxY * (1 - 1e-9))
            // Truncated mean is increasing in θ: from 0 (θ → 0) to maxY (θ → ∞).
            var logLo = kotlin.math.ln(w * 1e-9)
            var logHi = kotlin.math.ln(w * 1e9)
            repeat(200) {
                val mid = 0.5 * (logLo + logHi)
                if (truncatedMeanY(shape, exp(mid), w) < targetY) logLo = mid else logHi = mid
                if (logHi - logLo < 1e-12) return@repeat
            }
            return BoundedGamma(lo, hi, shape, exp(0.5 * (logLo + logHi)))
        }

        /** Untruncated mode (k − 1)θ placed at [mode] (needs k > 1; for k ≤ 1 the mode is always lo). */
        fun withMode(lo: Double, hi: Double, shape: Double, mode: Double): BoundedGamma {
            require(shape > 1.0) { "a mode inside the range needs shape > 1" }
            if (hi <= lo) return pointMass(lo)
            val w = hi - lo
            val y = (mode - lo).coerceIn(w * 1e-6, w)
            return BoundedGamma(lo, hi, shape, y / (shape - 1.0))
        }

        /** E[Y | Y ≤ W] = kθ · P(k+1, W/θ) / P(k, W/θ); uses the θ → ∞ limit W·k/(k+1) when P underflows. */
        internal fun truncatedMeanY(k: Double, theta: Double, w: Double): Double {
            val x = w / theta
            val pk = Gamma.regularizedGammaP(k, x)
            if (pk < 1e-280 || !pk.isFinite()) return w * k / (k + 1.0)
            val pk1 = Gamma.regularizedGammaP(k + 1.0, x)
            return (k * theta * pk1 / pk).coerceIn(0.0, w)
        }
    }
}
