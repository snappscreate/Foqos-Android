package com.foqos.android.ui.insights

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.foqos.android.domain.session.SessionTimeCalculator
import com.foqos.android.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class InsightsFilter { THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH, ALL }

@HiltViewModel
class InsightsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profileRepository: ProfileRepository,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val profileId: String = checkNotNull(savedStateHandle[Routes.ARG_PROFILE_ID])

    data class UiState(
        val loading: Boolean = true,
        val profile: BlockedProfile? = null,
        val filter: InsightsFilter = InsightsFilter.THIS_WEEK,
        val filteredSessions: List<BlockedProfileSession> = emptyList(),
        val totalFocusTime: Duration = Duration.ZERO,
        val totalBreakTime: Duration = Duration.ZERO,
        val heatmapDays: List<HeatmapDay> = emptyList(),
        val weeklyAggregation: WeeklySessionAggregation? = null,
        val monthlyAggregation: MonthlySessionAggregation? = null,
    )

    private val _filter = MutableStateFlow(InsightsFilter.THIS_WEEK)
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                profileRepository.observeById(profileId),
                sessionRepository.sessionsForProfile(profileId),
                _filter,
            ) { profile, sessions, filter -> Triple(profile, sessions, filter) }
                .collect { (profile, sessions, filter) ->
                    val now = Instant.now()
                    val (from, to) = filter.window(now)
                    val filtered = sessions.filter { s ->
                        val end = s.endTime ?: now
                        s.startTime.isBefore(to) && end.isAfter(from)
                    }.sortedByDescending { it.startTime }

                    var totalFocus = Duration.ZERO
                    var totalBreak = Duration.ZERO
                    if (profile != null) {
                        filtered.forEach { session ->
                            val at = session.endTime ?: now
                            totalFocus = totalFocus.plus(SessionTimeCalculator.elapsedFocusTime(session, profile, at))
                            totalBreak = totalBreak.plus(SessionTimeCalculator.usedBreakDurationIncludingActiveBreak(session, profile, at))
                        }
                    }

                    _uiState.update {
                        it.copy(
                            loading = false,
                            profile = profile,
                            filter = filter,
                            filteredSessions = filtered,
                            totalFocusTime = totalFocus,
                            totalBreakTime = totalBreak,
                            heatmapDays = buildHeatmap(sessions, now),
                            weeklyAggregation = buildWeekly(sessions, now, filter),
                            monthlyAggregation = buildMonthly(sessions, now, filter),
                        )
                    }
                }
        }
    }

    fun setFilter(filter: InsightsFilter) {
        _filter.value = filter
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch { sessionRepository.delete(sessionId) }
    }

    fun deleteAllSessions() {
        viewModelScope.launch { sessionRepository.deleteAllForProfile(profileId) }
    }

    private fun buildHeatmap(sessions: List<BlockedProfileSession>, now: Instant): List<HeatmapDay> {
        val intervals = sessions.map { HeatmapSessionInterval(it.startTime, it.endTime) }
        return HeatmapAggregator.aggregate(intervals, now.atZone(ZoneOffset.UTC).toLocalDate(), ZoneOffset.UTC, now)
    }

    private fun buildWeekly(sessions: List<BlockedProfileSession>, now: Instant, filter: InsightsFilter): WeeklySessionAggregation {
        val reference = if (filter == InsightsFilter.LAST_WEEK) now.minus(Duration.ofDays(7)) else now
        val weekStart = WeeklySessionAggregator.startOfWeek(reference, ZoneOffset.UTC)
        val intervals = sessions.map { WeeklySessionInterval(it.startTime, it.endTime ?: now) }
        return WeeklySessionAggregator.aggregate(intervals, weekStart, ZoneOffset.UTC)
    }

    private fun buildMonthly(sessions: List<BlockedProfileSession>, now: Instant, filter: InsightsFilter): MonthlySessionAggregation {
        val reference = if (filter == InsightsFilter.LAST_MONTH) now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() else now
        val monthStart = MonthlySessionAggregator.startOfMonth(reference, ZoneOffset.UTC)
        val intervals = sessions.map { MonthlySessionInterval(it.startTime, it.endTime ?: now) }
        return MonthlySessionAggregator.aggregate(intervals, monthStart, ZoneOffset.UTC)
    }
}

/** The [from, to) instant window a filter covers, relative to [now]. */
private fun InsightsFilter.window(now: Instant): Pair<Instant, Instant> = when (this) {
    InsightsFilter.THIS_WEEK -> {
        val start = WeeklySessionAggregator.startOfWeek(now, ZoneOffset.UTC)
        start to start.plus(Duration.ofDays(7))
    }
    InsightsFilter.LAST_WEEK -> {
        val thisWeekStart = WeeklySessionAggregator.startOfWeek(now, ZoneOffset.UTC)
        thisWeekStart.minus(Duration.ofDays(7)) to thisWeekStart
    }
    InsightsFilter.THIS_MONTH -> {
        val start = MonthlySessionAggregator.startOfMonth(now, ZoneOffset.UTC)
        start to start.plus(Duration.ofDays(MonthlySessionAggregator.daysInMonth(now, ZoneOffset.UTC).toLong()))
    }
    InsightsFilter.LAST_MONTH -> {
        val thisMonthStart = MonthlySessionAggregator.startOfMonth(now, ZoneOffset.UTC)
        val lastMonthReference = thisMonthStart.minus(Duration.ofDays(1))
        val start = MonthlySessionAggregator.startOfMonth(lastMonthReference, ZoneOffset.UTC)
        start to thisMonthStart
    }
    InsightsFilter.ALL -> Instant.EPOCH to Instant.MAX
}
