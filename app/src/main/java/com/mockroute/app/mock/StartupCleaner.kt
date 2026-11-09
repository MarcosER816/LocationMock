package com.mockroute.app.mock

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.util.Log
import com.google.android.gms.location.LocationServices

/**
 * Removes every test provider MockRoute could have registered and turns GMS mock mode off, so the real
 * location comes back. Runs on app start and when the service is destroyed. Never throws.
 */
object StartupCleaner {
    private const val TAG = "StartupCleaner"

    @SuppressLint("MissingPermission")
    fun clean(context: Context, reason: String) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        var removed = 0
        for (provider in MockInjector.providerNames()) {
            try {
                lm.removeTestProvider(provider)
                removed++
            } catch (_: IllegalArgumentException) {
                // wasn't a test provider
            } catch (_: SecurityException) {
                // not the mock app any more: the system already dropped our providers
            }
        }
        if (GmsSupport.isAvailable(context)) {
            runCatching { LocationServices.getFusedLocationProviderClient(context).setMockMode(false) }
        }
        Log.i(TAG, "Cleaned ($reason): removed $removed test provider(s)")
    }
}
