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
import com.mockroute.core.plan.AccelerationPreset
import com.mockroute.core.plan.CoupledSliders
import com.mockroute.core.plan.SegmentPlanSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    // ---- Stage 3: speed / time / acceleration plan for the current segment --------------------------

    private val _plan = MutableStateFlow<SegmentPlanSettings?>(null)
    val plan: StateFlow<SegmentPlanSettings?> = _plan.asStateFlow()

    /** Length of the segment being planned (the geometry that will actually be played). */
    fun segmentDistance(): Double? = editor.state.value.result?.geometry?.lengthM

    /** User edits: clear the "auto, from traffic" flag. */
    fun updatePlan(transform: (SegmentPlanSettings) -> SegmentPlanSettings) {
        val cur = _plan.value ?: defaultPlan()
        _plan.value = transform(cur).copy(autoFromTraffic = false)
    }

    fun resetPlan() {
        _plan.value = defaultPlan()
    }

    fun applyCoupled(c: CoupledSliders) = updatePlan { it.applyCoupled(c) }

    private fun defaultPlan() = SegmentPlanSettings.defaults(editor.state.value.profile, segmentDistance() ?: 1000.0)

    private fun initPlan() {
        viewModelScope.launch {
            _plan.value = repo.segmentPlan.first() // may be null → defaults on first open
            // Persist edits, debounced (sliders emit many values).
            _plan.filterNotNull().drop(1).debounce(500).collect { repo.setSegmentPlan(it) }
        }
        // Switching Walk/Car/Bus picks that profile's default acceleration preset.
        viewModelScope.launch {
            editor.state.map { it.profile }.distinctUntilChanged().drop(1).collect { p ->
                if (_plan.value != null) {
                    val preset = AccelerationPreset.defaultFor(p)
                    updatePlan { it.copy(aMaxMps2 = preset.aMps2, accelPreset = preset) }
                }
            }
        }
    }

    // ---- Stage 4: generated speed plan ------------------------------------------------------------

    private val routeForPlan: StateFlow<Pair<com.mockroute.core.route.RouteGeometry?, com.mockroute.core.route.RoutingProfile>> =
        editor.state.map { it.result?.takeUnless { r -> it.loading }?.geometry to it.profile }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null to com.mockroute.core.route.RoutingProfile.Walk)

    // ---- Stage 5: traffic --------------------------------------------------------------------------
    val trafficCache = com.mockroute.app.net.TrafficDiskCache(app)
    val traffic = TrafficController(viewModelScope, repo, trafficCache, routeForPlan, settings) { analysis ->
        // Fill the Stage 3 settings ("auto, from traffic"); everything stays editable.
        _plan.value = analysis.settings.copy(correlationLengthM = _plan.value?.correlationLengthM)
    }

    val planner = PlanController(viewModelScope, routeForPlan, _plan, traffic.status)

    /** Diagnostics: route cache key, cached samples on disk, last cache problem. */
    fun trafficCacheInfo(status: TrafficStatus): String {
        val key = (status as? TrafficStatus.Ready)?.analysis?.routeKey?.take(6) ?: "—"
        val problem = trafficCache.lastProblem?.let { " · cache problem: $it" } ?: ""
        return "cache: route $key · ${trafficCache.entryCount()} samples stored$problem"
    }

    // ---- Stage 6: INDOOR zone --------------------------------------------------------------------------
    val zone = ZoneEditor()
    private val zoneDef: StateFlow<com.mockroute.core.zone.ZoneDefinition> =
        zone.state.map { it.def }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, com.mockroute.core.zone.ZoneDefinition())
    private val _zonePlanSettings = MutableStateFlow(SegmentPlanSettings.zoneDefaults())
    val zonePlanSettings: StateFlow<SegmentPlanSettings> = _zonePlanSettings.asStateFlow()
    val zonePlanner = ZonePlanController(viewModelScope, zoneDef, _zonePlanSettings)

    fun updateZonePlan(transform: (SegmentPlanSettings) -> SegmentPlanSettings) {
        _zonePlanSettings.value = transform(_zonePlanSettings.value)
    }

    fun resetZonePlan() {
        _zonePlanSettings.value = SegmentPlanSettings.zoneDefaults()
    }

    /** Feasibility distance for the zone: the shortest inside path A→B. */
    fun zoneShortestPath(): Double? = zonePlanner.shortestPath(zone.state.value.def)

    fun playZone() {
        val ready = zonePlanner.status.value as? ZoneStatus.Ready
        if (ready == null) {
            _message.value = "Finish the zone first (shape, A and B on the edge)"
            return
        }
        startWith { _, _ -> TrajectorySpec.Zone(ready.generated.zone) }
    }

    private fun initZone() {
        viewModelScope.launch {
            repo.zoneDefinition()?.let(zone::load)
            repo.zonePlan()?.let { _zonePlanSettings.value = it }
            launch { zoneDef.drop(1).debounce(500).collect { repo.setZoneDefinition(it) } }
            launch { _zonePlanSettings.drop(1).debounce(500).collect { repo.setZonePlan(it) } }
        }
    }

    fun setTomTomKey(key: String) = viewModelScope.launch { repo.setTomTomApiKey(key) }
    fun setGoogleRoutesKey(key: String) = viewModelScope.launch { repo.setGoogleRoutesApiKey(key) }

    fun playPlan() {
        val ready = planner.status.value as? PlanStatus.Ready
        if (ready == null) {
            _message.value = "No plan yet — draw a feasible route first"
            return
        }
        startWith { alt, _ -> TrajectorySpec.Plan(ready.generated.geometry, ready.generated.plan, alt) }
    }

    /** Ensures a plan exists before the plan screen opens. */
    fun ensurePlan() {
        if (_plan.value == null) _plan.value = defaultPlan()
    }

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
        initPlan()
        initZone()
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
