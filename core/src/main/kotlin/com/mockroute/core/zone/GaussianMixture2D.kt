package com.mockroute.core.zone

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** One 2D Gaussian: weight, mean (local m), σx, σy (m) along its own axes, rotation (rad, CCW from east). */
@Serializable
data class GaussianComponent(
    val weight: Double,
    val meanX: Double,
    val meanY: Double,
    val sigmaX: Double,
    val sigmaY: Double,
    val rotationRad: Double = 0.0,
) {
    init {
        require(weight >= 0 && sigmaX > 0 && sigmaY > 0) { "invalid component" }
    }

    /** Squared Mahalanobis distance of (x, y) from the mean. */
    fun mahalanobis2(x: Double, y: Double): Double {
        val dx = x - meanX
        val dy = y - meanY
        val c = cos(rotationRad)
        val s = sin(rotationRad)
        val u = (c * dx + s * dy) / sigmaX
        val v = (-s * dx + c * dy) / sigmaY
        return u * u + v * v
    }

    fun density(x: Double, y: Double): Double = exp(-0.5 * mahalanobis2(x, y)) / (2 * PI * sigmaX * sigmaY)

    fun sample(rng: Random): Vec2 {
        val (g1, g2) = gaussianPair(rng)
        val u = g1 * sigmaX
        val v = g2 * sigmaY
        val c = cos(rotationRad)
        val s = sin(rotationRad)
        return Vec2(meanX + c * u - s * v, meanY + s * u + c * v)
    }
}

/**
 * Mixture of 2D Gaussians describing where a person tends to wander, in the zone's local frame (m).
 * Weights need not sum to 1 (they are normalised).
 */
class GaussianMixture2D(val components: List<GaussianComponent>) {
    init {
        require(components.isNotEmpty() && components.sumOf { it.weight } > 0) { "mixture needs a positive weight" }
    }

    private val totalWeight = components.sumOf { it.weight }

    fun density(x: Double, y: Double): Double = components.sumOf { it.weight * it.density(x, y) } / totalWeight

    fun sample(rng: Random): Vec2 {
        var r = rng.nextDouble() * totalWeight
        for (c in components) {
            r -= c.weight
            if (r <= 0) return c.sample(rng)
        }
        return components.last().sample(rng)
    }

    /**
     * Sample conditioned on lying inside [polygon] (≥ [margin] m from walls), by rejection. Falls back to a
     * uniform interior point if the mixture mostly lies outside (e.g. a mean dragged outside the zone).
     */
    fun sampleInside(polygon: Polygon2, rng: Random, margin: Double = 0.3, maxTries: Int = 500): Vec2 {
        repeat(maxTries) {
            val p = sample(rng)
            if (polygon.containsWithMargin(p, margin)) return p
        }
        return uniformInside(polygon, rng, margin)
    }

    /** Density on an nx × ny grid over [minX, maxX] × [minY, maxY] (row-major, y outer); outside cells = 0. */
    fun grid(nx: Int, ny: Int, minX: Double, maxX: Double, minY: Double, maxY: Double, polygon: Polygon2? = null): FloatArray {
        val out = FloatArray(nx * ny)
        for (j in 0 until ny) {
            val y = minY + (maxY - minY) * (j + 0.5) / ny
            for (i in 0 until nx) {
                val x = minX + (maxX - minX) * (i + 0.5) / nx
                out[j * nx + i] = if (polygon == null || polygon.contains(Vec2(x, y), 0.0)) density(x, y).toFloat() else 0f
            }
        }
        return out
    }

    companion object {
        /** Default: one broad Gaussian at the centroid, σ = √area / 3 on both axes. */
        fun default(polygon: Polygon2): GaussianMixture2D {
            val c = if (polygon.containsWithMargin(polygon.centroid, 0.3)) polygon.centroid else uniformInside(polygon, Random(0), 0.3)
            val s = (sqrt(polygon.area) / 3).coerceAtLeast(0.5)
            return GaussianMixture2D(listOf(GaussianComponent(1.0, c.x, c.y, s, s)))
        }

        fun uniformInside(polygon: Polygon2, rng: Random, margin: Double): Vec2 {
            repeat(20_000) {
                val p = Vec2(rng.nextDouble(polygon.minX, polygon.maxX), rng.nextDouble(polygon.minY, polygon.maxY))
                if (polygon.containsWithMargin(p, margin)) return p
            }
            repeat(20_000) {
                val p = Vec2(rng.nextDouble(polygon.minX, polygon.maxX), rng.nextDouble(polygon.minY, polygon.maxY))
                if (polygon.contains(p, 0.0)) return p
            }
            return polygon.centroid
        }
    }
}

/** Two independent standard normals (Box–Muller). */
internal fun gaussianPair(rng: Random): Pair<Double, Double> {
    val u1 = rng.nextDouble().coerceAtLeast(1e-300)
    val u2 = rng.nextDouble()
    val r = sqrt(-2 * ln(u1))
    return r * cos(2 * PI * u2) to r * sin(2 * PI * u2)
}
