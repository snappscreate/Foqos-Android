package com.foqos.android.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.service.session.GlobalSessionProtectionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    private val profileRepository: ProfileRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    data class UiState(
        val themeColorIndex: Int = 0,
        val darkModeOverride: String = "system",
        val emergencyRemaining: Int = 0,
        val emergencyResetWeeks: Int = 4,
        val protection: GlobalSessionProtectionStore.Config = GlobalSessionProtectionStore.Config(),
    )

    private val protectionStore = GlobalSessionProtectionStore(context)
    private val protection = MutableStateFlow(protectionStore.read())

    init {
        viewModelScope.launch {
            protectionStore.migrate(profileRepository.observeAll().first())
            protection.value = protectionStore.read()
            sessionManager.refreshGlobalProtectionSettings()
        }
    }

    val uiState: StateFlow<UiState> = combine(
        settingsDataStore.themeColorIndex,
        settingsDataStore.darkModeOverride,
        settingsDataStore.emergencyUnblocksRemaining,
        settingsDataStore.emergencyResetIntervalWeeks,
        protection,
    ) { themeIndex, darkMode, remaining, resetWeeks, global ->
        UiState(themeIndex, darkMode, remaining, resetWeeks, global)
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

    fun updateProtection(transform: (GlobalSessionProtectionStore.Config) -> GlobalSessionProtectionStore.Config) {
        val changed = transform(protection.value)
        protectionStore.save(changed)
        protection.value = changed
        viewModelScope.launch { sessionManager.refreshGlobalProtectionSettings() }
    }

    fun resetAllState() {
        viewModelScope.launch { sessionManager.resetAllState() }
    }
}
