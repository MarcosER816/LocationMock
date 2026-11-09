package com.mockroute.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.mockroute.app.data.RealFix
import com.mockroute.app.mock.GmsSupport
import com.mockroute.app.sim.SimulationRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Reads the device's REAL location. Refuses while a simulation is active (it would just read our own mock)
 * and discards any fix flagged as mock.
 */
class RealLocationReader(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Returns null if a simulation runs, permission is missing, or no fix arrives within [timeoutMs]. */
    suspend fun capture(timeoutMs: Long = 10_000): RealFix? {
        if (SimulationRepository.state.value.isActive || !hasPermission()) return null
        val loc = withTimeoutOrNull(timeoutMs) { fromFused() ?: fromLocationManager() }
        return loc?.takeUnless { it.isMockCompat() }?.toFix()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fromFused(): Location? {
        if (!GmsSupport.isAvailable(context)) return null
        return runCatching {
            val cts = CancellationTokenSource()
            try {
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .await()
            } finally {
                cts.cancel()
            }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fromLocationManager(): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val current = suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                val executor = Executor { it.run() }
                runCatching {
                    lm.getCurrentLocation(provider, signal, executor) { cont.resume(it) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
            if (current != null) return current
        }
        return runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    }

    private fun Location.isMockCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider

    private fun Location.toFix() = RealFix(
        lat = latitude,
        lon = longitude,
        accuracyM = if (hasAccuracy()) accuracy else 0f,
        altitudeM = if (hasAltitude()) altitude else 0.0,
        timeMillis = time,
    )
}
