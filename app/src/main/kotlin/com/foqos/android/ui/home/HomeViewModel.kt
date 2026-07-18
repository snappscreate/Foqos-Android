package com.foqos.android.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foqos.android.data.datastore.SettingsDataStore
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.data.repository.SessionRepository
import com.foqos.android.domain.insights.HeatmapAggregator
import com.foqos.android.domain.insights.HeatmapDay
import com.foqos.android.domain.insights.HeatmapSessionInterval
import com.foqos.android.domain.insights.MonthlySessionAggregation
import com.foqos.android.domain.insights.MonthlySessionAggregator
import com.foqos.android.domain.insights.MonthlySessionInterval
import com.foqos.android.domain.insights.WeeklySessionAggregation
import com.foqos.android.domain.insights.WeeklySessionAggregator
import com.foqos.android.domain.insights.WeeklySessionInterval
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.service.accessibility.ForegroundAppAccessibilityService
import com.foqos.android.service.session.PermissionChecks
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which activity chart is shown on the Home screen's chart card, persisted in [SettingsDataStore]. */
enum class ChartType(val storageKey: String) {
    HEATMAP("heatmap"),
    WEEKLY("weekly"),
    MONTHLY("monthly"),
    ;

    companion object {
        fun fromStorageKey(key: String): ChartType = entries.firstOrNull { it.storageKey == key } ?: WEEKLY
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val sessionRepository: SessionRepository,
    private val sessionManager: SessionManager,
    private val settingsDataStore: SettingsDataStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val profiles: List<BlockedProfile> = emptyList(),
        val activeSession: BlockedProfileSession? = null,
        val activeProfile: BlockedProfile? = null,
        val chartType: ChartType = ChartType.WEEKLY,
        val heatmapDays: List<HeatmapDay> = emptyList(),
        val weeklyAggregation: WeeklySessionAggregation? = null,
        val monthlyAggregation: MonthlySessionAggregation? = null,
        val permissionsMissing: Boolean = false,
        val scheduleOutOfSync: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refreshPermissions()
        viewModelScope.launch {
            combine(
                profileRepository.observeAll(),
                sessionManager.activeSession,
                settingsDataStore.chartType,
            ) { profiles, activeSession, chartTypeKey ->
                Triple(profiles, activeSession, ChartType.fromStorageKey(chartTypeKey))
            }.collect { (profiles, activeSession, chartType) ->
                val activeProfile = activeSession?.let { session -> profiles.firstOrNull { it.id == session.profileId } }
                val now = Instant.now()
                val sessions = sessionRepository.sessionsBetween(now.minusSeconds(INSIGHTS_WINDOW_SECONDS), now)

                _uiState.update {
                    it.copy(
                        loading = false,
                        profiles = profiles,
                        activeSession = activeSession,
                        activeProfile = activeProfile,
                        chartType = chartType,
                        heatmapDays = buildHeatmap(sessions, now),
                        weeklyAggregation = buildWeekly(sessions, now),
                        monthlyAggregation = buildMonthly(sessions, now),
                        scheduleOutOfSync = computeScheduleOutOfSync(profiles, activeSession),
                    )
                }
            }
        }
    }

    fun startProfile(profileId: String) {
        viewModelScope.launch { sessionManager.startProfile(profileId) }
    }

    fun stopActiveSession() {
        viewModelScope.launch { sessionManager.stopActiveSession(fromBackground = false) }
    }

    fun duplicateProfile(profileId: String) {
        viewModelScope.launch { profileRepository.clone(profileId) }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch { profileRepository.delete(profileId) }
    }

    fun setChartType(chartType: ChartType) {
        viewModelScope.launch { settingsDataStore.setChartType(chartType.storageKey) }
    }

    /** Re-checks accessibility/usage-access permission state - call on screen resume. */
    fun refreshPermissions() {
        val hasAccessibility = PermissionChecks.isAccessibilityEnabled(context, ForegroundAppAccessibilityService::class.java)
        val hasUsageAccess = PermissionChecks.hasUsageAccess(context)
        _uiState.update { it.copy(permissionsMissing = !hasAccessibility && !hasUsageAccess) }
    }

    private fun computeScheduleOutOfSync(profiles: List<BlockedProfile>, activeSession: BlockedProfileSession?): Boolean {
        val now = LocalDateTime.now()
        return profiles.any { profile ->
            val schedule = profile.schedule
            schedule != null && schedule.isConfigured && schedule.isActiveAt(now) && activeSession?.profileId != profile.id
        }
    }

    private fun buildHeatmap(sessions: List<BlockedProfileSession>, now: Instant): List<HeatmapDay> {
        val intervals = sessions.map { HeatmapSessionInterval(it.startTime, it.endTime) }
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        return HeatmapAggregator.aggregate(intervals, today, ZoneOffset.UTC, now)
    }

    private fun buildWeekly(sessions: List<BlockedProfileSession>, now: Instant): WeeklySessionAggregation {
        val weekStart = WeeklySessionAggregator.startOfWeek(now, ZoneOffset.UTC)
        val intervals = sessions.mapNotNull { session ->
            WeeklySessionInterval(session.startTime, session.endTime ?: now)
        }
        return WeeklySessionAggregator.aggregate(intervals, weekStart, ZoneOffset.UTC)
    }

    private fun buildMonthly(sessions: List<BlockedProfileSession>, now: Instant): MonthlySessionAggregation {
        val monthStart = MonthlySessionAggregator.startOfMonth(now, ZoneOffset.UTC)
        val intervals = sessions.map { session -> MonthlySessionInterval(session.startTime, session.endTime ?: now) }
        return MonthlySessionAggregator.aggregate(intervals, monthStart, ZoneOffset.UTC)
    }

    companion object {
        private const val INSIGHTS_WINDOW_SECONDS = 40L * 24 * 3600
    }
}
