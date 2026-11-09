package com.mockroute.app.mock

import android.app.AppOpsManager
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Process
import com.mockroute.app.sim.SimulationRepository

/** Is MockRoute currently selected as the mock location app? */
object MockAppCheck {
    private const val PROBE = "mockroute_probe"

    fun isSelected(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
            }
        }.getOrNull()
        if (mode == AppOpsManager.MODE_ALLOWED) return true
        // AppOps can be ambiguous on some ROMs: probe with a throwaway test provider (never while simulating).
        if (SimulationRepository.state.value.isActive) return true
        return probe(context)
    }

    // 1 == Criteria.POWER_LOW == ProviderProperties.POWER_USAGE_LOW; 1 == ACCURACY_FINE in both.
    @android.annotation.SuppressLint("WrongConstant")
    @Suppress("DEPRECATION")
    private fun probe(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return try {
            lm.addTestProvider(PROBE, false, false, false, false, false, false, false, 1, 1)
            runCatching { lm.removeTestProvider(PROBE) }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            // Already exists → we were allowed to add it before; clean up.
            runCatching { lm.removeTestProvider(PROBE) }.isSuccess
        }
    }
}
