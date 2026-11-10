package com.mockroute.core.noise

import com.mockroute.core.geo.GeoMath
import com.mockroute.core.geo.LatLon
import com.mockroute.core.trajectory.KinematicState
import com.mockroute.core.trajectory.StraightLineTrajectory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpsNoiseTest {
    private val still = KinematicState(40.4168, -3.7038, 650.0, 0.0, 0.0, 5.0)
    private val realistic = NoiseSettings()

    private fun percentile(xs: List<Double>, p: Double) = xs.sorted()[((xs.size - 1) * p).toInt()]

    /** 1 hour stationary under open sky: error size matches the reported accuracy; autocorrelation ≈ φ. */
    @Test
    fun stationaryOpenSkyIsConsistent() {
        val phi = exp(-1.0 / 30.0)
        for (seed in listOf(1L, 2L, 3L, 4L, 5L)) {
            val m = GpsNoiseModel(realistic, seed)
            val err = ArrayList<Double>()
            val east = ArrayList<Double>()
            val acc = ArrayList<Double>()
            repeat(3600) {
                val f = m.next(still, Environment.OpenSky)!! // open sky never misses
                err += GeoMath.haversineMeters(LatLon(still.lat, still.lon), f.state.position)
                east += (f.state.lon - still.lon) * 111_194.93 * cos(Math.toRadians(still.lat))
                acc += f.state.accuracyM
            }
            val p68 = percentile(err, 0.68)
            val medAcc = percentile(acc, 0.5)
            assertTrue(abs(p68 - medAcc) <= 0.25 * medAcc, "seed $seed: 68th pct error $p68 vs median accuracy $medAcc")
            val mean = east.average()
            var num = 0.0
            var den = 0.0
            for (i in east.indices) {
                den += (east[i] - mean) * (east[i] - mean)
                if (i > 0) num += (east[i] - mean) * (east[i - 1] - mean)
            }
            val rho = num / den
            assertEquals(phi, rho, 0.05, "seed $seed lag-1 autocorrelation")
            // It wanders (not frozen) but stays plausible for open sky.
            assertTrue(err.max() < 25.0 && err.toSet().size > 3000)
            assertTrue(acc.all { it in 3.0..8.0 }, "accuracy range ${acc.min()}..${acc.max()}")
            println("seed $seed: p68 error %.2f m, median accuracy %.2f m, lag-1 %.3f (φ %.3f)".format(p68, medAcc, rho, phi))
        }
    }

    @Test
    fun outputsAreAlwaysValid() {
        val a = LatLon(40.4168, -3.7038)
        val traj = StraightLineTrajectory(a, GeoMath.destination(a, 70.0, 2000.0))
        for (preset in listOf(NoisePreset.Light, NoisePreset.Realistic, NoisePreset.Heavy)) {
            val m = GpsNoiseModel(NoiseSettings(preset = preset), 99)
            var lastNanos = Long.MIN_VALUE
            var missed = 0
            var jumps = 0
            for (t in 0 until 7200) {
                val env = Environment.entries[(t / 600) % 3] // switch environment every 10 min
                val truth = if (t % 1200 < 900) traj.stateAt(t.toDouble()) else traj.stateAt(900.0).copy(speedMps = 0.0)
                val f = m.next(truth, env)
                if (f == null) {
                    missed++
                    continue
                }
                val s = f.state
                for (x in listOf(s.lat, s.lon, s.altitudeM, s.speedMps, s.bearingDeg, s.accuracyM, f.verticalAccuracyM, f.speedAccuracyMps, f.bearingAccuracyDeg)) {
                    assertTrue(x.isFinite(), "non-finite value at t=$t")
                }
                assertTrue(s.accuracyM > 0 && f.verticalAccuracyM > 0)
                assertTrue(s.speedMps >= 0.0)
                assertTrue(s.bearingDeg >= 0.0 && s.bearingDeg < 360.0, "bearing ${s.bearingDeg}")
                assertTrue(f.speedAccuracyMps in 0.3..1.0)
                assertTrue(f.timeOffsetMs in -20..20)
                // Timestamps: nominal second + jitter stays strictly increasing.
                val nanos = t * 1_000_000_000L + f.timeOffsetMs * 1_000_000L
                assertTrue(nanos > lastNanos)
                lastNanos = nanos
                if (f.multipath) jumps++
                // No teleports except configured jumps: without multipath the error stays within ~6σ (+ ramp).
                if (!f.multipath) {
                    val err = GeoMath.haversineMeters(truth.position, s.position)
                    assertTrue(err < 6.5 * 12.0 * 1.7 * 2.5, "error $err m at t=$t ($preset)")
                }
                if (truth.speedMps < 0.5) assertEquals(150.0, f.bearingAccuracyDeg)
            }
            if (preset == NoisePreset.Light) assertEquals(0, jumps) else assertTrue(jumps > 0, "$preset should have multipath")
            assertTrue(missed in 1..400, "$preset missed $missed")
        }
    }

    @Test
    fun indoorIsWorseThanUrbanIsWorseThanOpenSky() {
        fun meanError(env: Environment): Pair<Double, Double> {
            val m = GpsNoiseModel(NoiseSettings(preset = NoisePreset.Light), 7) // Light: no multipath
            var e = 0.0
            var a = 0.0
            var n = 0
            repeat(4000) {
                val f = m.next(still, env) ?: return@repeat
                e += GeoMath.haversineMeters(still.position, f.state.position)
                a += f.state.accuracyM
                n++
            }
            return e / n to a / n
        }
        val (eo, ao) = meanError(Environment.OpenSky)
        val (eu, au) = meanError(Environment.Urban)
        val (ei, ai) = meanError(Environment.Indoor)
        assertTrue(eo < eu && eu < ei && ao < au && au < ai, "errors $eo $eu $ei, accuracies $ao $au $ai")
    }

    @Test
    fun environmentChangeRampsSmoothly() {
        val m = GpsNoiseModel(NoiseSettings(preset = NoisePreset.Light), 3)
        repeat(300) { m.next(still, Environment.OpenSky) }
        val before = m.next(still, Environment.OpenSky)!!.state.accuracyM
        val accs = (0 until 30).mapNotNull { m.next(still, Environment.Indoor)?.state?.accuracyM }
        // Not an instant jump to indoor accuracy: the first second stays near the old value, the end is much larger.
        assertTrue(accs.first() < before * 2.2, "first ${accs.first()} vs before $before")
        assertTrue(accs.last() > accs.first() * 1.5)
    }

    @Test
    fun sameSeedSameOutputAndMultipathInflatesAccuracy() {
        val a = GpsNoiseModel(NoiseSettings(preset = NoisePreset.Heavy), 11)
        val b = GpsNoiseModel(NoiseSettings(preset = NoisePreset.Heavy), 11)
        var seenJump = false
        repeat(5000) {
            val fa = a.next(still, Environment.Urban)
            val fb = b.next(still, Environment.Urban)
            assertEquals(fa, fb)
            if (fa != null && fa.multipath && !seenJump) {
                seenJump = true
                val err = GeoMath.haversineMeters(still.position, fa.state.position)
                assertTrue(fa.state.accuracyM > 6.0 * 1.7 + 5, "accuracy ${fa.state.accuracyM} during a $err m jump")
            }
        }
        assertTrue(seenJump)
        // Settings JSON round trip.
        val s = NoiseSettings(NoisePreset.Heavy, 0.5, 2.0, 1.5, 42, true)
        assertEquals(s, kotlinx.serialization.json.Json.decodeFromString(NoiseSettings.serializer(), kotlinx.serialization.json.Json.encodeToString(NoiseSettings.serializer(), s)))
    }
}
