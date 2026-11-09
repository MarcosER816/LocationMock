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

/** In-process source of truth for the simulation, written by [com.mockroute.app.mock.MockLocationService]. */
object SimulationRepository {
    private val _state = MutableStateFlow(SimulationState())
    val state: StateFlow<SimulationState> = _state.asStateFlow()

    fun update(transform: (SimulationState) -> SimulationState) = _state.update(transform)

    fun reportError(error: SimError) = _state.update { SimulationState(error = error) }

    fun clearError() = _state.update { it.copy(error = null) }
}
