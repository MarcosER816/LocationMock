package com.mockroute.app.mock

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.mockroute.core.trajectory.KinematicState
import kotlinx.coroutines.tasks.await

/**
 * Pushes simulated fixes into Android's test providers ("gps", "network", and "fused" on API 31+) and,
 * optionally, into Google Play services' fused provider via setMockMode/setMockLocation.
 *
 * [start] throws [SecurityException] when MockRoute is not the selected mock location app.
 */
class MockInjector(context: Context, private val useGms: Boolean) {
    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(LocationManager::class.java)
    private var gms: FusedLocationProviderClient? = null
    private val active = mutableListOf<String>()

    @Throws(SecurityException::class)
    suspend fun start() {
        for (provider in providerNames()) {
            try {
                lm.removeTestProvider(provider)
            } catch (_: IllegalArgumentException) {
                // not registered — fine
            }
            try {
                addTestProvider(provider)
                lm.setTestProviderEnabled(provider, true)
                active += provider
            } catch (e: SecurityException) {
                stop()
                throw e
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Provider $provider unavailable as test provider", e)
            }
        }
        if (active.isEmpty()) throw IllegalStateException("No test provider could be registered")
        if (useGms && GmsSupport.isAvailable(appContext)) {
            try {
                val client = LocationServices.getFusedLocationProviderClient(appContext)
                @SuppressLint("MissingPermission")
                client.setMockMode(true).await()
                gms = client
            } catch (e: Exception) {
                // SecurityException here too means "not mock app", but the LocationManager layer already
                // succeeded, so just run without the GMS layer.
                Log.w(TAG, "GMS mock mode unavailable", e)
            }
        }
    }

    /** Pushes one fix to every active provider. */
    @SuppressLint("MissingPermission")
    fun push(state: KinematicState, fix: com.mockroute.core.noise.NoisyFix? = null) {
        for (provider in active) {
            lm.setTestProviderLocation(provider, state.toLocation(provider, fix))
        }
        gms?.setMockLocation(state.toLocation(GMS_PROVIDER, fix))
            ?.addOnFailureListener { Log.w(TAG, "GMS setMockLocation failed", it) }
    }

    /** Disables and removes everything this injector registered. Safe to call repeatedly. */
    @SuppressLint("MissingPermission")
    fun stop() {
        gms?.let { client ->
            runCatching { client.setMockMode(false) }.onFailure { Log.w(TAG, "setMockMode(false) failed", it) }
        }
        gms = null
        for (provider in active) {
            runCatching { lm.setTestProviderEnabled(provider, false) }
            runCatching { lm.removeTestProvider(provider) }
        }
        active.clear()
    }

    // Legacy overload: Criteria.POWER_LOW / ACCURACY_FINE equal ProviderProperties' values (1), lint can't tell.
    @SuppressLint("WrongConstant")
    @Suppress("DEPRECATION")
    private fun addTestProvider(name: String) {
        val network = name == LocationManager.NETWORK_PROVIDER
        val satellite = name == LocationManager.GPS_PROVIDER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val props = ProviderProperties.Builder()
                .setHasNetworkRequirement(network)
                .setHasSatelliteRequirement(satellite)
                .setHasCellRequirement(network)
                .setHasMonetaryCost(false)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
            lm.addTestProvider(name, props)
        } else {
            lm.addTestProvider(
                name,
                network, satellite, network,
                false, true, true, true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE,
            )
        }
    }

    companion object {
        private const val TAG = "MockInjector"
        private const val GMS_PROVIDER = "fused"

        fun providerNames(): List<String> = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }
    }
}

/** Builds a complete Location as required by CLAUDE.md (fresh time + elapsedRealtimeNanos every tick). */
fun KinematicState.toLocation(provider: String, fix: com.mockroute.core.noise.NoisyFix? = null): Location = Location(provider).apply {
    latitude = lat
    longitude = lon
    altitude = altitudeM
    accuracy = accuracyM.toFloat()
    speed = speedMps.toFloat()
    bearing = bearingDeg.toFloat()
    time = System.currentTimeMillis()
    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    // Stage 9: accuracies from the noise model when it is on; fixed plausible values otherwise.
    verticalAccuracyMeters = (fix?.verticalAccuracyM ?: (accuracyM * 1.5)).toFloat()
    speedAccuracyMetersPerSecond = (fix?.speedAccuracyMps ?: 0.5).toFloat()
    bearingAccuracyDegrees = (fix?.bearingAccuracyDeg ?: if (speedMps > 0.3) 10.0 else 180.0).toFloat()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        elapsedRealtimeUncertaintyNanos = 1_000_000.0
    }
}
