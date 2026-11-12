package com.mockroute.core.plan

import com.mockroute.core.dist.BoundedGamma
import com.mockroute.core.plan.SpeedProfileGenerator.Stop
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.traffic.TrafficAnalysis
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Data-driven default speed & time for a ROUTE step, used whenever the user hasn't set them by hand.
 *
 * Priority:
 * 1. **Car/Bus with a TomTom analysis** for the step's departure (historic speeds, time-dependent ETA, speed limits
 *    inside TomTom's model) → its fitted settings (Stage 5).
 * 2. **The router's ETA** as given (OpenRouteService/GraphHopper durations already use road classes and speed
 *    limits): time mean = ETA, speed mean = distance / ETA.
 * 3. **Standard urban speeds for the hour** (walk 4.8 km/h; car night 40 / day 30 / peak 20; bus 0.8 × car).
 *
 * Walking adds **street-crossing waits**: a crossing about every 150 m; at each you stop with a probability and for a
 * gamma-distributed wait that depend on the hour (busier traffic → more red lights and longer waits). The waits are
 * real v = 0 holds in the plan ([crossingStops]); their expected total is added to the time mean.
 *
 * Time: CV 12 % around the mean (±3 sd, ≥ physical minimum, ≤ 6 h). Speed: CV 20 % (walk 12 %) around the moving
 * average d / moving time, bounds ±60 % (walk ±30 %), ≤ 100 km/h.
 */
object AutoSettings {
    enum class Period(val label: String) { Night("night"), MorningPeak("morning rush"), Day("daytime"), EveningPeak("evening rush") }

    data class Result(val settings: SegmentPlanSettings, val source: String, val expectedWaitS: Double)

    const val CROSSING_SPACING_M = 150.0

    fun period(epochS: Long, zone: ZoneId): Period {
        val z = Instant.ofEpochSecond(epochS).atZone(zone)
        val weekend = z.dayOfWeek == DayOfWeek.SATURDAY || z.dayOfWeek == DayOfWeek.SUNDAY
        val h = z.hour
        return when {
            h < 6 || h >= 22 -> Period.Night
            !weekend && h in 7..9 -> Period.MorningPeak
            !weekend && h in 17..19 -> Period.EveningPeak
            else -> Period.Day
        }
    }

    /** Standard urban average speed (km/h) for [profile] in [p]. */
    fun standardKmh(profile: RoutingProfile, p: Period): Double {
        val car = when (p) {
            Period.Night -> 40.0
            Period.Day -> 30.0
            Period.MorningPeak, Period.EveningPeak -> 20.0
        }
        return when (profile) {
            RoutingProfile.Walk -> if (p == Period.Night) 5.0 else 4.8
            RoutingProfile.Car -> car
            RoutingProfile.Bus -> max(15.0, 0.8 * car)
        }
    }

    fun congestionFactor(profile: RoutingProfile, p: Period): Double = when (profile) {
        RoutingProfile.Walk -> 1.0
        RoutingProfile.Car, RoutingProfile.Bus -> (when (p) {
            Period.Night -> 1.0
            Period.Day -> 1.15
            Period.MorningPeak, Period.EveningPeak -> 1.35
        }) * if (profile == RoutingProfile.Bus) 1.15 else 1.0
    }

    /** Crossing behaviour by period: (probability of having to stop, mean wait s). */
    fun crossing(p: Period): Pair<Double, Double> = when (p) {
        Period.Night -> 0.3 to 12.0
        Period.Day -> 0.5 to 22.0
        Period.MorningPeak, Period.EveningPeak -> 0.6 to 30.0
    }

    fun crossingCount(distanceM: Double): Int = max(0, ((distanceM - 80.0) / CROSSING_SPACING_M).toInt())

    fun expectedCrossingWaitS(distanceM: Double, p: Period): Double {
        val (prob, mean) = crossing(p)
        return crossingCount(distanceM) * prob * mean
    }

    /** Sampled crossing stops along a walking route (positions every ~150 m, not within 40 m of the ends). */
    fun crossingStops(distanceM: Double, departEpochS: Long, zone: ZoneId, rng: Random): List<Stop> {
        val p = period(departEpochS, zone)
        val (prob, mean) = crossing(p)
        val wait = BoundedGamma.withMean(0.0, 120.0, 2.0, mean)
        val n = crossingCount(distanceM)
        return (1..n).mapNotNull { k ->
            val at = (k * CROSSING_SPACING_M + rng.nextDouble(-30.0, 30.0)).coerceIn(40.0, distanceM - 40.0)
            if (rng.nextDouble() < prob) Stop(at, wait.sample(rng).coerceAtLeast(3.0)) else null
        }
    }

    fun forRoute(
        profile: RoutingProfile,
        distanceM: Double,
        providerEtaS: Double?,
        departEpochS: Long,
        zone: ZoneId,
        traffic: TrafficAnalysis? = null,
        /** 1.2.0: the provider's per-street speeds; the default speed range is widened to contain them. */
        providerSpeeds: ProviderSpeedProfile? = null,
    ): Result {
        val p = period(departEpochS, zone)
        val clock = "%02d:%02d".format(Instant.ofEpochSecond(departEpochS).atZone(zone).hour, Instant.ofEpochSecond(departEpochS).atZone(zone).minute)
        if (traffic != null && profile != RoutingProfile.Walk) {
            return Result(traffic.settings.copy(autoFromTraffic = true), "TomTom traffic at $clock", 0.0)
        }
        val wait = if (profile == RoutingProfile.Walk) expectedCrossingWaitS(distanceM, p) else 0.0
        // The provider's own time is the default (user rule 2026-11-10): time mean = the router's ETA, speed mean =
        // distance / ETA, with no extra factors. Only without an ETA do we fall back to a standard speed for the hour
        // (plus the expected crossing waits when walking).
        val fromProvider = providerEtaS != null && providerEtaS > 0
        val (moving, source) = when {
            fromProvider -> providerEtaS!! to "route provider's time"
            else -> distanceM / (standardKmh(profile, p) / 3.6) to "standard ${p.label} speed at $clock"
        }
        val sourceLabel = if (wait > 0 && !fromProvider) "$source + crossings" else source
        val vMean = (distanceM / moving.coerceAtLeast(1.0)).coerceIn(0.5, 100 / 3.6)
        val walk = profile == RoutingProfile.Walk
        val spread = if (walk) 0.30 else 0.60
        var vLo = vMean * (1 - spread)
        var vHi = min(100 / 3.6, vMean * (1 + spread))
        var sdV = (if (walk) 0.12 else 0.20) * vMean
        if (fromProvider && providerSpeeds != null) {
            // Room for the provider's slowest and fastest streets (the first plan follows them exactly).
            vLo = min(vLo, providerSpeeds.minMps * 0.9).coerceAtLeast(0.3)
            vHi = min(100 / 3.6, max(vHi, providerSpeeds.maxMps * 1.1))
            sdV = max(sdV, (providerSpeeds.maxMps - providerSpeeds.minMps) / 4)
        }
        val speedG = BoundedGamma.withMeanVariance(vLo, vHi, vMean, sdV * sdV)
        val preset = AccelerationPreset.defaultFor(profile)
        val tMean = if (fromProvider) moving else moving + wait
        val sd = 0.12 * tMean
        val tPhys = Feasibility.tMinPhys(distanceM, vHi, preset.aMps2) * 1.02
        val tLo = max(tPhys, tMean - 3 * sd)
        val tHi = min(CoupledSliders.T_CAP, max(tLo + 60, tMean + 3 * sd))
        val tM = tMean.coerceIn(tLo + 1e-3 * (tHi - tLo), tHi - 1e-3 * (tHi - tLo))
        val timeG = BoundedGamma.withMeanVariance(tLo, tHi, tM, sd * sd)
        val settings = SegmentPlanSettings(
            speed = GammaSetting(vLo, vHi, vMean, speedG.shape),
            time = GammaSetting(tLo, tHi, tM, timeG.shape),
            aMaxMps2 = preset.aMps2,
            accelPreset = preset,
        )
        return Result(settings, sourceLabel, wait)
    }

    @Suppress("unused")
    private fun rms(xs: List<Double>) = sqrt(xs.sumOf { it * it } / max(1, xs.size))
}
