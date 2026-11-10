package com.mockroute.core.noise

import com.mockroute.core.trajectory.KinematicState
import com.mockroute.core.zone.gaussianPair
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Reception conditions. Indoor zones ⇒ Indoor; routes and street zones ⇒ Urban unless overridden per step. */
@Serializable
enum class Environment(val label: String) { OpenSky("Open sky"), Urban("Urban"), Indoor("Indoor") }

@Serializable
enum class NoisePreset(val label: String) { Off("Off"), Light("Light"), Realistic("Realistic"), Heavy("Heavy") }

/** Per-environment parameters after presets/overrides. SI units. */
data class EnvParams(
    /** Per-axis standard deviation of the horizontal error (m). */
    val sigmaM: Double,
    /** Correlation time of the error (s). */
    val tauS: Double,
    /** Reported accuracy is kept inside [accMinM, accMaxM] (before multipath inflation). */
    val accMinM: Double,
    val accMaxM: Double,
    val jumpsPerHour: Double,
    /** Probability that a 1 Hz fix is missing. */
    val missProbability: Double,
) {
    fun lerp(o: EnvParams, f: Double) = EnvParams(
        sigmaM + (o.sigmaM - sigmaM) * f, tauS + (o.tauS - tauS) * f,
        accMinM + (o.accMinM - accMinM) * f, accMaxM + (o.accMaxM - accMaxM) * f,
        jumpsPerHour + (o.jumpsPerHour - jumpsPerHour) * f, missProbability + (o.missProbability - missProbability) * f,
    )
}

/** User-facing noise settings (persisted as JSON). */
@Serializable
data class NoiseSettings(
    val preset: NoisePreset = NoisePreset.Realistic,
    /** Per-environment multipliers on the error size (0.25–3). */
    val openSkyScale: Double = 1.0,
    val urbanScale: Double = 1.0,
    val indoorScale: Double = 1.0,
    /** 0 = a new random seed every run. */
    val seed: Long = 0,
    /** Draw true path vs injected points on the map while simulating. */
    val debugOverlay: Boolean = false,
) {
    val enabled: Boolean get() = preset != NoisePreset.Off

    /**
     * Realistic defaults (GPS.gov: phones are typically within 4.9 m under open sky, worse near buildings/trees;
     * Android's accuracy is the 68 % radius): OpenSky σ 2.5 m τ 30 s; Urban σ 6 m τ 20 s; Indoor σ 12 m τ 10 s.
     * Light = ×0.5 without multipath; Heavy = ×1.7 with 2.5× the multipath and twice the missed fixes.
     */
    fun params(env: Environment): EnvParams {
        val base = when (env) {
            Environment.OpenSky -> EnvParams(2.5, 30.0, 3.0, 8.0, 0.0, 0.0)
            Environment.Urban -> EnvParams(6.0, 20.0, 6.0, 20.0, 12.0, 0.005)
            Environment.Indoor -> EnvParams(12.0, 10.0, 15.0, 50.0, 20.0, 0.03)
        }
        val (k, jumps, miss) = when (preset) {
            NoisePreset.Off -> Triple(0.0, 0.0, 0.0)
            NoisePreset.Light -> Triple(0.5, 0.0, 0.5)
            NoisePreset.Realistic -> Triple(1.0, 1.0, 1.0)
            NoisePreset.Heavy -> Triple(1.7, 2.5, 2.0)
        }
        val s = k * when (env) {
            Environment.OpenSky -> openSkyScale
            Environment.Urban -> urbanScale
            Environment.Indoor -> indoorScale
        }
        return EnvParams(base.sigmaM * s, base.tauS, base.accMinM * s, base.accMaxM * s, base.jumpsPerHour * jumps, base.missProbability * miss)
    }
}

/** One injected fix. [state] is what the phone reports; [truth] is the simulated true state. */
data class NoisyFix(
    val state: KinematicState,
    val verticalAccuracyM: Double,
    val speedAccuracyMps: Double,
    val bearingAccuracyDeg: Double,
    /** Tick jitter: offset of this fix from the nominal whole second, −20…+20 ms. */
    val timeOffsetMs: Int,
    val truth: KinematicState,
    val multipath: Boolean,
)

/**
 * Makes simulated positions look like fused/GNSS output. Seeded and stateful; call [next] once per tick (1 Hz).
 *
 * - **Horizontal error**: first-order Gauss–Markov per axis, g_t = φ·g_{t−1} + √(1−φ²)·w_t with φ = exp(−Δt/τ)
 *   (unit variance), scaled by σ·q_t. It evolves every tick whether or not the truth moves (natural wander when
 *   standing still).
 * - **Quality q_t**: slow log-normal walk (sd 0.2, τ 120 s, median 1) shared by the error and the reported
 *   accuracy, so they stay consistent: accuracy = 1.515·σ·q (the 68 % radius of a 2-D Gaussian), kept within the
 *   environment's range.
 * - **Environment changes** ramp all parameters over 5–15 s.
 * - **Multipath** (Poisson): a 15–60 m offset in a random direction that decays linearly over 3–10 s; accuracy is
 *   inflated by 0.7× the current offset.
 * - **Altitude**: truth + Gauss–Markov with σ_v = 2.5·σ·q; vertical accuracy = σ_v.
 * - **Speed** = truth + N(0, 0.2 m/s), ≥ 0; **bearing** = direction of motion + noise ∝ 1/speed; under 0.5 m/s the
 *   last bearing is kept with a large bearing accuracy.
 * - **Tick jitter** ±20 ms and **missed fixes** ([next] returns null; the state still advances).
 */
class GpsNoiseModel(private val settings: NoiseSettings, seed: Long) {
    private val rng = Random(seed)
    private var gx: Double
    private var gy: Double
    private var gz: Double
    private var logQ: Double

    private var env: Environment? = null
    private var from: EnvParams? = null
    private var to: EnvParams? = null
    private var rampTotal = 0.0
    private var rampDone = 0.0

    private var jumpE = 0.0
    private var jumpN = 0.0
    private var jumpAge = 0.0
    private var jumpDur = 0.0

    private var lastBearing: Double? = null

    init {
        val (a, b) = gaussianPair(rng)
        val (c, d) = gaussianPair(rng)
        gx = a
        gy = b
        gz = c
        logQ = d * Q_SD
    }

    private fun gauss() = gaussianPair(rng).first

    private fun current(): EnvParams {
        val t = to!!
        val f = from ?: return t
        if (rampTotal <= 0 || rampDone >= rampTotal) return t
        return f.lerp(t, rampDone / rampTotal)
    }

    fun next(truth: KinematicState, environment: Environment, dtS: Double = 1.0): NoisyFix? {
        // Environment ramp.
        if (env == null) {
            to = settings.params(environment)
        } else if (environment != env) {
            from = current()
            to = settings.params(environment)
            rampTotal = rng.nextDouble(5.0, 15.0)
            rampDone = 0.0
        } else {
            rampDone += dtS
        }
        env = environment
        val p = current()

        // Gauss–Markov states.
        val phi = exp(-dtS / p.tauS)
        val innov = sqrt(1 - phi * phi)
        gx = phi * gx + innov * gauss()
        gy = phi * gy + innov * gauss()
        val phiZ = exp(-dtS / (p.tauS * 1.5))
        gz = phiZ * gz + sqrt(1 - phiZ * phiZ) * gauss()
        val phiQ = exp(-dtS / Q_TAU_S)
        logQ = phiQ * logQ + sqrt(1 - phiQ * phiQ) * Q_SD * gauss()
        val q = exp(logQ)
        val sigma = p.sigmaM * q

        // Multipath.
        var jumpFactor = 0.0
        if (jumpDur > 0) {
            jumpAge += dtS
            if (jumpAge >= jumpDur) jumpDur = 0.0 else jumpFactor = 1 - jumpAge / jumpDur
        }
        if (jumpDur <= 0 && p.jumpsPerHour > 0 && rng.nextDouble() < p.jumpsPerHour / 3600.0 * dtS) {
            val mag = rng.nextDouble(15.0, 60.0)
            val dir = rng.nextDouble(0.0, 2 * PI)
            jumpE = mag * cos(dir)
            jumpN = mag * sin(dir)
            jumpDur = rng.nextDouble(3.0, 10.0)
            jumpAge = 0.0
            jumpFactor = 1.0
        }
        val east = sigma * gx + jumpE * jumpFactor
        val north = sigma * gy + jumpN * jumpFactor
        val jumpNow = hypot(jumpE, jumpN) * jumpFactor

        val lat = truth.lat + north / M_PER_DEG
        val lon = truth.lon + east / (M_PER_DEG * cos(Math.toRadians(truth.lat)).coerceAtLeast(1e-6))
        val accuracy = ((RADIUS_68 * sigma).coerceIn(p.accMinM, p.accMaxM) + 0.7 * jumpNow).coerceAtLeast(MIN_ACCURACY_M)

        val sigmaV = (2.5 * sigma).coerceAtLeast(1.0)
        val altitude = truth.altitudeM + sigmaV * gz

        // Speed and bearing.
        val speed = if (truth.speedMps < 0.05) abs(gauss()) * 0.03 else (truth.speedMps + gauss() * SPEED_SD).coerceAtLeast(0.0)
        val speedAcc = (0.3 + 0.05 * sigma).coerceIn(0.3, 1.0)
        val bearing: Double
        val bearingAcc: Double
        if (truth.speedMps >= 0.5) {
            val sd = (6.0 / truth.speedMps).coerceIn(1.5, 45.0)
            bearing = norm360(truth.bearingDeg + gauss() * sd)
            bearingAcc = (sd * 1.5).coerceAtMost(90.0)
            lastBearing = bearing
        } else {
            bearing = lastBearing ?: norm360(truth.bearingDeg)
            bearingAcc = 150.0
        }

        val jitter = rng.nextInt(-20, 21)
        val missed = p.missProbability > 0 && rng.nextDouble() < p.missProbability
        if (missed) return null
        return NoisyFix(
            state = KinematicState(lat.coerceIn(-90.0, 90.0), lon, altitude, speed, bearing, accuracy),
            verticalAccuracyM = sigmaV,
            speedAccuracyMps = speedAcc,
            bearingAccuracyDeg = bearingAcc,
            timeOffsetMs = jitter,
            truth = truth,
            multipath = jumpFactor > 0,
        )
    }

    companion object {
        /** 68th-percentile radius of a 2-D Gaussian with unit per-axis σ: √(−2·ln 0.32). */
        const val RADIUS_68 = 1.5096
        const val Q_SD = 0.2
        const val Q_TAU_S = 120.0
        const val SPEED_SD = 0.2
        const val MIN_ACCURACY_M = 1.0
        private const val M_PER_DEG = 111_194.93

        fun norm360(d: Double): Double {
            val r = d % 360.0
            return if (r < 0) r + 360.0 else if (r >= 360.0) 0.0 else r
        }
    }
}
