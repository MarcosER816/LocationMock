package com.mockroute.app.ui

import com.mockroute.app.data.AppSettings
import com.mockroute.app.data.SettingsRepository
import com.mockroute.app.net.TomTomTrafficProvider
import com.mockroute.app.net.TrafficDiskCache
import com.mockroute.core.route.RouteGeometry
import com.mockroute.core.route.RoutingProfile
import com.mockroute.core.traffic.TrafficAnalysis
import com.mockroute.core.traffic.TrafficException
import com.mockroute.core.traffic.TrafficMode
import com.mockroute.core.traffic.TrafficPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

sealed interface TrafficStatus {
    data object Off : TrafficStatus // walk mode or no route
    data class NoKey(val message: String) : TrafficStatus
    data object Loading : TrafficStatus
    data class Ready(val analysis: TrafficAnalysis, val geometry: RouteGeometry) : TrafficStatus
    data class Error(val message: String) : TrafficStatus
}

/**
 * Car/Bus: when the traced route settles (debounced 3 s), or the departure / re-fetch changes, runs the
 * TomTom analysis (≤ 12 requests, cache first) and hands the fitted settings to [onSettings].
 */
@OptIn(FlowPreview::class)
class TrafficController(
    private val scope: CoroutineScope,
    private val repo: SettingsRepository,
    private val cache: TrafficDiskCache,
    private val route: StateFlow<Pair<RouteGeometry?, RoutingProfile>>,
    private val settings: StateFlow<AppSettings?>,
    private val onSettings: (TrafficAnalysis) -> Unit,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Departure (epoch s); default = now + 1 min when first needed. */
    private val _departure = MutableStateFlow(nowS() + 60)
    val departure: StateFlow<Long> = _departure.asStateFlow()

    private val refetch = MutableStateFlow(0)

    private val _status = MutableStateFlow<TrafficStatus>(TrafficStatus.Off)
    val status: StateFlow<TrafficStatus> = _status.asStateFlow()

    fun setDeparture(epochS: Long) {
        _departure.value = maxOf(epochS, nowS() + 60)
    }

    fun refetchNow() {
        if (_departure.value < nowS() + 60) _departure.value = nowS() + 60
        refetch.value++
    }

    init {
        scope.launch {
            combine(route, _departure, refetch, keyFlow()) { r, dep, n, key -> Req(r.first, r.second, dep, n, key) }
                .distinctUntilChanged()
                // 3 s: long enough that street-by-street tracing has settled (each analysis can cost up to 12 requests).
                .debounce(3000)
                .collectLatest { run(it) }
        }
    }

    private fun keyFlow() = kotlinx.coroutines.flow.flow {
        settings.collect { emit(it?.tomTomApiKey.orEmpty()) }
    }.distinctUntilChanged()

    private data class Req(val g: RouteGeometry?, val profile: RoutingProfile, val dep: Long, val n: Int, val key: String)

    private suspend fun run(r: Req) {
        val g = r.g
        if (g == null || r.profile == RoutingProfile.Walk || g.lengthM < 200) {
            _status.value = TrafficStatus.Off
            return
        }
        if (r.key.isBlank()) {
            _status.value = TrafficStatus.NoKey(TrafficException.MissingKey("TomTom").userMessage)
            return
        }
        _status.value = TrafficStatus.Loading
        val mode = if (r.profile == RoutingProfile.Bus) TrafficMode.Bus else TrafficMode.Car
        val provider = TomTomTrafficProvider(r.key, zone) { repo.countTrafficRequest(LocalDate.now(zone).toString()) }
        val dep = maxOf(r.dep, nowS() + 60)
        _status.value = try {
            val a = TrafficPlanner.analyze(g, dep, mode, provider, cache, nowS(), zone)
            onSettings(a)
            TrafficStatus.Ready(a, g)
        } catch (e: TrafficException) {
            TrafficStatus.Error(e.userMessage)
        } catch (e: Exception) {
            TrafficStatus.Error("Traffic failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
    }

    /** Today's TomTom request count (0 if the stored day isn't today). */
    fun requestsToday(s: AppSettings?): Int =
        if (s != null && s.trafficRequestDay == LocalDate.now(zone).toString()) s.trafficRequestCount else 0

    private fun nowS() = System.currentTimeMillis() / 1000
}
