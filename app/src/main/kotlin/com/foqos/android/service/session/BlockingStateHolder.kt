package com.foqos.android.service.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pure in-memory holder for the current [BlockingState], shared across the app process (Phase 5's
 * accessibility service reads it to decide what to block; [SessionManager] is the only writer).
 * Nothing here touches persistence or Android frameworks.
 */
@Singleton
class BlockingStateHolder @Inject constructor() {

    private val _state = MutableStateFlow<BlockingState?>(null)
    private val _states = MutableStateFlow<Map<String, BlockingState>>(emptyMap())
    val states: StateFlow<Map<String, BlockingState>> = _states.asStateFlow()
    val state: StateFlow<BlockingState?> = _state.asStateFlow()

    fun setState(state: BlockingState) {
        _state.value = state
        _states.update { it + (state.profileId to state) }
    }

    /** One profile stopping must not clear other active profiles. */
    fun clearProfile(profileId: String) {
        _states.update { it - profileId }
        if (_state.value?.profileId == profileId) {
            _state.value = _states.value.values.firstOrNull()
        }
    }

    fun replaceStates(states: Collection<BlockingState>) {
        _states.value = states.associateBy { it.profileId }
        _state.value = states.firstOrNull()
    }

    fun stateForProfile(profileId: String): BlockingState? = _states.value[profileId]

    fun clear() {
        _states.value = emptyMap()
        _state.value = null
    }

    /** Update one profile only; a pause in profile A must not unlock profile B. */
    fun updateProfile(profileId: String, transform: (BlockingState) -> BlockingState) {
        _states.update { states ->
            states[profileId]?.let { states + (profileId to transform(it)) } ?: states
        }
        if (_state.value?.profileId == profileId) {
            _state.value = _states.value[profileId]
        }
    }

    private fun updateCurrent(transform: (BlockingState) -> BlockingState) {
        val current = _state.value ?: return
        updateProfile(current.profileId, transform)
    }

    fun setPeriodicActivatedAt(cycle: Long) {
        updateCurrent { it.copy(periodicActivatedAtEpochMillis = cycle, periodicAccessActive = true) }
    }

    fun setPeriodicAccessActive(active: Boolean) {
        updateCurrent { it.copy(periodicAccessActive = active) }
    }

    fun setPeriodicAccessAnchor(anchorEpochMillis: Long, active: Boolean) {
        updateCurrent { it.copy(periodicAnchorEpochMillis = anchorEpochMillis, periodicActivatedAtEpochMillis = 0L, periodicAccessActive = active) }
    }

    fun setBreakActive(active: Boolean) {
        updateCurrent { it.copy(breakActive = active) }
    }

    fun setLocationSuspended(suspended: Boolean) {
        updateCurrent { it.copy(locationSuspended = suspended) }
    }

    fun setPauseActive(active: Boolean) {
        updateCurrent { it.copy(pauseActive = active) }
    }

    /** Applies [transform] to the current grants map (e.g. add a new grant, remove an expired one). */
    fun updateGrants(transform: (Map<String, Long>) -> Map<String, Long>) {
        updateCurrent { current -> current.copy(activeGrants = transform(current.activeGrants)) }
    }
}
