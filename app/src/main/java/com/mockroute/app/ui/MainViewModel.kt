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
import com.mockroute.app.setup.SetupChecks
import com.mockroute.app.setup.SetupStatus
import com.mockroute.app.sim.SimError
import com.mockroute.app.sim.SimulationRepository
import com.mockroute.app.sim.SimulationState
import com.mockroute.app.sim.TrajectorySpec
import com.mockroute.core.geo.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    init {
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
