package com.mockroute.core.plan

import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.OrsCodec
import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.route.SpeedSpan
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderSpeedProfileTest {
    /** Straight line due north, [km] long, 21 points. */
    private fun line(km: Double): RouteGeometry {
        val start = LatLon(19.36, -99.118)
        return RouteGeometry.of((0..20).map { GeoMath.destination(start, 0.0, km * 1000 * it / 20) })
    }

    @Test
    fun `ors steps become provider speeds`() {
        val g = line(2.0)
        val coords = g.points.joinToString(",") { "[${it.lon},${it.lat}]" }
        // Two steps: first half 1000 m in 120 s (30 km/h), second half 1000 m in 60 s (60 km/h).
        val body = """{"features":[{"geometry":{"type":"LineString","coordinates":[$coords]},"properties":{
            "summary":{"distance":2000,"duration":180},
            "segments":[{"distance":2000,"duration":180,"steps":[
              {"distance":1000,"duration":120,"way_points":[0,10]},
              {"distance":1000,"duration":60,"way_points":[10,20]},
              {"distance":0,"duration":0,"way_points":[20,20]}]}]}}]}"""
        val r = OrsCodec.parse(body, RoutingProfile.Car)
        assertEquals(2, r.providerSpeeds.size)
        assertEquals(30 / 3.6, r.providerSpeeds[0].speedMps, 1e-6)
        assertEquals(60 / 3.6, r.providerSpeeds[1].speedMps, 1e-6)
        val p = assertNotNull(ProviderSpeedProfile.of(r))
        assertEquals(30 / 3.6, p.at(500.0), 0.1)
        assertEquals(60 / 3.6, p.at(1500.0), 0.1)
    }

    @Test
    fun `profile is scaled to take exactly the provider time and fills gaps`() {
        // 0–400 m at 10 m/s, gap 400–600, 600–1000 at 20 m/s; provider says 100 s for 1000 m.
        val p = assertNotNull(ProviderSpeedProfile.of(listOf(SpeedSpan(0.0, 400.0, 10.0), SpeedSpan(600.0, 1000.0, 20.0)), 1000.0, 100.0))
        var t = 0.0
        var s = 0.0
        while (s < 1000.0) { t += 1.0 / p.at(s + 0.5); s += 1.0 }
        assertEquals(100.0, t, 0.5)
        assertTrue(p.at(700.0) > p.at(100.0)) // the fast stretch stays faster
    }

    @Test
    fun `first plan follows the provider speeds and takes the provider time`() {
        val g = line(4.0)
        // Slow 0–2 km (25 km/h), fast 2–4 km (60 km/h). Provider time = sum.
        val spans = listOf(SpeedSpan(0.0, g.lengthM / 2, 25 / 3.6), SpeedSpan(g.lengthM / 2, g.lengthM, 60 / 3.6))
        val eta = spans.sumOf { (it.endM - it.startM) / it.speedMps }
        val prof = assertNotNull(ProviderSpeedProfile.of(spans, g.lengthM, eta))
        val st = AutoSettings.forRoute(RoutingProfile.Car, g.lengthM, eta, 1_700_000_000, ZoneId.of("UTC"), null, prof).settings
        assertTrue(st.vMax >= 60 / 3.6, "range holds the fastest street")
        val plan = SpeedProfileGenerator.generate(
            SpeedProfileGenerator.Request(g, st, RoutingProfile.Car, seed = 7, lockTime = true, localMedian = prof::at, noise = false),
        )
        assertEquals(st.time.distribution().mean, plan.totalTimeS, 1.0)
        assertEquals(eta, plan.totalTimeS, 0.02 * eta) // the provider's time
        // Cruising speeds (away from the ends and the change) ≈ the provider's, within a few %.
        val slow = plan.timeAtDistance(g.lengthM * 0.25).second
        val fast = plan.timeAtDistance(g.lengthM * 0.8).second
        assertTrue(abs(slow - 25 / 3.6) < 0.1 * 25 / 3.6, "slow street ≈ 25 km/h, got ${slow * 3.6}")
        assertTrue(abs(fast - 60 / 3.6) < 0.1 * 60 / 3.6, "fast street ≈ 60 km/h, got ${fast * 3.6}")
    }
}
