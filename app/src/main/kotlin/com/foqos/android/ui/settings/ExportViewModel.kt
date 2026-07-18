package com.foqos.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.data.repository.SessionRepository
import com.foqos.android.domain.export.CsvExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    data class UiState(
        val profiles: List<BlockedProfile> = emptyList(),
        val selectedProfileIds: Set<String> = emptySet(),
        val ascending: Boolean = true,
        val useUtc: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            profileRepository.observeAll().collect { profiles ->
                _uiState.update {
                    val selected = if (it.profiles.isEmpty() && it.selectedProfileIds.isEmpty()) {
                        // First load: default to "export everything" (all profiles pre-selected).
                        profiles.map { p -> p.id }.toSet()
                    } else {
                        it.selectedProfileIds.filter { id -> profiles.any { p -> p.id == id } }.toSet()
                    }
                    it.copy(profiles = profiles, selectedProfileIds = selected)
                }
            }
        }
    }

    fun toggleProfile(profileId: String) {
        _uiState.update {
            val updated = if (profileId in it.selectedProfileIds) it.selectedProfileIds - profileId else it.selectedProfileIds + profileId
            it.copy(selectedProfileIds = updated)
        }
    }

    fun setAscending(ascending: Boolean) {
        _uiState.update { it.copy(ascending = ascending) }
    }

    fun setUseUtc(useUtc: Boolean) {
        _uiState.update { it.copy(useUtc = useUtc) }
    }

    /** Builds the CSV export text for the current selection (all profiles if none explicitly selected). */
    suspend fun buildCsv(): String {
        val state = _uiState.value
        val targetProfiles = state.profiles.filter { it.id in state.selectedProfileIds }

        val pairs = targetProfiles.flatMap { profile ->
            sessionRepository.sessionsForProfile(profile.id).first().map { session -> session to profile.name }
        }

        val zone = if (state.useUtc) ZoneOffset.UTC else ZoneId.systemDefault()
        return CsvExporter.buildCsv(pairs, zone, state.ascending)
    }
}
