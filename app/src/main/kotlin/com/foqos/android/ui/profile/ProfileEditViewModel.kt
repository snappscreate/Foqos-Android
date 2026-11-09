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
import com.foqos.android.service.location.LocationAutomationStore
import com.foqos.android.service.location.GeofenceManager
import com.foqos.android.service.nfc.NfcForegroundDispatchManager
import com.foqos.android.service.session.FullScreenModeStore
import com.foqos.android.service.session.PeriodicAccessStore
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
        val configurationError: String? = null,
        val domainError: String? = null,
        val saved: Boolean = false,
        val deleted: Boolean = false,
        val installedApps: List<InstalledAppInfo> = emptyList(),
        val appSearchQuery: String = "",
        val scheduleEnabled: Boolean = false,
        val editingLocked: Boolean = false,
        val fullScreenEnabled: Boolean = false,
        val fullScreenAllowedPackages: Set<String> = emptySet(),
        val fullScreenAllowedUrls: Set<String> = emptySet(),
        val blockedUrls: Set<String> = emptySet(),
        val blockedUrlError: String? = null,
        val periodicAccessEnabled: Boolean = false,
        val periodicWaitMinutes: Int = 60,
        val periodicAccessMinutes: Int = 5,
    )

    val locationAutomationViewModel = com.foqos.android.ui.settings.LocationAutomationViewModel(sessionManager, context)

    private val fullScreenStore = FullScreenModeStore(context)
    private val periodicStore = PeriodicAccessStore(context)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sessionManager.activeSessions.collect { sessions ->
                // Only a running instance of this editor's own profile locks its fields.
                // New profiles remain editable while any other session is active.
                val locked = existingProfileId != null &&
                    sessions.any { it.profileId == existingProfileId }
                _uiState.update { it.copy(editingLocked = locked) }
            }
        }
        viewModelScope.launch {
            val existing = existingProfileId?.let { profileRepository.getById(it) }
            _uiState.update {
                if (existing != null) {
                    it.copy(
                        loading = false, draft = existing, isPersisted = true,
                        scheduleEnabled = existing.schedule?.isConfigured == true,
                        periodicAccessEnabled = periodicStore.read(existing.id).enabled,
                        periodicWaitMinutes = periodicStore.read(existing.id).waitMinutes.takeIf { it > 0 } ?: 60,
                        periodicAccessMinutes = periodicStore.read(existing.id).accessMinutes.takeIf { it > 0 } ?: 5,
                        fullScreenEnabled = fullScreenStore.enabled(existing.id),
                        // Old releases stored the focus allowlist in domains with
                        // allowModeDomains=true. Never reinterpret those as blocked sites.
                        blockedUrls = if (existing.allowModeDomains) emptySet()
                            else existing.domains.mapNotNull { FullScreenModeStore.normalizeUrl(it) }.toSet(),
                        fullScreenAllowedPackages = fullScreenStore.allowedPackages(existing.id),
                        // One shared website whitelist across both modes. Prefer the persisted
                        // fullscreen list; fall back to legacy website allow-mode entries.
                        fullScreenAllowedUrls = fullScreenStore.allowedUrls(existing.id).ifEmpty {
                            if (existing.allowModeDomains) existing.domains.mapNotNull {
                                domain -> FullScreenModeStore.normalizeUrl(domain)
                            }.toSet() else emptySet()
                        },
                    )
                } else {
                    it.copy(loading = false)
                }
            }
            loadInstalledApps()
        }
    }

    /** An unrelated active profile never locks this editor or its save/delete actions. */
    private fun isEditingProfileRunning(): Boolean =
        existingProfileId != null &&
            sessionManager.activeSessions.value.any { it.profileId == existingProfileId }

    /** Reject changes only for the profile currently being edited when it is running. */
    private fun guardedUpdate(update: (UiState) -> UiState) {
        if (_uiState.value.editingLocked) return
        _uiState.update(update)
    }

    // --- Name -----------------------------------------------------------------------------

    fun updateName(name: String) {
        // Name is the only field intentionally editable during an active session.
        _uiState.update { it.copy(draft = it.draft.copy(name = name), nameError = null) }
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

    // Fullscreen mode has its own allowlist. The existing block list is unchanged.
    fun setFullScreenEnabled(enabled: Boolean) {
        guardedUpdate { it.copy(fullScreenEnabled = enabled) }
    }

    fun toggleFullScreenAllowedPackage(packageName: String) {
        guardedUpdate { current ->
            val allowed = current.fullScreenAllowedPackages
            current.copy(fullScreenAllowedPackages =
                if (packageName in allowed) allowed - packageName else allowed + packageName)
        }
    }

    fun addFullScreenAllowedUrl(value: String) {
        val normalized = FullScreenModeStore.normalizeUrl(value) ?: return
        guardedUpdate {
            if (normalized in it.blockedUrls) {
                it.copy(blockedUrlError = "このURLはブロック対象にも登録されているよ。どちらかを削除してね",
                    configurationError = "許可URLとブロックURLに同じドメインは登録できないよ")
            } else it.copy(fullScreenAllowedUrls = it.fullScreenAllowedUrls + normalized,
                blockedUrlError = null, configurationError = null)
        }
    }

    fun addBlockedUrl(value: String) {
        val normalized = FullScreenModeStore.normalizeUrl(value)
        if (normalized == null) {
            guardedUpdate { it.copy(blockedUrlError = "有効なURLかドメインを入力してね") }
            return
        }
        guardedUpdate {
            if (normalized in it.fullScreenAllowedUrls) {
                it.copy(blockedUrlError = "このURLは許可URLにも登録されているよ。どちらかを削除してね")
            } else it.copy(blockedUrls = it.blockedUrls + normalized,
                blockedUrlError = null, configurationError = null)
        }
    }

    fun removeBlockedUrl(value: String) {
        guardedUpdate { it.copy(blockedUrls = it.blockedUrls - value, blockedUrlError = null) }
    }

    fun removeFullScreenAllowedUrl(value: String) {
        guardedUpdate {
            it.copy(fullScreenAllowedUrls = it.fullScreenAllowedUrls - value)
        }
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

    fun setPeriodicAccessEnabled(enabled: Boolean) {
        guardedUpdate { it.copy(periodicAccessEnabled = enabled) }
    }

    fun setPeriodicWaitMinutes(minutes: Int) {
        guardedUpdate { it.copy(periodicWaitMinutes = minutes.coerceIn(1, 1440)) }
    }

    fun setPeriodicAccessMinutes(minutes: Int) {
        guardedUpdate { it.copy(periodicAccessMinutes = minutes.coerceIn(1, 1440)) }
    }

    // --- Save / delete ------------------------------------------------------------------------

    fun save() {
        val trimmedName = _uiState.value.draft.name.trim()
        if (trimmedName.isBlank()) {
            _uiState.update { it.copy(nameError = "プロファイル名を入力してください") }
            return
        }

        viewModelScope.launch {
            val current = _uiState.value
            val duplicated = current.blockedUrls.intersect(current.fullScreenAllowedUrls)
            if (duplicated.isNotEmpty()) {
                _uiState.update {
                    it.copy(configurationError = "許可URLとブロックURLが重複しているよ：${duplicated.joinToString()}",
                        blockedUrlError = "重複しているドメインをどちらかから削除してね")
                }
                return@launch
            }
            if (isEditingProfileRunning()) {
                // Never write a stale draft over a running profile's security settings.
                val saved = profileRepository.rename(current.draft.id, trimmedName) ?: return@launch
                sessionManager.refreshActiveProfileConfig(saved.id)
                _uiState.update {
                    it.copy(draft = saved, isPersisted = true, saved = true)
                }
                return@launch
            }
            // The legacy Schedule and Breaks controls were removed from the editor.
            // Physical unlock items are edited in a separate repository-backed component.
            // Never overwrite newer NFC/QR registrations with this screen's stale draft.
            // Activate the independent policy on save, even when the user leaves
            // the default radio buttons unchanged.
            val policyStore = com.foqos.android.ui.activitylab.ProfileUnlockPolicyStore(context)
            if (!policyStore.configured(current.draft.id)) {
                policyStore.save(current.draft.id, policyStore.forProfile(current.draft))
            }
            val latestPersisted = if (current.isPersisted) profileRepository.getById(current.draft.id) else null
            // Never commit an active policy without the specifically selected proof.
            val validationProfile = latestPersisted ?: current.draft
            if (!policyStore.isReady(validationProfile)) {
                _uiState.update { it.copy(configurationError = "停止・一時休止で使用するNFC・QR・動作判定を登録して選択してね") }
                return@launch
            }
            _uiState.update { it.copy(configurationError = null) }
            // The app-block domain denylist is deliberately independent of
            // full-screen focus mode's URL allowlist.
            val blockedWebsiteHosts = current.blockedUrls.mapNotNull {
                FullScreenModeStore.normalizeUrl(it)?.removePrefix("https://")
            }.distinct()
            val toSave = current.draft.copy(
                name = trimmedName,
                // Independent unlock policies always start from the home button.
                // Legacy strategy data is retained for profiles that have not migrated.
                strategyId = if (com.foqos.android.ui.activitylab.ProfileUnlockPolicyStore(context)
                        .configured(current.draft.id)) ManualBlockingStrategy.ID
                    else current.draft.strategyId,
                schedule = null,
                enableBreaks = false,
                allowMultipleBreaks = false,
                allowModeEnabled = false,
                // Never write the focus-mode allowlist into the app-block denylist.
                domains = blockedWebsiteHosts,
                allowModeDomains = false,
                physicalUnblockItems = latestPersisted?.physicalUnblockItems
                    ?: current.draft.physicalUnblockItems,
                updatedAt = Instant.now(),
            )

            val saved = if (!current.isPersisted) profileRepository.create(toSave) else profileRepository.saveEditorDraft(toSave)
            fullScreenStore.save(saved.id, current.fullScreenEnabled, current.fullScreenAllowedPackages)
            fullScreenStore.saveUrls(saved.id, current.fullScreenAllowedUrls)
            periodicStore.save(saved.id, if (current.periodicAccessEnabled) {
                PeriodicAccessStore.Config(current.periodicWaitMinutes, current.periodicAccessMinutes)
            } else PeriodicAccessStore.Config())

            alarmScheduler.cancel(AlarmActivityType.SCHEDULE_START, saved.id)
            alarmScheduler.cancel(AlarmActivityType.SCHEDULE_END, saved.id)

            // If this profile owns the currently running session, re-apply its config so edits
            // (e.g. turning off website allow-mode) take effect immediately instead of only after
            // the next session start.
            sessionManager.refreshActiveProfileConfig(saved.id)

            _uiState.update { it.copy(draft = saved, isPersisted = true, saved = true) }
        }
    }

    fun delete() {
        if (isEditingProfileRunning()) return
        val id = _uiState.value.draft.id
        viewModelScope.launch {
            if (isEditingProfileRunning()) return@launch
            profileRepository.delete(id)
            val location = LocationAutomationStore(context)
            if (location.read(id) != null) {
                GeofenceManager(context).unregister(id)
                location.clear(id)
            }
            com.foqos.android.ui.activitylab.ProfileUnlockPolicyStore(context).delete(id)
            com.foqos.android.ui.activitylab.ActivityUnlockStore(context).deleteProfile(id)
            fullScreenStore.delete(id)
            periodicStore.delete(id)
            alarmScheduler.cancelAllForProfile(id)
            _uiState.update { it.copy(deleted = true) }
        }
    }

    /** All strategies in registry order - used by the strategy picker sheet, which groups them by mechanism. */
    fun pickerStrategies() = StrategyRegistry.all
}
