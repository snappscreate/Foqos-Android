package com.foqos.android.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foqos.android.data.datastore.SettingsDataStore
import com.foqos.android.domain.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ActiveSessionViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    settingsDataStore: SettingsDataStore,
) : ViewModel() {

    val activeSession = sessionManager.activeSession
    val activeProfile = sessionManager.activeProfile

    val emergencyUnblocksRemaining: StateFlow<Int> = settingsDataStore.emergencyUnblocksRemaining
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun startBreak() {
        viewModelScope.launch { sessionManager.startBreak() }
    }

    fun endBreak() {
        viewModelScope.launch { sessionManager.endBreak() }
    }

    fun stopSession() {
        viewModelScope.launch { sessionManager.stopActiveSession(fromBackground = false) }
    }

    fun emergencyUnblock() {
        viewModelScope.launch { sessionManager.emergencyUnblock() }
    }
}
