package com.mockroute.core.plan

import com.mockroute.core.dist.BoundedGamma
import com.mockroute.core.route.RoutingProfile
import kotlinx.serialization.Serializable
import kotlin.math.ceil

/** Whether the user edits the distribution's mean or its mode. */
@Serializable
enum class CenterKind { Mean, Mode }

/** One bounded-gamma setting as the user edits it. SI units (m/s or s). */
@Serializable
data class GammaSetting(
    val lo: Double,
    val hi: Double,
    val center: Double,
    val shape: Double,
    val centerKind: CenterKind = CenterKind.Mean,
) {
    /** The distribution; the centre is clamped into [lo, hi] and a mode needs shape > 1 (else falls back to mean). */
    fun distribution(): BoundedGamma {
        if (hi <= lo) return BoundedGamma.pointMass(lo)
        val c = center.coerceIn(lo, hi)
        val k = shape.coerceIn(BoundedGamma.MIN_SHAPE, BoundedGamma.MAX_SHAPE)
        return if (centerKind == CenterKind.Mode && k > 1.0) BoundedGamma.withMode(lo, hi, k, c)
        else BoundedGamma.withMean(lo, hi, k, c)
    }

    /** New range; the centre is kept if still inside, else moved to the middle. */
    fun withRange(newLo: Double, newHi: Double): GammaSetting {
        val l = minOf(newLo, newHi)
        val h = maxOf(newLo, newHi)
        val c = if (center in l..h && h > l) center else (l + h) / 2
        return copy(lo = l, hi = h, center = c)
    }
}

/** Everything Stage 3 lets the user set for one ROUTE segment (persisted with kotlinx.serialization). */
@Serializable
data class SegmentPlanSettings(
    val speed: GammaSetting,
    val time: GammaSetting,
    val aMaxMps2: Double,
    val accelPreset: AccelerationPreset? = null,
    /** Stage 4: correlation length of speed variations (m); null = profile default (150 walk / 400 car, bus). */
    val correlationLengthM: Double? = null,
    /** Stage 5: true while these values come from a traffic analysis ("auto, from traffic"); edits clear it. */
    val autoFromTraffic: Boolean = false,
) {
    val vMin: Double get() = speed.lo
    val vMax: Double get() = speed.hi
    val tMin: Double get() = time.lo
    val tMax: Double get() = time.hi

    fun feasibility(distanceM: Double): Feasibility.Result =
        Feasibility.check(distanceM, vMax, tMax, vMin, tMin, aMaxMps2)

    /** Applies a coupled-slider result: new v_max/t_max, keeping v_min ≤ v_max and t_min ≤ t_max. */
    fun applyCoupled(c: CoupledSliders): SegmentPlanSettings = copy(
        speed = speed.withRange(minOf(speed.lo, c.vMax), c.vMax),
        time = time.withRange(minOf(time.lo, c.tMax), c.tMax),
    )

    companion object {
        private const val KMH = 1 / 3.6

        /** Indoor zone defaults: slow walking (1.5–5 km/h, mean 3.5), 5–20 min (mean 10), a = 1.0, L = 30 m. */
        fun zoneDefaults(): SegmentPlanSettings = SegmentPlanSettings(
            speed = GammaSetting(1.5 * KMH, 5.0 * KMH, 3.5 * KMH, shape = 5.0),
            time = GammaSetting(5 * 60.0, 20 * 60.0, 10 * 60.0, shape = 4.0),
            aMaxMps2 = AccelerationPreset.Walking.aMps2,
            accelPreset = AccelerationPreset.Walking,
            correlationLengthM = 30.0,
        )

        /** Street-zone defaults per profile (wandering a neighbourhood). */
        fun streetDefaults(profile: RoutingProfile): SegmentPlanSettings {
            val (v, t) = when (profile) {
                RoutingProfile.Walk -> Triple(3.0, 5.0, 6.5) to Triple(15.0, 25.0, 45.0)
                RoutingProfile.Car -> Triple(10.0, 25.0, 50.0) to Triple(10.0, 20.0, 35.0)
                RoutingProfile.Bus -> Triple(5.0, 18.0, 40.0) to Triple(15.0, 25.0, 40.0)
            }
            val preset = AccelerationPreset.defaultFor(profile)
            return SegmentPlanSettings(
                speed = GammaSetting(v.first * KMH, v.third * KMH, v.second * KMH, shape = 5.0),
                time = GammaSetting(t.first * 60, t.third * 60, t.second * 60, shape = 4.0),
                aMaxMps2 = preset.aMps2,
                accelPreset = preset,
            )
        }

        /** Sensible defaults for a fresh segment of [distanceM] metres. */
        fun defaults(profile: RoutingProfile, distanceM: Double): SegmentPlanSettings {
            val (vLo, vMean, vHi) = when (profile) {
                RoutingProfile.Walk -> Triple(3.0, 5.0, 6.5)
                RoutingProfile.Car -> Triple(10.0, 35.0, 60.0)
                RoutingProfile.Bus -> Triple(5.0, 20.0, 45.0)
            }.let { (a, b, c) -> Triple(a * KMH, b * KMH, c * KMH) }
            val preset = AccelerationPreset.defaultFor(profile)
            // Time range around the time at the mean speed: [0.8×, 1.5×], whole minutes, within caps.
            val tAtMean = if (distanceM > 0) distanceM / vMean else 60.0
            val tLo = (ceil(tAtMean * 0.8 / 60.0) * 60.0).coerceIn(0.0, CoupledSliders.T_CAP)
            val tHi = (ceil(tAtMean * 1.5 / 60.0) * 60.0).coerceIn(tLo, CoupledSliders.T_CAP)
            return SegmentPlanSettings(
                speed = GammaSetting(vLo, vHi, vMean, shape = 6.0),
                time = GammaSetting(tLo, tHi, (tLo + tHi) / 2, shape = 4.0),
                aMaxMps2 = preset.aMps2,
                accelPreset = preset,
            )
        }
    }
}
