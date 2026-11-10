package com.mockroute.core.plan

import com.mockroute.core.route.RoutingProfile

/**
 * a_max presets (m/s²). a_max itself is free and uncapped (> 0); these are starting points.
 * Sources/notes:
 * - Walking: gait-initiation studies put typical accelerations ≈ 1 m/s², with brief peaks of 2–3 m/s².
 * - Car: everyday driving ≈ 1–1.5 m/s² (1.3 used); "brisk" ≈ 2.2; full throttle on ordinary cars ≈ 3–4 (3.5 used).
 * - City bus: passenger-comfort guidelines ≈ 1.0 m/s²; observed service maxima ≈ 1.5–2.5 (2.0 used).
 */
enum class AccelerationPreset(val label: String, val aMps2: Double, val note: String) {
    Walking("Walking", 1.0, "typical; peaks 2–3 m/s²"),
    CarNormal("Car normal", 1.3, "everyday driving"),
    CarBrisk("Car brisk", 2.2, "brisk acceleration"),
    CarFull("Car full throttle", 3.5, "ordinary car, 3–4 m/s²"),
    BusComfort("Bus comfort", 1.0, "passenger comfort"),
    BusMax("Bus typical max", 2.0, "observed 1.5–2.5 m/s²"),
    ;

    companion object {
        fun defaultFor(profile: RoutingProfile): AccelerationPreset = when (profile) {
            RoutingProfile.Walk -> Walking
            RoutingProfile.Car -> CarNormal
            RoutingProfile.Bus -> BusComfort
        }

        fun forProfile(profile: RoutingProfile): List<AccelerationPreset> = when (profile) {
            RoutingProfile.Walk -> listOf(Walking)
            RoutingProfile.Car -> listOf(CarNormal, CarBrisk, CarFull)
            RoutingProfile.Bus -> listOf(BusComfort, BusMax)
        }
    }
}
