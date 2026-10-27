package com.foqos.android.ui.profile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSchedule
import com.foqos.android.data.model.SoftUnblockStrategyData
import com.foqos.android.data.model.StrategyPauseTimerData
import com.foqos.android.data.model.StrategyTimerData
import com.foqos.android.data.model.Weekday
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.domain.strategy.BlockingStrategy
import com.foqos.android.domain.strategy.ManualBlockingStrategy
import com.foqos.android.domain.strategy.StrategyRegistry
import com.foqos.android.service.alarm.AlarmActivityType
import com.foqos.android.service.alarm.FoqosAlarmScheduler
import com.foqos.android.service.nfc.NfcForegroundDispatchManager
import com.foqos.android.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One launchable app, for the app picker list. */
data class InstalledAppInfo(val packageName: String, val label: String)

private val DOMAIN_REGEX = Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$")

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val profileRepository: ProfileRepository,
    val nfcDispatchManager: NfcForegroundDispatchManager,
    private val alarmScheduler: FoqosAlarmScheduler,
    private val sessionManager: SessionManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val existingProfileId: String? = savedStateHandle[Routes.ARG_PROFILE_ID]
    val isNewProfile: Boolean = existingProfileId == null

    data class UiState(
        val loading: Boolean = true,
        val draft: BlockedProfile = BlockedProfile(
            id = UUID.randomUUID().toString(),
            name = "",
            strategyId = ManualBlockingStrategy.ID,
        ),
        val isPersisted: Boolean = false,
        val nameError: String? = null,
        val domainError: String? = null,
        val saved: Boolean = false,
        val deleted: Boolean = false,
        val installedApps: List<InstalledAppInfo> = emptyList(),
        val appSearchQuery: String = "",
        val scheduleEnabled: Boolean = false,
        val editingLocked: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sessionManager.activeSession.collect { session ->
                _uiState.update { it.copy(editingLocked = session != null) }
            }
        }
        viewModelScope.launch {
            val existing = existingProfileId?.let { profileRepository.getById(it) }
            _uiState.update {
                if (existing != null) {
                    it.copy(loading = false, draft = existing, isPersisted = true, scheduleEnabled = existing.schedule?.isConfigured == true)
                } else {
                    it.copy(loading = false)
                }
            }
            loadInstalledApps()
        }
    }

    /** Reject profile mutations during any active blocking session. */
    private fun guardedUpdate(update: (UiState) -> UiState) {
        if (_uiState.value.editingLocked) return
        _uiState.update(update)
    }

    // --- Name -----------------------------------------------------------------------------

    fun updateName(name: String) {
        guardedUpdate { it.copy(draft = it.draft.copy(name = name), nameError = null) }
    }

    // --- Strategy ---------------------------------------------------------------------------

    fun selectStrategy(strategy: BlockingStrategy) {
        val defaultData = when {
            strategy.hasTimer -> StrategyTimerData().toJson()
            strategy.hasPauseMode -> StrategyPauseTimerData().toJson()
            !strategy.allowsTimedBreaks -> SoftUnblockStrategyData().normalized.toJson()
            else -> null
        }
        guardedUpdate { it.copy(draft = it.draft.copy(strategyId = strategy.id, strategyDataJson = defaultData)) }
    }

    fun updateStrategyDataJson(json: String) {
        guardedUpdate { it.copy(draft = it.draft.copy(strategyDataJson = json)) }
    }

    // --- Apps -----------------------------------------------------------------------------

    fun toggleApp(packageName: String) {
        guardedUpdate {
            val current = it.draft.blockedPackages
            val updated = if (packageName in current) current - packageName else current + packageName
            it.copy(draft = it.draft.copy(blockedPackages = updated))
        }
    }

    fun setAllowMode(enabled: Boolean) {
        guardedUpdate { it.copy(draft = it.draft.copy(allowModeEnabled = enabled)) }
    }

    fun setAppSearchQuery(query: String) {
        _uiState.update { it.copy(appSearchQuery = query) }
    }

    private suspend fun loadInstalledApps() {
        val apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            resolved
                .asSequence()
                .map { it.activityInfo.packageName }
                .distinct()
                .filterNot { it == context.packageName }
                .mapNotNull { packageName ->
                    runCatching {
                        val appInfo = pm.getApplicationInfo(packageName, 0)
                        InstalledAppInfo(packageName, pm.getApplicationLabel(appInfo).toString())
                    }.getOrNull()
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        }
        _uiState.update { it.copy(installedApps = apps) }
    }

    // --- Domains ----------------------------------------------------------------------------

    fun addDomain(rawValue: String) {
        val normalized = rawValue.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
        if (normalized.isBlank() || !DOMAIN_REGEX.matches(normalized)) {
            guardedUpdate { it.copy(domainError = "Enter a valid domain, e.g. reddit.com") }
            return
        }
        guardedUpdate {
            val updated = if (normalized in it.draft.domains) it.draft.domains else it.draft.domains + normalized
            it.copy(draft = it.draft.copy(domains = updated), domainError = null)
        }
    }

    fun removeDomain(domain: String) {
        guardedUpdate { it.copy(draft = it.draft.copy(domains = it.draft.domains - domain)) }
    }

    fun setAllowModeDomains(enabled: Boolean) {
        guardedUpdate { it.copy(draft = it.draft.copy(allowModeDomains = enabled)) }
    }

    fun clearDomainError() {
        guardedUpdate { it.copy(domainError = null) }
    }

    // --- Schedule ---------------------------------------------------------------------------

    fun setScheduleEnabled(enabled: Boolean) {
        guardedUpdate {
            it.copy(
                scheduleEnabled = enabled,
                draft = if (!enabled) it.draft.copy(schedule = null) else it.draft,
            )
        }
    }

    fun toggleScheduleDay(day: Weekday) {
        guardedUpdate { state ->
            val current = state.draft.schedule ?: BlockedProfileSchedule(emptyList(), 9, 0, 17, 0)
            val days = if (day in current.days) current.days - day else current.days + day
            state.copy(draft = state.draft.copy(schedule = current.copy(days = days, updatedAtEpochMillis = System.currentTimeMillis())))
        }
    }

    fun setScheduleStartTime(hour: Int, minute: Int) {
        guardedUpdate { state ->
            val current = state.draft.schedule ?: BlockedProfileSchedule(emptyList(), hour, minute, 17, 0)
            state.copy(draft = state.draft.copy(schedule = current.copy(startHour = hour, startMinute = minute)))
        }
    }

    fun setScheduleEndTime(hour: Int, minute: Int) {
        guardedUpdate { state ->
            val current = state.draft.schedule ?: BlockedProfileSchedule(emptyList(), 9, 0, hour, minute)
            state.copy(draft = state.draft.copy(schedule = current.copy(endHour = hour, endMinute = minute)))
        }
    }

    // --- Breaks -------------------------------------------------------------------------------

    fun setEnableBreaks(enabled: Boolean) {
        guardedUpdate { it.copy(draft = it.draft.copy(enableBreaks = enabled)) }
    }

    fun setBreakDurationMinutes(minutes: Int) {
        guardedUpdate { it.copy(draft = it.draft.copy(breakTimeInMinutes = minutes)) }
    }

    fun setAllowMultipleBreaks(enabled: Boolean) {
        guardedUpdate { it.copy(draft = it.draft.copy(allowMultipleBreaks = enabled)) }
    }

    // --- Session protection -------------------------------------------------------------------

    fun setEnableStrictMode(enabled: Boolean) {
        guardedUpdate { it.copy(draft = it.draft.copy(enableStrictMode = enabled)) }
    }

    fun setEnableBlockAppInstallation(enabled: Boolean) {
        guardedUpdate { it.copy(draft = it.draft.copy(enableBlockAppInstallation = enabled)) }
    }

    fun setDisableBackgroundStops(enabled: Boolean) {
        guardedUpdate { it.copy(draft = it.draft.copy(disableBackgroundStops = enabled)) }
    }

    fun setEnableEmergencyUnblock(enabled: Boolean) {
        guardedUpdate { it.copy(draft = it.draft.copy(enableEmergencyUnblock = enabled)) }
    }

    // --- Save / delete ------------------------------------------------------------------------

    fun save() {
        if (sessionManager.activeSession.value != null) return
        val trimmedName = _uiState.value.draft.name.trim()
        if (trimmedName.isBlank()) {
            _uiState.update { it.copy(nameError = "Name is required") }
            return
        }

        viewModelScope.launch {
            val current = _uiState.value
            if (sessionManager.activeSession.value != null) return@launch
            val schedule = current.draft.schedule?.takeIf { current.scheduleEnabled && it.isConfigured }
            // Physical unlock items are edited in a separate repository-backed component.
            // Never overwrite newer NFC/QR registrations with this screen's stale draft.
            val latestPersisted = if (current.isPersisted) profileRepository.getById(current.draft.id) else null
            val toSave = current.draft.copy(
                name = trimmedName,
                schedule = schedule,
                physicalUnblockItems = latestPersisted?.physicalUnblockItems
                    ?: current.draft.physicalUnblockItems,
                updatedAt = Instant.now(),
            )

            val saved = if (!current.isPersisted) profileRepository.create(toSave) else profileRepository.upsert(toSave)

            if (saved.schedule?.isConfigured == true) {
                alarmScheduler.scheduleWeeklySchedule(saved)
            } else {
                alarmScheduler.cancel(AlarmActivityType.SCHEDULE_START, saved.id)
                alarmScheduler.cancel(AlarmActivityType.SCHEDULE_END, saved.id)
            }

            // If this profile owns the currently running session, re-apply its config so edits
            // (e.g. turning off website allow-mode) take effect immediately instead of only after
            // the next session start.
            sessionManager.refreshActiveProfileConfig(saved.id)

            _uiState.update { it.copy(draft = saved, isPersisted = true, saved = true) }
        }
    }

    fun delete() {
        if (sessionManager.activeSession.value != null) return
        val id = _uiState.value.draft.id
        viewModelScope.launch {
            if (sessionManager.activeSession.value != null) return@launch
            profileRepository.delete(id)
            alarmScheduler.cancelAllForProfile(id)
            _uiState.update { it.copy(deleted = true) }
        }
    }

    /** All strategies in registry order - used by the strategy picker sheet, which groups them by mechanism. */
    fun pickerStrategies() = StrategyRegistry.all
}
