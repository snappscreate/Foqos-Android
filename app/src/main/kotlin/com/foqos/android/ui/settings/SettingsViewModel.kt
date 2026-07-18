package com.foqos.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foqos.android.data.datastore.SettingsDataStore
import com.foqos.android.domain.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val sessionManager: SessionManager,
) : ViewModel() {

    data class UiState(
        val themeColorIndex: Int = 0,
        val darkModeOverride: String = "system",
        val emergencyRemaining: Int = 0,
        val emergencyResetWeeks: Int = 4,
    )

    val uiState: StateFlow<UiState> = combine(
        settingsDataStore.themeColorIndex,
        settingsDataStore.darkModeOverride,
        settingsDataStore.emergencyUnblocksRemaining,
        settingsDataStore.emergencyResetIntervalWeeks,
    ) { themeIndex, darkMode, remaining, resetWeeks ->
        UiState(themeIndex, darkMode, remaining, resetWeeks)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun setThemeColorIndex(index: Int) {
        viewModelScope.launch { settingsDataStore.setThemeColorIndex(index) }
    }

    fun setDarkModeOverride(mode: String) {
        viewModelScope.launch { settingsDataStore.setDarkModeOverride(mode) }
    }

    fun setEmergencyResetWeeks(weeks: Int) {
        viewModelScope.launch { settingsDataStore.setEmergencyResetIntervalWeeks(weeks) }
    }

    fun resetAllState() {
        viewModelScope.launch { sessionManager.resetAllState() }
    }
}
