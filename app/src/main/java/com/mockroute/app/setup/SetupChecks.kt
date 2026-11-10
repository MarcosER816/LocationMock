package com.mockroute.app.setup

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mockroute.app.mock.MockAppCheck

data class SetupStatus(
    val developerOptions: Boolean = false,
    val mockAppSelected: Boolean = false,
    val locationPermission: Boolean = false,
    val notificationPermission: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val disclaimerAccepted: Boolean = false,
) {
    /** Required to simulate at all. Battery and notifications are strongly recommended but optional. */
    val requiredOk: Boolean get() = developerOptions && mockAppSelected && locationPermission && disclaimerAccepted
    val allOk: Boolean get() = requiredOk && notificationPermission && batteryUnrestricted
}

object SetupChecks {
    fun evaluate(context: Context, disclaimerAccepted: Boolean) = SetupStatus(
        developerOptions = developerOptionsEnabled(context),
        mockAppSelected = MockAppCheck.isSelected(context),
        locationPermission = granted(context, Manifest.permission.ACCESS_FINE_LOCATION),
        notificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(context, Manifest.permission.POST_NOTIFICATIONS),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true,
        disclaimerAccepted = disclaimerAccepted,
    )

    fun developerOptionsEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    val notificationPermission: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
}

/** Deep links, each with a safe fallback so a missing screen on some ROM never crashes. */
object SetupIntents {
    fun openDeveloperOptions(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    /** "About phone", where OS version is tapped 7× to unlock Developer options. */
    fun openAboutPhone(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetailsIntent(context),
    )

    fun isXiaomi(): Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.lowercase() in setOf("xiaomi", "poco", "redmi")

    /** MIUI / HyperOS Autostart manager; falls back to the app's info page. */
    fun openAutostart(context: Context) = launchFirst(
        context,
        Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        appDetailsIntent(context),
    )

    fun openAppDetails(context: Context) = launchFirst(context, appDetailsIntent(context))

    fun openLocationSettings(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    private fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun launchFirst(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}
