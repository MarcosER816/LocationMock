package com.mockroute.app.sim

import com.mockroute.core.trajectory.KinematicState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SimStatus { Idle, Running, Paused }

sealed interface SimError {
    /** SecurityException from LocationManager: MockRoute is not the selected mock location app. */
    data object NotMockApp : SimError
    data class Other(val message: String) : SimError
}

data class SimulationState(
    val status: SimStatus = SimStatus.Idle,
    val spec: TrajectorySpec? = null,
    val current: KinematicState? = null,
    val elapsedSeconds: Double = 0.0,
    val finished: Boolean = false,
    val error: SimError? = null,
) {
    val isActive: Boolean get() = status != SimStatus.Idle
}

/** Stage 9 debug overlay: the last ~10 min of (true position, injected position) pairs; empty when noise is off. */
object NoiseDebug {
    data class Sample(val truth: com.mockroute.core.geo.LatLon, val reported: com.mockroute.core.geo.LatLon, val multipath: Boolean)

    private val _samples = MutableStateFlow<List<Sample>>(emptyList())
    val samples: StateFlow<List<Sample>> = _samples.asStateFlow()

    fun add(s: Sample) = _samples.update { (if (it.size >= 600) it.drop(it.size - 599) else it) + s }
    fun clear() {
        _samples.value = emptyList()
    }
}

/** Tick timing of the current/last run: detects background throttling (ticks should be 1 s apart). */
data class TickStats(val ticks: Int = 0, val maxGapMs: Long = 0, val lateTicks: Int = 0, val startedAtMs: Long = 0)

object TickMonitor {
    private val _stats = MutableStateFlow(TickStats())
    val stats: StateFlow<TickStats> = _stats.asStateFlow()
    fun reset() {
        _stats.value = TickStats(startedAtMs = System.currentTimeMillis())
    }
    /** [gapMs] since the previous tick; "late" = more than 1.5 s. */
    fun tick(gapMs: Long) = _stats.update { it.copy(ticks = it.ticks + 1, maxGapMs = maxOf(it.maxGapMs, gapMs), lateTicks = it.lateTicks + if (gapMs > 1500) 1 else 0) }
}

/** In-process source of truth for the simulation, written by [com.mockroute.app.mock.MockLocationService]. */
object SimulationRepository {
    private val _state = MutableStateFlow(SimulationState())
    val state: StateFlow<SimulationState> = _state.asStateFlow()

    fun update(transform: (SimulationState) -> SimulationState) = _state.update(transform)

    fun reportError(error: SimError) = _state.update { SimulationState(error = error) }

    fun clearError() = _state.update { it.copy(error = null) }
}
