package com.mockroute.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mockroute.app.MockRouteApp
import com.mockroute.app.data.AppSettings
import com.mockroute.app.data.RealFix
import com.mockroute.app.location.RealLocationReader
import com.mockroute.app.mock.GmsSupport
import com.mockroute.app.mock.MockAppCheck
import com.mockroute.app.mock.MockLocationService
import com.mockroute.app.net.RouteCache
import com.mockroute.app.net.RoutingService
import com.mockroute.app.setup.SetupChecks
import com.mockroute.app.setup.SetupStatus
import com.mockroute.app.sim.SimError
import com.mockroute.app.sim.SimulationRepository
import com.mockroute.app.sim.SimulationState
import com.mockroute.app.sim.TrajectorySpec
import com.mockroute.core.geo.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as MockRouteApp).settings
    private val reader = RealLocationReader(app)

    /** null until DataStore has been read once. */
    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val simulation: StateFlow<SimulationState> = SimulationRepository.state

    private val _selected = MutableStateFlow<LatLon?>(null)
    val selected: StateFlow<LatLon?> = _selected.asStateFlow()

    private val _setup = MutableStateFlow(SetupStatus())
    val setup: StateFlow<SetupStatus> = _setup.asStateFlow()

    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Shown when the user tries to simulate without being the selected mock app. */
    private val _notMockAppDialog = MutableStateFlow(false)
    val notMockAppDialog: StateFlow<Boolean> = _notMockAppDialog.asStateFlow()

    private val _disclaimerDialog = MutableStateFlow(false)
    val disclaimerDialog: StateFlow<Boolean> = _disclaimerDialog.asStateFlow()

    val gmsAvailable: Boolean = GmsSupport.isAvailable(app)

    val routeCache = RouteCache(app)
    val routing = RoutingService(routeCache)
    val editor = RouteEditor(viewModelScope, routing) { settings.value }

    fun realLatLon(): LatLon? = settings.value?.lastRealLocation?.let { LatLon(it.lat, it.lon) }

    fun setEditMode(mode: EditMode) {
        _selected.value = null
        editor.setMode(mode, realLatLon())
    }

    /** Temporary Stage 2 playback: constant speed along the edited route. */
    fun playRoute() {
        val st = editor.state.value
        val geometry = st.result?.geometry
        if (geometry == null || geometry.size < 2 || geometry.lengthM < 1.0) {
            _message.value = "Draw a route first"
            return
        }
        startWith { alt, _ -> TrajectorySpec.Polyline(geometry, st.playSpeedKmh / 3.6, alt) }
    }

    fun setRoutingProvider(id: String) = viewModelScope.launch { repo.setRoutingProvider(id) }
    fun setOrsKey(key: String) = viewModelScope.launch { repo.setOrsApiKey(key) }
    fun setGraphHopperKey(key: String) = viewModelScope.launch { repo.setGraphHopperApiKey(key) }
    private val _routingTest = MutableStateFlow<String?>(null)
    val routingTest: StateFlow<String?> = _routingTest.asStateFlow()

    /** Settings → Test routing: one short request with the current provider/key, showing the exact outcome. */
    fun testRouting() = viewModelScope.launch {
        val s = settings.value ?: return@launch
        val start = realLatLon() ?: LatLon(49.41461, 8.681495)
        val end = com.mockroute.core.geo.GeoMath.destination(start, 45.0, 400.0)
        _routingTest.value = "Testing…"
        _routingTest.value = try {
            val r = routing.test(listOf(start, end), com.mockroute.core.route.RoutingProfile.Walk, s)
            "OK — ${r.providerId}: ${formatDistance(r.distanceM)} route, ${r.geometry.size} points"
        } catch (e: com.mockroute.core.route.RoutingException) {
            e.userMessage
        } catch (e: Exception) {
            "Failed: ${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    fun keyStatus(saved: String): String {
        val k = saved.trim().trim('"', '\'').trim()
        return if (k.isEmpty()) "No key: straight-line routes" else "Saved on this phone: ${k.length} chars, …${k.takeLast(4)}"
    }

    fun clearRouteCache() = viewModelScope.launch {
        routeCache.clear()
        _message.value = "Route cache cleared"
    }

    init {
        // Re-route fallback routes as soon as the provider or an API key changes (no need to re-open screens).
        viewModelScope.launch {
            settings.filterNotNull()
                .map { routing.configSignature(it) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(800) // typing a key changes it on every keystroke
                .collect { editor.refresh() }
        }
        viewModelScope.launch {
            SimulationRepository.state.collect { s ->
                if (s.error != null) {
                    when (s.error) {
                        SimError.NotMockApp -> _notMockAppDialog.value = true
                        is SimError.Other -> _message.value = "Simulation failed: ${s.error.message}"
                    }
                    SimulationRepository.clearError()
                }
            }
        }
    }

    fun refreshSetup() {
        val accepted = settings.value?.disclaimerAccepted ?: false
        _setup.value = SetupChecks.evaluate(getApplication(), accepted)
    }

    fun select(point: LatLon?) {
        _selected.value = point
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun dismissNotMockAppDialog() {
        _notMockAppDialog.value = false
    }

    fun dismissDisclaimerDialog() {
        _disclaimerDialog.value = false
    }

    fun acceptDisclaimer() = viewModelScope.launch {
        repo.setDisclaimerAccepted(true)
        _disclaimerDialog.value = false
        _setup.value = _setup.value.copy(disclaimerAccepted = true)
    }

    fun completeWizard() = viewModelScope.launch { repo.setWizardCompleted(true) }

    fun setGmsLayer(on: Boolean) = viewModelScope.launch { repo.setGmsMockLayer(on) }

    /** Reads the real location (only when no simulation runs) and stores it. */
    fun captureRealLocation(onResult: (RealFix?) -> Unit = {}) = viewModelScope.launch {
        onResult(captureNow())
    }

    private suspend fun captureNow(): RealFix? {
        if (simulation.value.isActive) return settings.value?.lastRealLocation
        if (!reader.hasPermission()) {
            _message.value = "Location permission needed — open Settings → Check setup"
            return settings.value?.lastRealLocation
        }
        _capturing.value = true
        try {
            val fix = reader.capture()
            if (fix != null) {
                repo.setLastRealLocation(fix)
            } else {
                _message.value = "Couldn't get a fresh real location (is Location on?)"
            }
            return fix ?: settings.value?.lastRealLocation
        } finally {
            _capturing.value = false
        }
    }

    fun simulateHere(point: LatLon) = startWith { alt, _ -> TrajectorySpec.Static(point, alt) }

    fun walkStraightTo(point: LatLon) = startWith { alt, from ->
        if (from == null) null else TrajectorySpec.StraightLine(from, point, alt)
    }

    /** Common pre-flight: disclaimer, mock-app check, capture real location BEFORE mocking, then start. */
    private fun startWith(build: (altitudeM: Double, from: LatLon?) -> TrajectorySpec?) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val s = settings.value ?: repo.current()
        if (!s.disclaimerAccepted) {
            _disclaimerDialog.value = true
            return@launch
        }
        if (!MockAppCheck.isSelected(ctx)) {
            _notMockAppDialog.value = true
            return@launch
        }
        if (!reader.hasPermission()) {
            _message.value = "Grant location permission first (Settings → Check setup)"
            return@launch
        }
        val sim = simulation.value
        val (from, alt) = if (sim.isActive && sim.current != null) {
            sim.current.position to sim.current.altitudeM
        } else {
            val real = captureNow()
            real?.let { LatLon(it.lat, it.lon) } to (real?.altitudeM ?: 0.0)
        }
        val spec = build(alt, from)
        if (spec == null) {
            _message.value = "Real location unknown — tap the location button and try again"
            return@launch
        }
        val useGms = (s.gmsMockLayer ?: gmsAvailable) && gmsAvailable
        MockLocationService.start(ctx, spec, useGms)
        _selected.value = null
    }

    fun pause() = MockLocationService.pause(getApplication())
    fun resume() = MockLocationService.resume(getApplication())
    fun stop() = MockLocationService.stop(getApplication())
}
