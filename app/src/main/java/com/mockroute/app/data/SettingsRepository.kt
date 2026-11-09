package com.mockroute.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Last real (non-mock) fix, captured before any simulation starts. */
data class RealFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val altitudeM: Double,
    val timeMillis: Long,
)

data class AppSettings(
    val wizardCompleted: Boolean = false,
    val disclaimerAccepted: Boolean = false,
    /** null = not chosen yet → default ON when Google Play services is present. */
    val gmsMockLayer: Boolean? = null,
    val lastRealLocation: RealFix? = null,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val wizardCompleted = booleanPreferencesKey("wizard_completed")
        val disclaimerAccepted = booleanPreferencesKey("disclaimer_accepted")
        val gmsMockLayer = booleanPreferencesKey("gms_mock_layer")
        val realLat = doublePreferencesKey("last_real_lat")
        val realLon = doublePreferencesKey("last_real_lon")
        val realAcc = floatPreferencesKey("last_real_accuracy")
        val realAlt = doublePreferencesKey("last_real_altitude")
        val realTime = longPreferencesKey("last_real_time")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setWizardCompleted(done: Boolean) = context.dataStore.edit { it[Keys.wizardCompleted] = done }

    suspend fun setDisclaimerAccepted(ok: Boolean) = context.dataStore.edit { it[Keys.disclaimerAccepted] = ok }

    suspend fun setGmsMockLayer(on: Boolean) = context.dataStore.edit { it[Keys.gmsMockLayer] = on }

    suspend fun setLastRealLocation(fix: RealFix) = context.dataStore.edit {
        it[Keys.realLat] = fix.lat
        it[Keys.realLon] = fix.lon
        it[Keys.realAcc] = fix.accuracyM
        it[Keys.realAlt] = fix.altitudeM
        it[Keys.realTime] = fix.timeMillis
    }

    private fun Preferences.toSettings(): AppSettings {
        val lat = this[Keys.realLat]
        val lon = this[Keys.realLon]
        return AppSettings(
            wizardCompleted = this[Keys.wizardCompleted] ?: false,
            disclaimerAccepted = this[Keys.disclaimerAccepted] ?: false,
            gmsMockLayer = this[Keys.gmsMockLayer],
            lastRealLocation = if (lat != null && lon != null) {
                RealFix(lat, lon, this[Keys.realAcc] ?: 0f, this[Keys.realAlt] ?: 0.0, this[Keys.realTime] ?: 0L)
            } else {
                null
            },
        )
    }
}
