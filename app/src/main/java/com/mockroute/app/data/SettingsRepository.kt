package com.mockroute.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mockroute.core.plan.SegmentPlanSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
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
    /** Stage 10. */
    val onboardingDone: Boolean = false,
    /** "system" | "en" | "es". */
    val language: String = "system",
    val imperialUnits: Boolean = false,
    /** "dark" | "light". */
    val mapStyle: String = "dark",
    val disclaimerAccepted: Boolean = false,
    /** null = not chosen yet → default ON when Google Play services is present. */
    val gmsMockLayer: Boolean? = null,
    val lastRealLocation: RealFix? = null,
    /** "ors" | "graphhopper" | "straight". */
    val routingProvider: String = "ors",
    /** Keys pasted in Settings; empty → BuildConfig (GitHub secrets). */
    val orsApiKey: String = "",
    val graphHopperApiKey: String = "",
    /** Stage 5 traffic keys (device-only). */
    val tomTomApiKey: String = "",
    val googleRoutesApiKey: String = "",
    /** TomTom requests made today: (yyyy-MM-dd, count). */
    val trafficRequestDay: String = "",
    val trafficRequestCount: Int = 0,
    /** Overpass queries made today: (yyyy-MM-dd, count). */
    val overpassRequestDay: String = "",
    val overpassRequestCount: Int = 0,
    /** Stage 9: GPS noise. */
    val noise: com.mockroute.core.noise.NoiseSettings = com.mockroute.core.noise.NoiseSettings(),
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val wizardCompleted = booleanPreferencesKey("wizard_completed")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val language = stringPreferencesKey("language")
        val imperial = booleanPreferencesKey("imperial_units")
        val mapStyle = stringPreferencesKey("map_style")
        val disclaimerAccepted = booleanPreferencesKey("disclaimer_accepted")
        val gmsMockLayer = booleanPreferencesKey("gms_mock_layer")
        val realLat = doublePreferencesKey("last_real_lat")
        val realLon = doublePreferencesKey("last_real_lon")
        val realAcc = floatPreferencesKey("last_real_accuracy")
        val realAlt = doublePreferencesKey("last_real_altitude")
        val realTime = longPreferencesKey("last_real_time")
        val routingProvider = stringPreferencesKey("routing_provider")
        val orsKey = stringPreferencesKey("ors_api_key")
        val ghKey = stringPreferencesKey("graphhopper_api_key")
        val segmentPlan = stringPreferencesKey("segment_plan_v1")
        val zoneDef = stringPreferencesKey("zone_def_v1")
        val zonePlan = stringPreferencesKey("zone_plan_v1")
        val tomTomKey = stringPreferencesKey("tomtom_api_key")
        val googleKey = stringPreferencesKey("google_routes_api_key")
        val trafficDay = stringPreferencesKey("traffic_request_day")
        val trafficCount = intPreferencesKey("traffic_request_count")
        val overpassDay = stringPreferencesKey("overpass_request_day")
        val overpassCount = intPreferencesKey("overpass_request_count")
        val noise = stringPreferencesKey("noise_settings_v1")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setWizardCompleted(done: Boolean) = context.dataStore.edit { it[Keys.wizardCompleted] = done }
    suspend fun setOnboardingDone() = context.dataStore.edit { it[Keys.onboardingDone] = true }
    suspend fun setLanguage(l: String) = context.dataStore.edit { it[Keys.language] = l }
    suspend fun setImperial(on: Boolean) = context.dataStore.edit { it[Keys.imperial] = on }
    suspend fun setMapStyle(s: String) = context.dataStore.edit { it[Keys.mapStyle] = s }

    suspend fun setDisclaimerAccepted(ok: Boolean) = context.dataStore.edit { it[Keys.disclaimerAccepted] = ok }

    suspend fun setGmsMockLayer(on: Boolean) = context.dataStore.edit { it[Keys.gmsMockLayer] = on }

    suspend fun setRoutingProvider(id: String) = context.dataStore.edit { it[Keys.routingProvider] = id }

    suspend fun setOrsApiKey(key: String) = context.dataStore.edit { it[Keys.orsKey] = key.trim() }

    suspend fun setGraphHopperApiKey(key: String) = context.dataStore.edit { it[Keys.ghKey] = key.trim() }

    /** Stage 3: speed/time/acceleration settings of the current ROUTE segment (JSON, kotlinx.serialization). */
    val segmentPlan: Flow<SegmentPlanSettings?> = context.dataStore.data.map { prefs ->
        prefs[Keys.segmentPlan]?.let { runCatching { planJson.decodeFromString(SegmentPlanSettings.serializer(), it) }.getOrNull() }
    }

    suspend fun setSegmentPlan(plan: SegmentPlanSettings) = context.dataStore.edit {
        it[Keys.segmentPlan] = planJson.encodeToString(SegmentPlanSettings.serializer(), plan)
    }

    private val planJson = Json { ignoreUnknownKeys = true }

    /** Stage 6: the INDOOR zone and its speed/time settings. */
    suspend fun zoneDefinition(): com.mockroute.core.zone.ZoneDefinition? = context.dataStore.data.first()[Keys.zoneDef]?.let {
        runCatching { planJson.decodeFromString(com.mockroute.core.zone.ZoneDefinition.serializer(), it) }.getOrNull()
    }

    suspend fun setZoneDefinition(d: com.mockroute.core.zone.ZoneDefinition) = context.dataStore.edit {
        it[Keys.zoneDef] = planJson.encodeToString(com.mockroute.core.zone.ZoneDefinition.serializer(), d)
    }

    suspend fun zonePlan(): SegmentPlanSettings? = context.dataStore.data.first()[Keys.zonePlan]?.let {
        runCatching { planJson.decodeFromString(SegmentPlanSettings.serializer(), it) }.getOrNull()
    }

    suspend fun setZonePlan(p: SegmentPlanSettings) = context.dataStore.edit {
        it[Keys.zonePlan] = planJson.encodeToString(SegmentPlanSettings.serializer(), p)
    }

    suspend fun setTomTomApiKey(key: String) = context.dataStore.edit { it[Keys.tomTomKey] = key.trim() }

    suspend fun setGoogleRoutesApiKey(key: String) = context.dataStore.edit { it[Keys.googleKey] = key.trim() }

    /** +1 to today's traffic request counter (resets when the date changes). */
    suspend fun countTrafficRequest(today: String) = context.dataStore.edit {
        val count = if (it[Keys.trafficDay] == today) (it[Keys.trafficCount] ?: 0) else 0
        it[Keys.trafficDay] = today
        it[Keys.trafficCount] = count + 1
    }

    suspend fun setNoise(n: com.mockroute.core.noise.NoiseSettings) = context.dataStore.edit {
        it[Keys.noise] = Json.encodeToString(com.mockroute.core.noise.NoiseSettings.serializer(), n)
    }

    suspend fun countOverpassRequest(today: String) = context.dataStore.edit {
        val count = if (it[Keys.overpassDay] == today) (it[Keys.overpassCount] ?: 0) else 0
        it[Keys.overpassDay] = today
        it[Keys.overpassCount] = count + 1
    }

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
            // Existing installs (wizard already done) don't see the onboarding again.
            onboardingDone = this[Keys.onboardingDone] ?: (this[Keys.wizardCompleted] ?: false),
            language = this[Keys.language] ?: "system",
            imperialUnits = this[Keys.imperial] ?: false,
            mapStyle = this[Keys.mapStyle] ?: "dark",
            disclaimerAccepted = this[Keys.disclaimerAccepted] ?: false,
            gmsMockLayer = this[Keys.gmsMockLayer],
            lastRealLocation = if (lat != null && lon != null) {
                RealFix(lat, lon, this[Keys.realAcc] ?: 0f, this[Keys.realAlt] ?: 0.0, this[Keys.realTime] ?: 0L)
            } else {
                null
            },
            routingProvider = this[Keys.routingProvider] ?: "ors",
            orsApiKey = this[Keys.orsKey] ?: "",
            graphHopperApiKey = this[Keys.ghKey] ?: "",
            tomTomApiKey = this[Keys.tomTomKey] ?: "",
            googleRoutesApiKey = this[Keys.googleKey] ?: "",
            trafficRequestDay = this[Keys.trafficDay] ?: "",
            trafficRequestCount = this[Keys.trafficCount] ?: 0,
            overpassRequestDay = this[Keys.overpassDay] ?: "",
            overpassRequestCount = this[Keys.overpassCount] ?: 0,
            noise = this[Keys.noise]?.let {
                runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(com.mockroute.core.noise.NoiseSettings.serializer(), it) }.getOrNull()
            } ?: com.mockroute.core.noise.NoiseSettings(),
        )
    }
}
