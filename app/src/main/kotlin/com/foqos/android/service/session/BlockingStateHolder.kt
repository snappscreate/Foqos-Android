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
    val state: StateFlow<BlockingState?> = _state.asStateFlow()

    fun setState(state: BlockingState) {
        _state.value = state
    }

    fun clear() {
        _state.value = null
    }

    fun setBreakActive(active: Boolean) {
        _state.update { it?.copy(breakActive = active) }
    }

    fun setPauseActive(active: Boolean) {
        _state.update { it?.copy(pauseActive = active) }
    }

    /** Applies [transform] to the current grants map (e.g. add a new grant, remove an expired one). */
    fun updateGrants(transform: (Map<String, Long>) -> Map<String, Long>) {
        _state.update { current -> current?.copy(activeGrants = transform(current.activeGrants)) }
    }
}
