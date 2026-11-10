package com.mockroute.core.traffic

import com.mockroute.core.dist.BoundedGamma
import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import com.mockroute.core.plan.SpeedProfileGenerator
import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.route.RoutingProfile
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TrafficTest {
    private val zone: ZoneId = ZoneId.of("Europe/Madrid")
    private val start = LatLon(40.42, -3.70)
    private fun route(d: Double) = RouteGeometry.of(start, GeoMath.destination(start, 80.0, d)).densify(200.0)

    /** 2026-11-11 (Wednesday) 06:00 local, on the 15-min grid. */
    private val t0: Long = java.time.ZonedDateTime.of(2026, 11, 11, 6, 0, 0, 0, zone).toEpochSecond()

    /**
     * Synthetic city: free flow 50 km/h; a rush-hour bump centred on 08:00 local (σ 40 min) slows the middle third
     * of the route down to ~12 km/h. Speeds depend on the CLOCK time a car is at a place.
     */
    private fun speedAt(s: Double, clock: Double, d: Double): Double {
        val rush = exp(-0.5 * ((clock - (t0 + 7200)) / 2400).let { it * it })
        val middle = s > d / 3 && s < 2 * d / 3
        val free = 50 / 3.6
        return if (middle) free * (1 - 0.76 * rush) else free * (1 - 0.2 * rush)
    }

    /** A provider that integrates the synthetic speeds along the route for a given departure (like TomTom would). */
    private inner class FakeProvider(val d: Double) : TrafficProvider {
        var calls = 0
        override val id = "fake"
        override val displayName = "Fake"
        override suspend fun profile(route: RouteGeometry, departureEpochS: Long, mode: TrafficMode): SpeedProfileSample {
            calls++
            val step = 50.0
            val offs = mutableListOf(0.0)
            val times = mutableListOf(0.0)
            var s = 0.0
            var t = 0.0
            while (s < d) {
                val ds = minOf(step, d - s)
                t += ds / speedAt(s, departureEpochS + t, d)
                s += ds
                offs += s
                times += t
            }
            return SpeedProfileSample(departureEpochS, d, t, noTrafficTimeS = d / (50 / 3.6), offsetsM = offs, timesS = times, provider = "Fake")
        }
    }

    private class MemoryCache : TrafficCacheStore {
        val map = HashMap<String, SpeedProfileSample>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, sample: SpeedProfileSample) {
            map[key] = sample
        }
    }

    @Test
    fun timeBinSelection() {
        val dep = t0 + 7 * 60 // 06:07
        val first = TrafficPropagation.firstBin(dep, nowEpochS = t0 - 3600)
        assertEquals(t0, first) // floors to 06:00
        val bins = TrafficPropagation.binTimes(dep, 3600.0, nowEpochS = t0 - 3600, first = first)
        assertTrue(bins.size <= 12, "${bins.size} bins")
        assertTrue(bins.all { it % 900 == 0L }, "on the 15-min grid")
        assertTrue(bins.first() <= dep - 1800 + 900, "covers departure − 30 min")
        assertTrue(bins.last() >= dep + 1800 + 5400, "covers departure + 30 min + 1.5 × duration")
        assertTrue(first in bins)
        // Never in the past: now = 06:20 → first bin ≥ 06:30 even for a 06:07 departure.
        val now = t0 + 20 * 60
        val f2 = TrafficPropagation.firstBin(dep, now)
        assertEquals(t0 + 1800, f2)
        assertTrue(TrafficPropagation.binTimes(dep, 3600.0, now, f2).all { it > now })
        // Long trip: the step widens instead of exceeding 12 requests.
        val long = TrafficPropagation.binTimes(dep, 5 * 3600.0, t0 - 3600, first)
        assertTrue(long.size <= 12 && long.last() >= dep + 1800 + 27_000)
    }

    @Test
    fun propagationConvergesToSequentialSolution() = runBlocking {
        val d = 18_000.0
        val provider = FakeProvider(d)
        val bins = (0 until 12).map { t0 + it * 900L }
        val samples = bins.map { provider.profile(route(d), it, TrafficMode.Car) }
        val model = ChunkModel(ChunkModel.boundaries(d), samples, TrafficMode.Car)
        val dep = t0 + 6600.0 // 07:50, into the rush
        val r = TrafficPropagation.propagate(model, dep)
        assertTrue(r.converged, "converged in ${r.iterations}")
        assertTrue(r.iterations <= model.count + 1)
        // Sequential (Gauss–Seidel) reference: t_{k+1} = t_k + tt_k(t_k).
        var t = dep
        for (k in 0 until model.count) {
            t += model.durationAt(k, t)
            assertEquals(t, r.arrivalsEpochS[k + 1], TrafficPropagation.TOLERANCE_S)
        }
        // Rush hour is slower than early morning.
        val early = TrafficPropagation.propagate(model, t0.toDouble())
        assertTrue(r.totalS > early.totalS * 1.2, "rush ${r.totalS} vs early ${early.totalS}")
    }

    @Test
    fun fittedGammaReproducesMeanAndVariance() {
        val rnd = kotlin.random.Random(3)
        repeat(20) {
            val lo = rnd.nextDouble(0.0, 5.0)
            val hi = lo + rnd.nextDouble(10.0, 25.0)
            val mean = lo + (hi - lo) * rnd.nextDouble(0.25, 0.5)
            // A reachable variance: that of some gamma in range.
            val ref = BoundedGamma.withMean(lo, hi, rnd.nextDouble(1.0, 20.0), mean)
            val g = BoundedGamma.withMeanVariance(lo, hi, mean, ref.variance)
            assertTrue(abs(g.mean - mean) <= 0.03 * mean, "mean ${g.mean} vs $mean")
            assertTrue(abs(g.variance - ref.variance) <= 0.03 * ref.variance, "var ${g.variance} vs ${ref.variance}")
        }
        // From data: time-weighted chunk speeds.
        val speeds = doubleArrayOf(13.9, 12.5, 4.0, 3.5, 5.0, 12.0, 13.0, 11.0)
        val weights = DoubleArray(speeds.size) { 1500.0 / speeds[it] }
        val st = TrafficPlanner.fitSpeed(speeds, weights)
        val w = weights.sum()
        val m = speeds.indices.sumOf { speeds[it] * weights[it] } / w
        val dist = st.distribution()
        assertTrue(abs(dist.mean - m) <= 0.03 * m, "fitted mean ${dist.mean} vs $m")
        assertTrue(st.lo >= 0 && st.hi <= 100 / 3.6 + 1e-9)
    }

    @Test
    fun timeFitUsesSpreadOrHeuristic() {
        val (g, heuristic) = TrafficPlanner.fitTime(1800.0, listOf(240.0, -180.0, null))
        assertTrue(!heuristic)
        assertEquals(1800.0, g.distribution().mean, 0.03 * 1800)
        val (h, isHeuristic) = TrafficPlanner.fitTime(1800.0, listOf(1.0, -1.0))
        assertTrue(isHeuristic)
        val sd = kotlin.math.sqrt(h.distribution().variance)
        assertEquals(0.12 * 1800, sd, 0.03 * 0.12 * 1800 + 5)
        assertTrue(h.hi <= 6 * 3600.0)
    }

    @Test
    fun worstPointsAreRankedByDelay() = runBlocking {
        val d = 18_000.0
        val p = FakeProvider(d)
        val samples = (0 until 12).map { p.profile(route(d), t0 + it * 900L, TrafficMode.Car) }
        val model = ChunkModel(ChunkModel.boundaries(d), samples, TrafficMode.Car)
        val r = TrafficPropagation.propagate(model, t0 + 6600.0)
        val free = DoubleArray(model.count) { model.freeFlowDuration(it) }
        val worst = TrafficPlanner.worstPoints(model, r, free)
        assertEquals(3, worst.size)
        assertTrue(worst[0].delayS >= worst[1].delayS && worst[1].delayS >= worst[2].delayS)
        // The congested middle third holds the worst points.
        assertTrue(worst.all { it.atM > d / 3 && it.atM < 2 * d / 3 }, "${worst.map { it.atM }}")
        assertTrue(worst.all { it.speedMps < 30 / 3.6 })
    }

    @Test
    fun analyzeRespectsRequestBudgetAndCache() = runBlocking {
        val d = 18_000.0
        val p = FakeProvider(d)
        val cache = MemoryCache()
        val dep = t0 + 6600
        val a = TrafficPlanner.analyze(route(d), dep, TrafficMode.Car, p, cache, nowEpochS = t0 - 600, zone = zone)
        assertTrue(a.requestsMade in 1..12, "requests ${a.requestsMade}")
        assertEquals(a.requestsMade, p.calls)
        // Re-open: everything from the cache.
        val b = TrafficPlanner.analyze(route(d), dep, TrafficMode.Car, p, cache, nowEpochS = t0 - 600, zone = zone)
        assertEquals(0, b.requestsMade)
        assertEquals(a.totalS, b.totalS, 1e-9)
        // Next week, same weekday and time → cache too.
        val week = 7 * 24 * 3600L
        val c = TrafficPlanner.analyze(route(d), dep + week, TrafficMode.Car, p, cache, nowEpochS = t0 - 600 + week, zone = zone)
        assertEquals(0, c.requestsMade)
        // Settings are auto, feasible, and the plan generator accepts them with the local median.
        assertTrue(a.settings.autoFromTraffic)
        assertTrue(a.settings.feasibility(d).feasible, "${a.settings.feasibility(d)}")
        val plan = SpeedProfileGenerator.generate(
            SpeedProfileGenerator.Request(route(d), a.settings, RoutingProfile.Car, 1, localMedian = a::medianSpeedAt),
        )
        assertTrue(abs(plan.totalTimeS - plan.targetTimeS) < 1.0)
        // Slow where traffic predicts it: mean plan speed in the middle third < in the first third.
        fun meanSpeed(from: Double, to: Double): Double {
            val (t1, _) = plan.timeAtDistance(from)
            val (t2, _) = plan.timeAtDistance(to)
            return (to - from) / (t2 - t1)
        }
        assertTrue(meanSpeed(d / 3, 2 * d / 3) < meanSpeed(500.0, d / 3), "plan follows predicted slow spots")
    }

    @Test
    fun busRulesCapSpeeds() = runBlocking {
        val d = 6_000.0
        val p = FakeProvider(d)
        val samples = listOf(p.profile(route(d), t0, TrafficMode.Car))
        val car = ChunkModel(ChunkModel.boundaries(d), samples, TrafficMode.Car)
        val bus = ChunkModel(ChunkModel.boundaries(d), samples, TrafficMode.Bus)
        for (k in 0 until car.count) {
            val vCar = car.lengthOf(k) / car.durationAt(k, t0.toDouble())
            val vBus = bus.lengthOf(k) / bus.durationAt(k, t0.toDouble())
            assertEquals(minOf(0.85 * vCar, 60 / 3.6), vBus, 1e-9)
        }
    }

    @Test
    fun tomTomFixtureParses() {
        val body = requireNotNull(javaClass.getResource("/fixtures/tomtom_calculate_route.json")).readText()
        val a = LatLon(52.5, 13.4)
        val ours = RouteGeometry.of(a, LatLon(52.5, 13.44431))
        val s = TomTomCodec.parse(body, ours, departureEpochS = 1_000L)
        assertEquals(420.0, s.travelTimeS)
        assertEquals(300.0, s.noTrafficTimeS)
        assertEquals(420.0, s.historicTimeS)
        assertEquals(450.0, s.liveTimeS)
        // Guidance profile, rescaled to our length.
        val k = ours.lengthM / 3000.0
        assertEquals(90.0, s.timeAt(1000.0 * k), 1e-6)
        assertEquals(290.0, s.timeAt(2000.0 * k), 1e-6)
        assertEquals(420.0, s.timeAt(ours.lengthM), 1e-6)
        assertEquals(1, s.sections.size)
        val jam = s.sections[0]
        assertEquals(18.0, jam.effectiveSpeedKmh)
        assertEquals(95.0, jam.delayS)
        assertEquals(2, jam.magnitude)
        assertEquals(ours.lengthM / 3, jam.startM, 15.0)
        assertEquals(2 * ours.lengthM / 3, jam.endM, 15.0)
        // Fallback profile from sections only: the jam stretch takes 1000 m / 5 m/s = 200 s, the rest share 220 s.
        val (offs, times) = TomTomCodec.profileFromSections(s.sections, 420.0, ours.lengthM)
        assertEquals(420.0, times.last(), 1e-9)
        assertEquals(ours.lengthM, offs.last(), 1e-9)
        // Errors.
        assertIs<TrafficException.Unauthorized>(TomTomCodec.error(401, """{"detailedError":{"code":"Unauthorized","message":"You are missing valid authentication credentials"}}"""))
        assertIs<TrafficException.QuotaExceeded>(TomTomCodec.error(429, null))
        assertIs<TrafficException.RestoreFailed>(TomTomCodec.error(400, """{"detailedError":{"code":"MAP_MATCHING_FAILURE","message":"x"}}"""))
        // The exact error seen on the phone (TomTom spells it "CANOT").
        assertIs<TrafficException.RestoreFailed>(
            TomTomCodec.error(400, """{"detailedError":{"code":"BAD_INPUT","message":"Engine error while executing route request: CANOT_RESTORE_BASEROUTE: No valid route found."}}"""),
        )
        assertIs<TrafficException.NoRoute>(TomTomCodec.error(400, """{"detailedError":{"code":"NO_ROUTE_FOUND","message":"x"}}"""))
    }

    @Test
    fun requestUrlAndBody() {
        val g = route(3000.0)
        val url = TomTomCodec.url(g, t0 + 7200, zone, TrafficMode.Car, "KEY")
        assertTrue(url.startsWith("https://api.tomtom.com/routing/1/calculateRoute/40.420000,-3.700000:"), url)
        assertTrue(url.contains("departAt=2026-11-11T08%3A00%3A00%2B01%3A00"), url)
        assertTrue(url.contains("sectionType=traffic") && url.contains("computeTravelTimeFor=all") && url.contains("travelMode=car"))
        assertTrue(url.contains("reconstructionMode=track"), url)
        val corridor = TomTomCodec.url(route(9000.0), t0 + 7200, zone, TrafficMode.Car, "KEY", PathMatch.Corridor)
        assertEquals(6, Regex("circle\\(").findAll(corridor).count(), corridor) // 9 km / 1.5 km
        assertTrue(!corridor.contains("reconstructionMode"))
        val direct = TomTomCodec.url(g, t0 + 7200, zone, TrafficMode.Car, "KEY", PathMatch.Direct)
        assertTrue(!direct.contains("circle(") && !direct.contains("reconstructionMode"))
        val body = TomTomCodec.body(g)
        assertTrue(body.startsWith("""{"supportingPoints":[{"latitude":40.42,"longitude":-3.7}"""), body.take(80))
    }
}
