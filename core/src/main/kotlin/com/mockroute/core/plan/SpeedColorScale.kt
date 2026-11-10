package com.mockroute.core.plan

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * speed → colour: green at [low], yellow at [mid], red at [high], interpolated in OKLab (perceptually even,
 * without the muddy brown you get mixing in sRGB). Colours are 0xAARRGGBB ints.
 */
class SpeedColorScale(val low: Double, val mid: Double, val high: Double) {
    init {
        require(high >= low) { "high < low" }
    }

    private val midC = mid.coerceIn(low, high)

    fun colorFor(speed: Double): Int {
        if (high - low < 1e-9) return YELLOW
        return if (speed <= midC) {
            val f = if (midC - low > 1e-9) ((speed - low) / (midC - low)).coerceIn(0.0, 1.0) else 1.0
            lerpOklab(GREEN, YELLOW, f)
        } else {
            val f = if (high - midC > 1e-9) ((speed - midC) / (high - midC)).coerceIn(0.0, 1.0) else 1.0
            lerpOklab(YELLOW, RED, f)
        }
    }

    companion object {
        const val GREEN: Int = 0xFF1E9E48.toInt()
        const val YELLOW: Int = 0xFFF9C21B.toInt()
        const val RED: Int = 0xFFE5372E.toInt()

        /**
         * Scale for a plan: green at the plan's slowest cruising speed, red at its fastest. Speeds below the
         * floor (the accel ramps at the ends) are ignored for the range, and the range is widened to at least
         * the settings' [vMin, vMax] if the plan barely varies. Yellow sits at the speed distribution's mean.
         */
        fun forPlan(plan: SpeedPlan, settings: SegmentPlanSettings): SpeedColorScale {
            val floor = maxOf(settings.vMin, SpeedProfileGenerator.MIN_SPEED_MPS)
            var lo = Double.POSITIVE_INFINITY
            var hi = 0.0
            for (x in plan.v) {
                if (x >= floor - 1e-9) {
                    if (x < lo) lo = x
                    if (x > hi) hi = x
                }
            }
            if (!lo.isFinite()) {
                lo = plan.minSpeedMps
                hi = plan.maxSpeedMps
            }
            val mean = settings.speed.distribution().mean
            return SpeedColorScale(minOf(lo, mean), mean, maxOf(hi, mean))
        }

        fun toHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

        fun lerpOklab(c0: Int, c1: Int, f: Double): Int {
            val a = toOklab(c0)
            val b = toOklab(c1)
            return fromOklab(
                doubleArrayOf(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f),
            )
        }

        private fun srgbToLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        private fun linearToSrgb(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055

        internal fun toOklab(argb: Int): DoubleArray {
            val r = srgbToLinear(((argb shr 16) and 0xFF) / 255.0)
            val g = srgbToLinear(((argb shr 8) and 0xFF) / 255.0)
            val b = srgbToLinear((argb and 0xFF) / 255.0)
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            return doubleArrayOf(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
            )
        }

        internal fun fromOklab(lab: DoubleArray): Int {
            val l = (lab[0] + 0.3963377774 * lab[1] + 0.2158037573 * lab[2]).pow(3)
            val m = (lab[0] - 0.1055613458 * lab[1] - 0.0638541728 * lab[2]).pow(3)
            val s = (lab[0] - 0.0894841775 * lab[1] - 1.2914855480 * lab[2]).pow(3)
            val r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
            val g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
            val b = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
            fun ch(x: Double) = (linearToSrgb(x.coerceIn(0.0, 1.0)) * 255).roundToInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
    }
}
