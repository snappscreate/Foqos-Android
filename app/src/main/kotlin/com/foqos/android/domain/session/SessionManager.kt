package com.foqos.android.domain.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.foqos.android.service.location.LocationAutomationStore
import com.foqos.android.service.session.FullScreenModeStore
import com.foqos.android.service.session.GlobalSessionProtectionStore
import com.foqos.android.service.session.PeriodicAccessStore
import com.foqos.android.service.session.PeriodicAccessSchedule
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.SoftUnblockGrant
import com.foqos.android.data.model.SoftUnblockSessionState
import com.foqos.android.data.model.SoftUnblockStrategyData
import com.foqos.android.data.model.StrategyPauseTimerData
import com.foqos.android.data.model.StrategyTimerData
import com.foqos.android.data.datastore.SettingsDataStore
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.data.repository.SessionRepository
import com.foqos.android.data.repository.SoftUnblockRepository
import com.foqos.android.di.IoDispatcher
import com.foqos.android.domain.strategy.ManualBlockingStrategy
import com.foqos.android.domain.strategy.SessionOutcome
import com.foqos.android.domain.strategy.StrategyHost
import com.foqos.android.domain.strategy.StrategyInput
import com.foqos.android.domain.strategy.StrategyRegistry
import com.foqos.android.domain.strategy.StrategyUiRequest
import com.foqos.android.service.alarm.AlarmActivityType
import com.foqos.android.service.alarm.FoqosAlarmScheduler
import com.foqos.android.service.session.BlockingState
import com.foqos.android.service.session.BlockingStateHolder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a [StrategyUiRequest] emitted by [SessionManager.uiRequests] resolves to once fed input. */
sealed interface PendingStrategyAction {
    data class Start(val profileId: String) : PendingStrategyAction
    data class Stop(val sessionId: String) : PendingStrategyAction
    data class LocationPause(val sessionId: String) : PendingStrategyAction
}

/**
 * The heart of session orchestration - Android port of iOS `StrategyManager`. Resolves which
 * [com.foqos.android.domain.strategy.BlockingStrategy] governs a profile, drives it through the
 * [StrategyHost] callbacks (this class implements [StrategyHost] itself), persists session state,
 * publishes [BlockingState] for the enforcement layer, schedules/cancels alarms, and reacts to
 * alarms firing back in via [onAlarm].
 *
 * All persistence goes through the injected repositories; nothing here touches Android
 * frameworks directly except through [FoqosAlarmScheduler], [SessionServiceController] and
 * [WidgetRefresher], all of which are thin seams kept swappable for testing.
 */
@Singleton
class SessionManager @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val sessionRepository: SessionRepository,
    private val softUnblockRepository: SoftUnblockRepository,
    private val blockingStateHolder: BlockingStateHolder,
    private val alarmScheduler: FoqosAlarmScheduler,
    private val settingsDataStore: SettingsDataStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val sessionServiceController: SessionServiceController,
    private val widgetRefresher: WidgetRefresher,
    @ApplicationContext private val appContext: Context,
) : StrategyHost {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val sessionMutex = Mutex()
    private class FullScreenSessionConflict(val runningProfileId: String) : IllegalStateException()

    private val fullScreenModeStore by lazy { FullScreenModeStore(appContext) }

    private suspend fun conflictingFullScreenProfile(profileId: String): BlockedProfile? {
        if (!fullScreenModeStore.enabled(profileId)) return null
        val runningId = _activeSessions.value
            .firstOrNull { it.profileId != profileId && fullScreenModeStore.enabled(it.profileId) }
            ?.profileId ?: return null
        return profileRepository.getById(runningId)
    }

    private fun reportFullScreenConflict(runningName: String) {
        emitError("全画面集中モード「$runningName」がすでに稼働中です。停止してから別の全画面集中モードを開始してください。")
    }


    private val _activeSession = MutableStateFlow<BlockedProfileSession?>(null)
    val activeSession: StateFlow<BlockedProfileSession?> = _activeSession.asStateFlow()

    private val _activeSessions = MutableStateFlow<List<BlockedProfileSession>>(emptyList())
    val activeSessions: StateFlow<List<BlockedProfileSession>> = _activeSessions.asStateFlow()

    private fun sessionForProfile(profileId: String): BlockedProfileSession? =
        _activeSessions.value.firstOrNull { it.profileId == profileId }

    private fun cacheSession(session: BlockedProfileSession) {
        _activeSessions.update { previous ->
            if (previous.any { it.id == session.id }) {
                previous.map { if (it.id == session.id) session else it }
            } else listOf(session) + previous.filterNot { it.profileId == session.profileId }
        }
        _activeSession.value = _activeSessions.value.firstOrNull()
    }

    private fun uncacheSession(sessionId: String) {
        _activeSessions.update { previous -> previous.filterNot { it.id == sessionId } }
        _activeSession.value = _activeSessions.value.firstOrNull()
    }

    private val _activeProfile = MutableStateFlow<BlockedProfile?>(null)
    val activeProfile: StateFlow<BlockedProfile?> = _activeProfile.asStateFlow()

    private val _uiRequests = MutableSharedFlow<Pair<StrategyUiRequest, PendingStrategyAction>>(extraBufferCapacity = 1)
    val uiRequests: SharedFlow<Pair<StrategyUiRequest, PendingStrategyAction>> = _uiRequests.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    init {
        scope.launch {
            // Best-effort warm-up of the active-session cache. Guarded so a transient read failure
            // (or, in tests, the backing store being torn down before this fire-and-forget job runs)
            // can't surface as an uncaught exception in this SupervisorJob scope.
            runCatching {
                sessionMutex.withLock {
                    _activeSessions.value = sessionRepository.activeSessionsOnce()
                    _activeSession.value = _activeSessions.value.firstOrNull()
                    _activeProfile.value = _activeSession.value?.let { profileRepository.getById(it.profileId) }
                }
            }
        }
    }

    private fun locationStoreOutside(profileId: String): Boolean {
        val store = LocationAutomationStore(appContext)
        return store.enabled(profileId) &&
            (store.manualInside(profileId) ?: store.lastInside(profileId)) == false
    }

    private val globalProtection by lazy { GlobalSessionProtectionStore(appContext) }

    suspend fun refreshGlobalProtectionSettings() {
        _activeSessions.value.forEach { refreshActiveProfileConfig(it.profileId) }
    }

    private val periodicAccessStore by lazy { PeriodicAccessStore(appContext) }

    private fun periodicAnchor(session: BlockedProfileSession): Long =
        maxOf(session.startTime.toEpochMilli(), session.pauseEndTime?.toEpochMilli() ?: 0L, session.breakEndTime?.toEpochMilli() ?: 0L)

    private fun activatedPeriodicAt(profileId: String, session: BlockedProfileSession): Long =
        periodicAccessStore.activatedAt(profileId, session.id, periodicAnchor(session))

    private fun periodicAccessActive(profileId: String, session: BlockedProfileSession, now: Instant): Boolean =
        PeriodicAccessSchedule.isActive(
            periodicAccessStore.read(profileId), periodicAnchor(session),
            activatedPeriodicAt(profileId, session), now.toEpochMilli(),
        )

    /** Starts only the currently available recurring break; it never bypasses NFC outside that window. */
    suspend fun activatePeriodicAccess(profileId: String): Boolean = withContext(ioDispatcher) {
        val session = sessionForProfile(profileId) ?: return@withContext false
        if (session.isPauseActive() ||
            com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext)
                .selection(profileId, session.id) != null
        ) return@withContext false
        val now = Instant.now()
        val config = periodicAccessStore.read(profileId)
        val anchor = periodicAnchor(session)
        if (!PeriodicAccessSchedule.isAvailable(config, anchor, activatedPeriodicAt(profileId, session), now.toEpochMilli())) return@withContext false
        periodicAccessStore.activate(profileId, session.id, anchor, now.toEpochMilli())
        blockingStateHolder.updateProfile(profileId) {
            it.copy(periodicActivatedAtEpochMillis = now.toEpochMilli(), periodicAccessActive = true)
        }
        schedulePeriodicAccess(profileId, session, now)
        true
    }

    private fun schedulePeriodicAccess(
        profileId: String, session: BlockedProfileSession, now: Instant = Instant.now(),
    ) {
        val config = periodicAccessStore.read(profileId)
        val extras = mapOf("sessionId" to session.id)
        alarmScheduler.cancel(AlarmActivityType.PERIODIC_ACCESS_BOUNDARY, profileId, extras)
        PeriodicAccessSchedule.nextBoundary(config, periodicAnchor(session), activatedPeriodicAt(profileId, session), now.toEpochMilli())?.let {
            alarmScheduler.scheduleExactAt(
                AlarmActivityType.PERIODIC_ACCESS_BOUNDARY, profileId,
                Instant.ofEpochMilli(it), extras,
            )
        }
    }

    private suspend fun handlePeriodicAccessBoundary(
        profileId: String, extras: Map<String, String>, at: Instant,
    ) {
        val session = sessionForProfile(profileId) ?: return
        if (session.profileId != profileId || session.id != extras["sessionId"]) return
        blockingStateHolder.updateProfile(profileId) { it.copy(periodicAccessActive = periodicAccessActive(profileId, session, at)) }
        schedulePeriodicAccess(profileId, session, at)
    }

    // --- Public API ------------------------------------------------------------------------

    /**
     * Location events only manage sessions that were created by the location automation.
     * Never replace or stop a session started manually, by NFC, or by a schedule.
     */
    suspend fun onLocationTransition(profileId: String, inside: Boolean) = withContext(ioDispatcher) {
        if (!LocationAutomationStore(appContext).enabled(profileId)) return@withContext
        val current = sessionForProfile(profileId) ?: sessionRepository.activeSessionForProfile(profileId)
        if (inside) {
            if (LocationAutomationStore(appContext).manuallyStopped(profileId)) {
                // EXIT/ENTER only resumes a location-suspended session; never undo a full stop.
                return@withContext
            }
            if (current != null) {
                // Restore blocking in the same location-owned session; NFC timed pauses
                // remain independent and keep their remaining duration.
                if (current.profileId == profileId) {
                    blockingStateHolder.updateProfile(profileId) { it.copy(locationSuspended = false) }
                }
                return@withContext
            }
            // A location transition can only resume an existing session.
            // A fully stopped (no active session) profile requires the explicit Play action.
            return@withContext
        } else {
            // Leaving the geofence is the deliberate escape hatch for a chosen activity.
            com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext).clear(profileId)
            com.foqos.android.ui.activitylab.ActivityChallengeSetStore(appContext).clearProgress(profileId)
            if (current?.profileId == profileId) {
                // Do not end the session on EXIT. Keep history, NFC registrations and
                // timed pause state intact, only suspend enforcement until ENTER.
                blockingStateHolder.updateProfile(profileId) { it.copy(locationSuspended = true) }
            }
        }
    }

    /**
     * Home action for an active location-owned session. Inside the configured area this
     * starts a timed pause without destroying the session or its NFC registrations.
     * Outside the area the location suspension is independent of timed NFC pauses.
     */
    suspend fun pauseLocationSessionFromHome(profileId: String) = withContext(ioDispatcher) {
        if (independentUnlock.configured(profileId)) {
            requestConfiguredPause(profileId)
            return@withContext
        }
        val current = sessionForProfile(profileId) ?: return@withContext
        if (current.profileId != profileId ||
            current.isPauseActive() || !locationStoreInside(profileId)
        ) return@withContext
        val profile = profileRepository.getById(profileId) ?: return@withContext
        if (com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext)
                .selection(profileId, current.id) != null) return@withContext
        val strategy = StrategyRegistry.byId(profile.strategyId)
        if (strategy.usesNfc || strategy.usesQr || profile.physicalUnblockItems.isNotEmpty()) {
            emitError("一時休止には登録されたNFCまたはQRの認証が必要です")
            return@withContext
        }
        pauseLocationAfterAuthorization(current, profile)
    }

    private suspend fun pauseLocationAfterAuthorization(
        current: BlockedProfileSession,
        profile: BlockedProfile,
    ) {
        val minutes = if (profile.strategyId ==
            com.foqos.android.domain.strategy.NfcPauseTimerBlockingStrategy.ID
        ) {
            StrategyPauseTimerData.fromJsonOrNull(profile.strategyDataJson).pauseDurationInMinutes
        } else {
            profile.breakTimeInMinutes.coerceAtLeast(1)
        }
        beginPause(current, minutes)
    }

    private val independentUnlock by lazy {
        com.foqos.android.ui.activitylab.ProfileUnlockPolicyStore(appContext)
    }

    /** One authoritative retry guard for manual, NFC, QR and verified AI paths. */
    fun pauseRetryRemainingMillis(profileId: String): Long =
        independentUnlock.pauseRetryRemainingMillis(profileId)

    private fun pauseRetryAllowed(profileId: String): Boolean {
        val remaining = pauseRetryRemainingMillis(profileId)
        if (remaining == 0L) return true
        val minutes = (remaining + 59_999L) / 60_000L
        emitError("一時休止の再挑戦まであと${minutes / 60}時間${minutes % 60}分")
        return false
    }

    /** Start a timed pause under the dedicated, independent main condition. */
    suspend fun requestConfiguredPause(profileId: String): Unit = withContext(ioDispatcher) {
        val current = sessionForProfile(profileId) ?: return@withContext
        if (current.isPauseActive() || locationStoreOutside(profileId)) return@withContext
        if (!pauseRetryAllowed(profileId)) return@withContext
        val profile = profileRepository.getById(profileId) ?: return@withContext
        if (!independentUnlock.configured(profileId)) {
            requestLocationPauseFromHome(profileId)
            return@withContext
        }
        val activity = com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext)
        if (activity.selection(profileId, current.id) != null ||
            com.foqos.android.ui.activitylab.ActivityChallengeSetStore(appContext)
                .progress(profileId, current.id) != null) {
            emitError("先に選択した動作チャレンジを完了してね")
            return@withContext
        }
        val policy = independentUnlock.read(profileId)
        when (policy.pause) {
            com.foqos.android.ui.activitylab.UnlockMethod.MANUAL ->
                pauseForConfiguredDuration(current, policy.pauseMinutes)
            com.foqos.android.ui.activitylab.UnlockMethod.NFC ->
                _uiRequests.emit(StrategyUiRequest.ScanNfc(StrategyUiRequest.Purpose.STOP) to
                    PendingStrategyAction.LocationPause(current.id))
            com.foqos.android.ui.activitylab.UnlockMethod.QR ->
                _uiRequests.emit(StrategyUiRequest.ScanQr(StrategyUiRequest.Purpose.STOP) to
                    PendingStrategyAction.LocationPause(current.id))
            com.foqos.android.ui.activitylab.UnlockMethod.ACTIVITY ->
                emitError("ブロック画面で設定済みの動作判定を完了してね")
        }
    }

    private suspend fun pauseForConfiguredDuration(
        session: BlockedProfileSession, minutes: Int,
    ) {
        if (!pauseRetryAllowed(session.profileId)) return
        val activity = com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext)
        activity.savePauseMinutes(session.id, minutes)
        beginPause(session, minutes)
        activity.clear(session.profileId)
    }

    /** Validated NFC/QR proof is used by exactly one chosen action: stop OR pause. */
    suspend fun authorizeConfiguredCode(
        profileId: String, code: String, type: PhysicalUnblockType,
        forPause: Boolean,
    ): Boolean = withContext(ioDispatcher) {
        if (!independentUnlock.configured(profileId)) return@withContext false
        val current = sessionForProfile(profileId) ?: return@withContext false
        val profile = profileRepository.getById(profileId) ?: return@withContext false
        val policy = independentUnlock.read(profileId)
        val requested = if (forPause) policy.pause else policy.stop
        val expected = when (type) {
            PhysicalUnblockType.NFC -> com.foqos.android.ui.activitylab.UnlockMethod.NFC
            PhysicalUnblockType.QR_CODE -> com.foqos.android.ui.activitylab.UnlockMethod.QR
        }
        if (requested != expected || locationStoreOutside(profileId)) return@withContext false
        val chosenId = if (forPause) policy.pauseCredentialId else policy.stopCredentialId
        val chosen = profile.physicalUnblockItems.firstOrNull { it.id == chosenId && it.type == type }
            ?: return@withContext false
        if (com.foqos.android.data.model.PhysicalUnblockItem.normalizeCodeValue(chosen.codeValue) !=
            com.foqos.android.data.model.PhysicalUnblockItem.normalizeCodeValue(code)) return@withContext false
        if (forPause) {
            if (current.isPauseActive() || !pauseRetryAllowed(profileId)) return@withContext false
            val activity = com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext)
            if (activity.selection(profileId, current.id) != null ||
                com.foqos.android.ui.activitylab.ActivityChallengeSetStore(appContext)
                    .progress(profileId, current.id) != null) return@withContext false
            pauseForConfiguredDuration(current, policy.pauseMinutes)
        } else {
            markCompletelyStopped(profileId)
            endSession(current, Instant.now())
        }
        true
    }

    /** Request physical proof before a location-profile pause. */
    suspend fun requestLocationPauseFromHome(profileId: String): Unit = withContext(ioDispatcher) {
        val session = sessionForProfile(profileId) ?: return@withContext
        if (session.profileId != profileId || session.isPauseActive()) return@withContext
        if (independentUnlock.configured(profileId)) {
            requestConfiguredPause(profileId)
            return@withContext
        }
        if (!locationStoreInside(profileId)) return@withContext
        val profile = profileRepository.getById(profileId) ?: return@withContext
        if (com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext)
                .selection(profileId, session.id) != null) {
            emitError("動作チャレンジ選択中です。NFCに戻すことはできません")
            return@withContext
        }
        val strategy = StrategyRegistry.byId(profile.strategyId)
        val physical = profile.physicalUnblockItems
        when {
            strategy.usesNfc || physical.any { it.type == PhysicalUnblockType.NFC } ->
                _uiRequests.emit(StrategyUiRequest.ScanNfc(StrategyUiRequest.Purpose.STOP) to
                    PendingStrategyAction.LocationPause(session.id))
            strategy.usesQr || physical.any { it.type == PhysicalUnblockType.QR_CODE } ->
                _uiRequests.emit(StrategyUiRequest.ScanQr(StrategyUiRequest.Purpose.STOP) to
                    PendingStrategyAction.LocationPause(session.id))
            else -> pauseLocationAfterAuthorization(session, profile)
        }
    }

    /** Verify scanned credentials without invoking a strategy's stop/end-session action. */
    suspend fun pauseLocationWithVerifiedCode(
        profileId: String, code: String, type: PhysicalUnblockType,
    ): Boolean = withContext(ioDispatcher) {
        if (independentUnlock.configured(profileId)) {
            return@withContext authorizeConfiguredCode(profileId, code, type, forPause = true)
        }
        val current = sessionForProfile(profileId) ?: return@withContext false
        if (current.profileId != profileId || current.isPauseActive() ||
            !locationStoreInside(profileId)) return@withContext false
        val profile = profileRepository.getById(profileId) ?: return@withContext false
        if (com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext)
                .selection(profileId, current.id) != null) return@withContext false
        val strategy = StrategyRegistry.byId(profile.strategyId)
        if (type == PhysicalUnblockType.NFC && !strategy.usesNfc &&
            profile.physicalUnblockItems.none { it.type == type }) return@withContext false
        if (type == PhysicalUnblockType.QR_CODE && !strategy.usesQr &&
            profile.physicalUnblockItems.none { it.type == type }) return@withContext false
        val registered = profile.physicalUnblockItems.filter { it.type == type }
        val valid = registered.isNotEmpty() && profile.canUnblock(code, type)
        if (!valid) {
            emitError("登録されていないNFC・QRコードです")
            return@withContext false
        }
        pauseLocationAfterAuthorization(current, profile)
        true
    }

    /**
     * Apply a successful, verified video challenge to the exact running session.
     * The screen cannot alter its proof selection by passing arbitrary activity text.
     */
    suspend fun verifyActivityStep(
        profileId: String, sessionId: String, conditionId: String,
    ): com.foqos.android.ui.activitylab.ActivityProofResult = withContext(ioDispatcher) {
        val rejected = com.foqos.android.ui.activitylab.ActivityProofResult.REJECTED
        val current = sessionForProfile(profileId) ?: return@withContext rejected
        if (current.id != sessionId || current.isPauseActive() ||
            !pauseRetryAllowed(profileId)) return@withContext rejected
        val store = com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext)
        val condition = store.conditions(profileId).firstOrNull { it.id == conditionId }
            ?: return@withContext rejected
        if (condition.description.isBlank()) return@withContext rejected
        val location = com.foqos.android.service.location.LocationAutomationStore(appContext)
        if (location.enabled(profileId) &&
            (location.manualInside(profileId) ?: location.lastInside(profileId)) != true
        ) return@withContext rejected

        val sets = com.foqos.android.ui.activitylab.ActivityChallengeSetStore(appContext)
        val available = (
            if (independentUnlock.read(profileId).pause ==
                com.foqos.android.ui.activitylab.UnlockMethod.ACTIVITY)
                sets.sets(profileId) else emptyList()
            ) + sets.subSets(profileId)
        val duration: Int
        if (available.isNotEmpty()) {
            val progress = sets.progress(profileId, sessionId) ?: return@withContext rejected
            val selectedSet = available.firstOrNull { it.id == progress.setId }
                ?: return@withContext rejected
            if (conditionId !in sets.nextConditions(profileId, sessionId))
                return@withContext rejected
            val result = sets.complete(profileId, sessionId, conditionId)
            if (result != com.foqos.android.ui.activitylab.ActivityProofResult.PAUSED)
                return@withContext result
            duration = selectedSet.pauseMinutes
        } else {
            // Once the profile uses set-based config, no obsolete single-movement
            // reward can authorize a pause, even if an old main ID remains stored.
            if (sets.hasMainSetConfig(profileId) || sets.hasSubSetConfig(profileId))
                return@withContext rejected
            if (store.selection(profileId, sessionId)?.conditionId != conditionId)
                return@withContext rejected
            val configuredDuration = independentUnlock.read(profileId).pauseMinutes
            duration = if (store.mainConditionId(profileId) == conditionId) configuredDuration
                else store.conditionPauseMinutes(profileId, conditionId, configuredDuration)
        }

        store.savePauseMinutes(current.id, duration)
        beginPause(current, duration)
        store.clear(profileId)
        sets.clearProgress(profileId)
        com.foqos.android.ui.activitylab.ActivityProofResult.PAUSED
    }

    /** Compatibility for callers that only care whether the whole pause succeeded. */
    suspend fun pauseWithVerifiedActivity(
        profileId: String, sessionId: String, conditionId: String,
    ): Boolean = verifyActivityStep(profileId, sessionId, conditionId) ==
        com.foqos.android.ui.activitylab.ActivityProofResult.PAUSED

    private fun locationStoreInside(profileId: String): Boolean {
        val store = LocationAutomationStore(appContext)
        return store.enabled(profileId) &&
            (store.manualInside(profileId) ?: store.lastInside(profileId)) == true
    }

    private fun markCompletelyStopped(profileId: String) {
        val store = LocationAutomationStore(appContext)
        if (store.read(profileId) != null) store.setManuallyStopped(profileId, true)
    }

    /** Explicit home Stop action for a location-configured profile while outside its area. */
    suspend fun stopLocationProfileFromHome(profileId: String) = withContext(ioDispatcher) {
        val current = sessionForProfile(profileId) ?: return@withContext
        // Leaving the geographic boundary is the exception to locked activity proof.
        if (locationStoreInside(profileId)) {
            emitError("位置範囲内では解除条件が必要です")
            return@withContext
        }
        if (current.profileId == profileId) {
            markCompletelyStopped(profileId)
            endSession(current, Instant.now())
        }
    }

    /** NFC profile tags pause NFC + Pause Timer sessions instead of acting as forced toggles. */
    suspend fun onProfileNfcScan(profileId: String) {
        if (independentUnlock.configured(profileId)) {
            // New profiles start from home, never from an unsolicited NFC tap.
            val current = sessionForProfile(profileId) ?: return
            val policy = independentUnlock.read(profileId)
            val forPause = policy.pause == com.foqos.android.ui.activitylab.UnlockMethod.NFC
            authorizeConfiguredCode(profileId, "foqos://profile/$profileId",
                PhysicalUnblockType.NFC, forPause = forPause)
            return
        }
        val current = sessionForProfile(profileId) ?: sessionRepository.activeSessionForProfile(profileId)
        if (current?.profileId == profileId) {
            if (com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext)
                    .selection(profileId, current.id) != null) {
                emitError("動作チャレンジが解除条件になっています")
                return
            }
            val profile = profileRepository.getById(profileId) ?: return
            if (profile.strategyId ==
                com.foqos.android.domain.strategy.NfcPauseTimerBlockingStrategy.ID
            ) {
                StrategyRegistry.byId(profile.strategyId).onStopInput(
                    profile, current,
                    StrategyInput.Code(
                        "foqos://profile/$profileId",
                        StrategyInput.CodeSource.NFC,
                    ),
                    this,
                )
                return
            }
        }
        toggleFromDeeplink(profileId, fromBackground = false)
    }

    /** Resolves [profileId]'s configured strategy and either starts it directly or requests UI. */
    suspend fun startProfile(profileId: String) {
        val profile = profileRepository.getById(profileId) ?: run {
            emitError("Failed to find a profile stored locally that matches the tag")
            return
        }
        conflictingFullScreenProfile(profileId)?.let {
            reportFullScreenConflict(it.name)
            return
        }
        // Only a successful explicit start re-arms a completely stopped profile.
        if (independentUnlock.configured(profileId)) {
            if (!independentUnlock.isReady(profile)) {
                emitError("ブロック開始不可：停止・一時休止条件の登録が必要だよ")
                return
            }
            createSession(profile, tag = ManualBlockingStrategy.ID, forceStarted = false)
            LocationAutomationStore(appContext).setManuallyStopped(profileId, false)
            return
        }
        val strategy = StrategyRegistry.byId(profile.strategyId)
        val uiRequest = strategy.startRequest(profile)
        if (uiRequest == StrategyUiRequest.None) {
            try {
                strategy.onStartInput(profile, StrategyInput.NoInput, this)
                LocationAutomationStore(appContext).setManuallyStopped(profileId, false)
            } catch (conflict: FullScreenSessionConflict) {
                reportFullScreenConflict(profileRepository.getById(conflict.runningProfileId)?.name ?: "別のプロファイル")
            }
        } else {
            _uiRequests.tryEmit(uiRequest to PendingStrategyAction.Start(profileId))
        }
    }

    /** Feeds [input] (the result of a previously-emitted [uiRequests] entry) back into a strategy. */
    suspend fun deliverInput(action: PendingStrategyAction, input: StrategyInput) {
        when (action) {
            is PendingStrategyAction.Start -> {
                val profile = profileRepository.getById(action.profileId) ?: run {
                    emitError("Failed to find a profile stored locally that matches the tag")
                    return
                }
                conflictingFullScreenProfile(profile.id)?.let {
                    reportFullScreenConflict(it.name)
                    return
                }
                val strategy = StrategyRegistry.byId(profile.strategyId)
                try {
                    strategy.onStartInput(profile, input, this)
                    LocationAutomationStore(appContext).setManuallyStopped(profile.id, false)
                } catch (conflict: FullScreenSessionConflict) {
                    reportFullScreenConflict(profileRepository.getById(conflict.runningProfileId)?.name ?: "別のプロファイル")
                }
            }
            is PendingStrategyAction.LocationPause -> {
                val current = _activeSessions.value.firstOrNull { it.id == action.sessionId } ?: return
                val scanned = input as? StrategyInput.Code ?: return
                val type = when (scanned.source) {
                    StrategyInput.CodeSource.NFC -> PhysicalUnblockType.NFC
                    StrategyInput.CodeSource.QR -> PhysicalUnblockType.QR_CODE
                }
                pauseLocationWithVerifiedCode(current.profileId, scanned.value, type)
            }
            is PendingStrategyAction.Stop -> {
                val session = sessionRepository.getById(action.sessionId) ?: run {
                    emitError("Session is no longer active")
                    return
                }
                val profile = profileRepository.getById(session.profileId) ?: run {
                    emitError("Failed to find a profile stored locally that matches the tag")
                    return
                }
                if (independentUnlock.configured(profile.id)) {
                    // A delayed QR/NFC result from an old session must not act on
                    // a different current session of the same profile.
                    if (sessionForProfile(profile.id)?.id != action.sessionId) return
                    val scanned = input as? StrategyInput.Code ?: return
                    authorizeConfiguredCode(profile.id, scanned.value,
                        if (scanned.source == StrategyInput.CodeSource.NFC) PhysicalUnblockType.NFC
                        else PhysicalUnblockType.QR_CODE,
                        forPause = false)
                    return
                }
                if (com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext)
                        .selection(profile.id, session.id) != null) {
                    emitError("動作チャレンジが解除条件になっています")
                    return
                }
                val strategy = StrategyRegistry.byId(profile.strategyId)
                strategy.onStopInput(profile, session, input, this)
            }
        }
    }

    /**
     * Requests that the active session be stopped through its configured strategy. When
     * [fromBackground] is true and the profile has `disableBackgroundStops` enabled, this refuses
     * and emits an error instead - mirrors iOS `stopSessionFromBackground`.
     */
    suspend fun stopActiveSession(fromBackground: Boolean) {
        val profileId = _activeSession.value?.profileId ?: return
        stopProfileSession(profileId, fromBackground)
    }

    suspend fun stopProfileSession(profileId: String, fromBackground: Boolean = false) {
        val session = sessionForProfile(profileId) ?: run {
            emitError("No active session to stop")
            return
        }
        val profile = profileRepository.getById(session.profileId) ?: run {
            emitError("Failed to find a profile stored locally that matches the tag")
            return
        }
        if (independentUnlock.configured(profileId)) {
            if (locationStoreOutside(profileId)) {
                markCompletelyStopped(profileId)
                endSession(session, Instant.now())
                return
            }
            val stopMethod = independentUnlock.read(profileId).stop
            when (stopMethod) {
                com.foqos.android.ui.activitylab.UnlockMethod.MANUAL -> {
                    if (locationStoreInside(profileId)) {
                        emitError("ブロック範囲内では停止できません")
                    } else if (!fromBackground || !globalProtection.readFor(profile).disableBackgroundStops) {
                        markCompletelyStopped(profileId)
                        endSession(session, Instant.now())
                    }
                }
                com.foqos.android.ui.activitylab.UnlockMethod.NFC ->
                    _uiRequests.emit(StrategyUiRequest.ScanNfc(StrategyUiRequest.Purpose.STOP) to PendingStrategyAction.Stop(session.id))
                com.foqos.android.ui.activitylab.UnlockMethod.QR ->
                    _uiRequests.emit(StrategyUiRequest.ScanQr(StrategyUiRequest.Purpose.STOP) to PendingStrategyAction.Stop(session.id))
                else -> Unit
            }
            return
        }
        if (com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext)
                .selection(profileId, session.id) != null) {
            emitError("動作チャレンジが解除条件になっています")
            return
        }
        if (fromBackground && globalProtection.readFor(profile).disableBackgroundStops) {
            emitError("This profile requires the app to stop the session")
            return
        }

        val strategy = StrategyRegistry.byId(profile.strategyId)
        val uiRequest = strategy.stopRequest(profile, session)
        if (uiRequest == StrategyUiRequest.None) {
            strategy.onStopInput(profile, session, StrategyInput.NoInput, this)
        } else {
            _uiRequests.tryEmit(uiRequest to PendingStrategyAction.Stop(session.id))
        }
    }

    /**
     * Toggles blocking for [profileId] from outside the app's UI (deep link / shortcut), bypassing
     * the profile's configured strategy entirely via a forced Manual start/stop - mirrors iOS
     * `toggleSessionFromDeeplink`:
     *  - If [profileId] is already the active session: force-stop it (unless
     *    `disableBackgroundStops` blocks a background stop).
     *  - If a *different* profile is active: force-stop it, then force-start [profileId].
     *  - If nothing is active: force-start [profileId].
     */
    suspend fun toggleFromDeeplink(profileId: String, fromBackground: Boolean = true) {
        val current = sessionForProfile(profileId)
        if (current == null) {
            val target = profileRepository.getById(profileId) ?: run {
                emitError("Failed to find a profile stored locally that matches the tag")
                return
            }
            if (independentUnlock.configured(profileId)) {
                startProfile(profileId)
                return
            }
            conflictingFullScreenProfile(profileId)?.let {
                reportFullScreenConflict(it.name)
                return
            }
            try {
                createSession(target, tag = ManualBlockingStrategy.ID, forceStarted = true)
            } catch (conflict: FullScreenSessionConflict) {
                reportFullScreenConflict(profileRepository.getById(conflict.runningProfileId)?.name ?: "別のプロファイル")
            }
            return
        }
        val currentProfile = profileRepository.getById(profileId)
        if (independentUnlock.configured(profileId)) {
            stopProfileSession(profileId, fromBackground)
            return
        }
        if (com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext)
                .selection(profileId, current.id) != null) {
            emitError("動作チャレンジが解除条件になっています")
            return
        }
        if (currentProfile?.strategyId ==
            com.foqos.android.domain.strategy.NfcPauseTimerBlockingStrategy.ID) {
            emitError("NFC + Pause Timer cannot be stopped through a shortcut")
            return
        }
        if (fromBackground && currentProfile?.let { globalProtection.readFor(it).disableBackgroundStops } == true) {
            emitError("This profile requires the app to stop the session")
            return
        }
        endSession(current, Instant.now())
    }

    /**
     * Starts a timed break on the active session, if the profile/strategy allow it and allowance
     * remains. Mirrors iOS `StrategyManager.startBreak`.
     */
    suspend fun startBreak(profileId: String? = _activeSession.value?.profileId) {
        val session = profileId?.let(::sessionForProfile) ?: run {
            emitError("Breaks are only available in an active session")
            return
        }
        val profile = profileRepository.getById(session.profileId) ?: run {
            emitError("Failed to find a profile stored locally that matches the tag")
            return
        }
        val strategy = StrategyRegistry.byId(profile.strategyId)

        if (!profile.enableBreaks) {
            emitError("Breaks are not enabled for this profile")
            return
        }
        if (!strategy.allowsTimedBreaks) {
            emitError("This strategy does not support breaks")
            return
        }

        val totalAllowanceSeconds = profile.breakTimeInMinutes * 60L
        val remainingAllowance = if (profile.allowMultipleBreaks) {
            maxOf(0L, totalAllowanceSeconds - session.usedBreakDurationInSeconds)
        } else {
            totalAllowanceSeconds
        }
        val breakAvailable = if (profile.allowMultipleBreaks) {
            remainingAllowance > 0
        } else {
            session.breakStartTime == null
        }
        if (!breakAvailable) {
            emitError("No break allowance remaining")
            return
        }

        val now = Instant.now()
        sessionRepository.startBreak(session.id, profile.allowMultipleBreaks, now)
        blockingStateHolder.updateProfile(session.profileId) { it.copy(breakActive = true) }

        val durationSeconds = minOf(totalAllowanceSeconds, remainingAllowance)
        alarmScheduler.scheduleExactAt(
            AlarmActivityType.BREAK_END,
            profile.id,
            now.plusSeconds(durationSeconds),
            mapOf("sessionId" to session.id),
        )

        cacheSession(session.copy(breakStartTime = now, breakEndTime = null))
    }

    /** Ends the active session's break at [at], accumulating used break duration. */
    suspend fun endBreak(at: Instant = Instant.now(), profileId: String? = _activeSession.value?.profileId) {
        val session = profileId?.let(::sessionForProfile) ?: return
        val profile = profileRepository.getById(session.profileId) ?: return

        alarmScheduler.cancel(AlarmActivityType.BREAK_END, profile.id, mapOf("sessionId" to session.id))
        sessionRepository.endBreak(session.id, profile.allowMultipleBreaks, profile.breakTimeInMinutes * 60L, at)
        blockingStateHolder.updateProfile(session.profileId) { it.copy(breakActive = false) }

        val breakStart = session.breakStartTime
        val elapsedSeconds = if (breakStart != null) maxOf(0L, at.toEpochMilli() - breakStart.toEpochMilli()) / 1000L else 0L
        val newUsedDuration = if (profile.allowMultipleBreaks) {
            minOf(profile.breakTimeInMinutes * 60L, session.usedBreakDurationInSeconds + elapsedSeconds)
        } else {
            elapsedSeconds
        }

        val resumed = session.copy(breakEndTime = at, usedBreakDurationInSeconds = newUsedDuration)
        cacheSession(resumed)
        // A manual break ends with a fresh uninterrupted-blocking countdown.
        // Unlike NFC pauses this timestamp is already in the session record.
        blockingStateHolder.updateProfile(profile.id) {
            it.copy(periodicAnchorEpochMillis = at.toEpochMilli(), periodicActivatedAtEpochMillis = 0L,
                periodicAccessActive = false)
        }
        schedulePeriodicAccess(profile.id, resumed, at)
    }

    /**
     * Force-stops the active session via a global "break-glass" emergency counter (default 3,
     * refilled every `emergencyResetIntervalWeeks`), if the profile allows it and allowance
     * remains. Mirrors iOS `StrategyManager.emergencyUnblock`.
     */
    suspend fun emergencyUnblock(profileId: String? = _activeSession.value?.profileId) {
        val session = profileId?.let(::sessionForProfile) ?: run {
            emitError("No active session to unblock")
            return
        }
        val profile = profileRepository.getById(session.profileId) ?: run {
            emitError("Failed to find a profile stored locally that matches the tag")
            return
        }
        if (!globalProtection.readFor(profile).enableEmergencyUnblock) {
            emitError("Emergency unblock is disabled for this profile")
            return
        }

        val now = Instant.now()
        maybeResetEmergencyAllowance(now)

        val remaining = settingsDataStore.emergencyUnblocksRemaining.first()
        if (remaining <= 0) {
            emitError("No emergency unblocks remaining")
            return
        }

        endSession(session, now)
        settingsDataStore.setEmergencyUnblocksRemaining(remaining - 1)
    }

    /** Resets the emergency-unblock counter to its default if the reset interval has elapsed. */
    private suspend fun maybeResetEmergencyAllowance(now: Instant) {
        val lastReset = settingsDataStore.emergencyLastResetEpochMillis.first()
        if (lastReset == 0L) {
            settingsDataStore.setEmergencyLastResetEpochMillis(now.toEpochMilli())
            scheduleNextEmergencyAllowanceReset(now)
            return
        }

        val weeks = settingsDataStore.emergencyResetIntervalWeeks.first()
        val intervalMillis = weeks * DAYS_PER_WEEK * MILLIS_PER_DAY
        if (now.toEpochMilli() - lastReset >= intervalMillis) {
            settingsDataStore.setEmergencyUnblocksRemaining(SettingsDataStore.DEFAULT_EMERGENCY_UNBLOCKS_REMAINING)
            settingsDataStore.setEmergencyLastResetEpochMillis(now.toEpochMilli())
            scheduleNextEmergencyAllowanceReset(now)
        }
    }

    private suspend fun scheduleNextEmergencyAllowanceReset(from: Instant) {
        val weeks = settingsDataStore.emergencyResetIntervalWeeks.first()
        val intervalMillis = weeks * DAYS_PER_WEEK * MILLIS_PER_DAY
        alarmScheduler.scheduleExactAt(
            AlarmActivityType.EMERGENCY_ALLOWANCE_RESET,
            EMERGENCY_ALLOWANCE_KEY,
            from.plusMillis(intervalMillis),
        )
    }

    /**
     * Issues a soft-unblock grant for [packageName] against the active session's allowance, if any
     * remains. Used by the block overlay (Phase 5) to let a package open temporarily.
     */
    suspend fun issueSoftUnblockGrant(packageName: String, profileId: String? = _activeSession.value?.profileId): SoftUnblockGrant? {
        val session = profileId?.let(::sessionForProfile) ?: return null
        val profile = profileRepository.getById(session.profileId) ?: return null
        val data = SoftUnblockStrategyData.fromJsonOrNull(profile.strategyDataJson)

        val grant = softUnblockRepository.issueGrant(
            sessionId = session.id,
            profileId = profile.id,
            packageName = packageName,
            accessDurationInMinutes = data.accessDurationInMinutes,
        )
        if (grant == null) {
            emitError("No temporary access remaining")
            return null
        }

        blockingStateHolder.updateProfile(profile.id) {
            it.copy(activeGrants = it.activeGrants + (packageName to grant.expiresAt.toEpochMilli()))
        }
        alarmScheduler.scheduleExactAt(
            AlarmActivityType.GRANT_EXPIRY,
            profile.id,
            grant.expiresAt,
            mapOf("sessionId" to session.id, "grantId" to grant.id, "packageName" to packageName),
        )
        return grant
    }

    /**
     * Reacts to one of [FoqosAlarmScheduler]'s alarms firing, called by
     * [com.foqos.android.receiver.AlarmReceiver]. See each `handle*` function for the exact
     * semantics of every [AlarmActivityType].
     */
    suspend fun onAlarm(type: AlarmActivityType, profileId: String, extras: Map<String, String>, at: Instant) {
        when (type) {
            AlarmActivityType.SCHEDULE_START -> handleScheduleStart(profileId, at)
            AlarmActivityType.SCHEDULE_END -> handleScheduleEnd(profileId, at)
            AlarmActivityType.STRATEGY_TIMER_END -> handleStrategyTimerEnd(profileId, extras, at)
            AlarmActivityType.BREAK_END -> handleBreakEnd(profileId, extras, at)
            AlarmActivityType.PAUSE_END -> handlePauseEnd(profileId, extras, at)
            AlarmActivityType.PERIODIC_ACCESS_BOUNDARY -> handlePeriodicAccessBoundary(profileId, extras, at)
            AlarmActivityType.GRANT_EXPIRY -> handleGrantExpiry(profileId, extras, at)
            AlarmActivityType.EMERGENCY_ALLOWANCE_RESET -> handleEmergencyAllowanceReset(at)
            AlarmActivityType.REMINDER -> Unit // The receiver posts the reminder notification itself; nothing to update here.
        }
    }

    /**
     * Panic-button reset for the Settings screen: force-ends any active session (bypassing its
     * configured strategy entirely, unlike [stopActiveSession]), clears [BlockingStateHolder], and
     * cancels every alarm tracked for every stored profile plus the global emergency-allowance
     * reset alarm. Used when a user's blocking state has gotten stuck and they just want a clean
     * slate.
     */
    suspend fun resetAllState() {
        sessionMutex.withLock {
        val sessions = sessionRepository.activeSessionsOnce()
        sessions.forEach { session ->
            sessionRepository.endSession(session.id, Instant.now())
        }
        sessions.firstOrNull()?.let {
            sessionServiceController.stopForegroundService(it.id)
        }
        blockingStateHolder.clear()
        _activeSessions.value = emptyList()
        _activeSession.value = null
        _activeProfile.value = null

        val allProfiles = profileRepository.observeAll().first()
        val location = LocationAutomationStore(appContext)
        allProfiles.forEach { profile ->
            // An emergency stop must not be undone by the next geofence transition.
            if (location.read(profile.id) != null) location.setManuallyStopped(profile.id, true)
            alarmScheduler.cancelAllForProfile(profile.id)
        }
        alarmScheduler.cancel(AlarmActivityType.EMERGENCY_ALLOWANCE_RESET, EMERGENCY_ALLOWANCE_KEY)
        widgetRefresher.refresh()
        }
    }

    /**
     * Rebuilds [activeSession]/[activeProfile]/[BlockingStateHolder] from persisted state after a
     * process restart (app cold start, or [com.foqos.android.receiver.BootReceiver] after a
     * device reboot), and restarts the foreground service if a session is still active. Grants
     * still valid at "now" are re-populated in [BlockingStateHolder]; break/pause flags are
     * derived from the session's own timestamps.
     */
    suspend fun rebuildStateAfterProcessRestart() {
        val sessions = sessionRepository.activeSessionsOnce()
        _activeSessions.value = sessions
        _activeSession.value = sessions.firstOrNull()
        _activeProfile.value = _activeSession.value?.let { profileRepository.getById(it.profileId) }
        if (sessions.isEmpty()) {
            blockingStateHolder.clear()
            return
        }
        blockingStateHolder.clear()
        val now = Instant.now()
        sessions.forEach { session ->
            val profile = profileRepository.getById(session.profileId) ?: return@forEach
            val grants = softUnblockRepository.activeGrantsOnce(session.id, now)
            val activeGrants = grants.associate { it.packageName to it.expiresAt.toEpochMilli() }
            blockingStateHolder.setState(
                BlockingState(
                    profileId = profile.id,
                    sessionId = session.id,
                    profileName = profile.name,
                    blockedPackages = profile.blockedPackages.toSet(),
                    allowMode = profile.allowModeEnabled,
                    blockedDomains = profile.domains,
                    allowModeDomains = profile.allowModeDomains,
                    strictMode = globalProtection.readFor(profile).strictMode,
                    blockAppInstallation = globalProtection.readFor(profile).blockAppInstallation,
                    breakActive = session.isBreakActive(now),
                    pauseActive = session.isPauseActive(now),
                    periodicAccessActive = periodicAccessActive(profile.id, session, now),
                    periodicActivatedAtEpochMillis = activatedPeriodicAt(profile.id, session),
                    periodicWaitMinutes = periodicAccessStore.read(profile.id).waitMinutes,
                    periodicAccessMinutes = periodicAccessStore.read(profile.id).accessMinutes,
                    periodicAnchorEpochMillis = periodicAnchor(session),
                    locationSuspended = locationStoreOutside(profile.id),
                    activeGrants = activeGrants,
                    disableBackgroundStops = globalProtection.readFor(profile).disableBackgroundStops,
                    sessionStartEpochMillis = session.startTime.toEpochMilli(),
                ),
            )
            rearmAlarmsAfterRestart(profile, session, now, grants)
            schedulePeriodicAccess(profile.id, session, now)
        }
        sessions.firstOrNull()?.let { first ->
            blockingStateHolder.stateForProfile(first.profileId)
                ?.let(blockingStateHolder::setState)
            sessionServiceController.startForegroundService(first.id)
        }
    }

    /**
     * Re-applies a profile's current configuration to the *running* session, if that profile owns
     * the active session. [BlockingState] is a snapshot taken at session start, so editing the
     * active profile (e.g. toggling website allow-mode, adding a blocked app) would otherwise have
     * no effect until the session is stopped and restarted - which is impossible mid-session for a
     * scan-to-stop strategy without the tag/code. Called after a profile is saved so edits take
     * effect immediately. No-ops when the edited profile isn't the active one.
     */
    suspend fun refreshActiveProfileConfig(profileId: String) = withContext(ioDispatcher) {
        val session = sessionForProfile(profileId) ?: return@withContext
        val profile = profileRepository.getById(profileId) ?: return@withContext
        if (_activeSession.value?.profileId == profileId) _activeProfile.value = profile

        val now = Instant.now()
        val grants = softUnblockRepository.activeGrantsOnce(session.id, now)
        val activeGrants = grants.associate { it.packageName to it.expiresAt.toEpochMilli() }

        blockingStateHolder.setState(
            BlockingState(
                profileId = profile.id,
                sessionId = session.id,
                profileName = profile.name,
                blockedPackages = profile.blockedPackages.toSet(),
                allowMode = profile.allowModeEnabled,
                blockedDomains = profile.domains,
                allowModeDomains = profile.allowModeDomains,
                strictMode = globalProtection.readFor(profile).strictMode,
                blockAppInstallation = globalProtection.readFor(profile).blockAppInstallation,
                breakActive = session.isBreakActive(now),
                pauseActive = session.isPauseActive(now),
                periodicAccessActive = periodicAccessActive(profile.id, session, now),
                periodicActivatedAtEpochMillis = activatedPeriodicAt(profile.id, session),
                periodicWaitMinutes = periodicAccessStore.read(profile.id).waitMinutes,
                periodicAccessMinutes = periodicAccessStore.read(profile.id).accessMinutes,
                periodicAnchorEpochMillis = periodicAnchor(session),
                locationSuspended = locationStoreOutside(profile.id),
                activeGrants = activeGrants,
                disableBackgroundStops = globalProtection.readFor(profile).disableBackgroundStops,
                sessionStartEpochMillis = session.startTime.toEpochMilli(),
            ),
        )
        schedulePeriodicAccess(profile.id, session, now)
    }

    private fun rearmAlarmsAfterRestart(
        profile: BlockedProfile,
        session: BlockedProfileSession,
        now: Instant,
        grants: List<SoftUnblockGrant>,
    ) {
        val strategy = StrategyRegistry.byId(profile.strategyId)
        if (strategy.hasTimer && session.tag != AlarmActivityType.SCHEDULE_START.key) {
            val timerData = StrategyTimerData.fromJsonOrNull(profile.strategyDataJson)
            val endAt = session.startTime.plusSeconds(timerData.durationInMinutes * 60L)
            if (endAt.isAfter(now)) {
                alarmScheduler.scheduleExactAt(
                    AlarmActivityType.STRATEGY_TIMER_END,
                    profile.id,
                    endAt,
                    mapOf("sessionId" to session.id),
                )
            }
        }

        if (session.isBreakActive(now)) {
            val totalAllowanceSeconds = profile.breakTimeInMinutes * 60L
            val remainingAllowance = if (profile.allowMultipleBreaks) {
                maxOf(0L, totalAllowanceSeconds - session.usedBreakDurationInSeconds)
            } else {
                totalAllowanceSeconds
            }
            val breakEndAt = session.breakStartTime!!.plusSeconds(minOf(totalAllowanceSeconds, remainingAllowance))
            if (breakEndAt.isAfter(now)) {
                alarmScheduler.scheduleExactAt(
                    AlarmActivityType.BREAK_END,
                    profile.id,
                    breakEndAt,
                    mapOf("sessionId" to session.id),
                )
            }
        }

        if (session.isPauseActive(now)) {
            val pauseDurationMinutes = com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext)
                .pauseMinutes(session.id)
                ?: StrategyPauseTimerData.fromJsonOrNull(profile.strategyDataJson).pauseDurationInMinutes
            val pauseEndAt = session.pauseStartTime!!.plusSeconds(pauseDurationMinutes * 60L)
            if (pauseEndAt.isAfter(now)) {
                alarmScheduler.scheduleExactAt(
                    AlarmActivityType.PAUSE_END,
                    profile.id,
                    pauseEndAt,
                    mapOf("sessionId" to session.id),
                )
            }
        }

        grants.forEach { grant ->
            alarmScheduler.scheduleExactAt(
                AlarmActivityType.GRANT_EXPIRY,
                profile.id,
                grant.expiresAt,
                mapOf("sessionId" to session.id, "grantId" to grant.id, "packageName" to grant.packageName),
            )
        }

        if (profile.schedule?.isConfigured == true) {
            alarmScheduler.scheduleWeeklySchedule(profile)
        }
    }

    // --- StrategyHost ------------------------------------------------------------------------

    override suspend fun createSession(
        profile: BlockedProfile, tag: String, forceStarted: Boolean,
    ): BlockedProfileSession = sessionMutex.withLock {
        // Serialize simultaneous starts so two NFC/schedule events cannot make two sessions
        // for the same profile or overwrite a different profile's in-memory cache.
        val existing = sessionForProfile(profile.id)
            ?: sessionRepository.activeSessionForProfile(profile.id)
        if (existing != null) {
            if (sessionForProfile(profile.id) == null) cacheSession(existing)
            return@withLock existing
        }

        if (fullScreenModeStore.enabled(profile.id)) {
            val runningId = _activeSessions.value.firstOrNull {
                it.profileId != profile.id && fullScreenModeStore.enabled(it.profileId)
            }?.profileId
            if (runningId != null) throw FullScreenSessionConflict(runningId)
        }

        val session = sessionRepository.startSession(profile, tag = tag, forceStarted = forceStarted)
        onSessionStarted(profile, session)
        session
    }

    override suspend fun endSession(session: BlockedProfileSession, at: Instant) {
        sessionMutex.withLock {
        // Guard against a stale/late call targeting a session that isn't active anymore (e.g. a
        // queued stop-UI action for a session that a background alarm already ended) - without
        // this, the destructive steps below would wrongly clobber a *different*, currently active
        // session on the same profile.
        val persisted = sessionRepository.getById(session.id)
        if (persisted == null || !persisted.isActive) return@withLock

        // All real end paths are complete stops. A geofence EXIT is never an end;
        // it only suspends, so ENTER can resume that same still-active session.
        markCompletelyStopped(session.profileId)
        sessionRepository.endSession(session.id, at)
        alarmScheduler.cancelAllForProfile(session.profileId)
        softUnblockRepository.clearForSession(session.id)
        com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext).apply {
            clear(session.profileId)
            clearPauseMinutes(session.id)
        }

        blockingStateHolder.clearProfile(session.profileId)
        uncacheSession(session.id)
        _activeProfile.value = _activeSession.value?.let { profileRepository.getById(it.profileId) }

        // Keep the shared foreground service alive while any other profile is active.
        if (_activeSessions.value.isEmpty()) {
            sessionServiceController.stopForegroundService(session.id)
        }
        widgetRefresher.refresh()

        // cancelAllForProfile above also wipes any still-armed weekly schedule alarms - re-arm
        // them so a profile's recurring schedule survives ending an (unrelated) session on it.
        val profile = profileRepository.getById(session.profileId)
        if (profile?.schedule?.isConfigured == true) {
            alarmScheduler.scheduleWeeklySchedule(profile)
        }
        }
    }

    override suspend fun beginPause(session: BlockedProfileSession, durationMinutes: Int) {
        // Covers legacy strategy paths as well as the new independently configured conditions.
        if (!pauseRetryAllowed(session.profileId)) return
        val now = Instant.now()
        sessionRepository.startPause(session.id, now)
        independentUnlock.markPauseAuthorized(session.profileId, now.toEpochMilli())
        blockingStateHolder.updateProfile(session.profileId) { it.copy(pauseActive = true) }
        alarmScheduler.scheduleExactAt(
            AlarmActivityType.PAUSE_END,
            session.profileId,
            now.plusSeconds(durationMinutes * 60L),
            mapOf("sessionId" to session.id),
        )

        cacheSession(session.copy(pauseStartTime = now, pauseEndTime = null))
    }

    override suspend fun beginSoftUnblockSession(
        profile: BlockedProfile,
        session: BlockedProfileSession,
        data: SoftUnblockStrategyData,
    ) {
        val now = Instant.now()
        softUnblockRepository.beginSession(
            SoftUnblockSessionState(
                sessionId = session.id,
                profileId = profile.id,
                maximumUnblockCount = data.maximumUnblockCount,
                allowanceResetIntervalInHours = data.allowanceResetIntervalInHours,
                allowanceWindowStartedAt = now,
                nextAllowanceResetAt = data.allowanceResetIntervalInHours?.let { now.plusSeconds(it * 3600L) },
            ),
        )
    }

    override fun emitError(message: String) {
        _errors.tryEmit(message)
    }

    // --- Session-start side effects --------------------------------------------------------

    private fun onSessionStarted(profile: BlockedProfile, session: BlockedProfileSession) {
        cacheSession(session)
        _activeProfile.value = profile

        val strategy = StrategyRegistry.byId(profile.strategyId)
        if (strategy.hasTimer) {
            val timerData = StrategyTimerData.fromJsonOrNull(profile.strategyDataJson)
            val endAt = session.startTime.plusSeconds(timerData.durationInMinutes * 60L)
            alarmScheduler.scheduleExactAt(
                AlarmActivityType.STRATEGY_TIMER_END,
                profile.id,
                endAt,
                mapOf("sessionId" to session.id),
            )
        }

        blockingStateHolder.setState(
            BlockingState(
                profileId = profile.id,
                sessionId = session.id,
                profileName = profile.name,
                blockedPackages = profile.blockedPackages.toSet(),
                allowMode = profile.allowModeEnabled,
                blockedDomains = profile.domains,
                allowModeDomains = profile.allowModeDomains,
                strictMode = globalProtection.readFor(profile).strictMode,
                blockAppInstallation = globalProtection.readFor(profile).blockAppInstallation,
                breakActive = false,
                pauseActive = false,
                periodicAccessActive = periodicAccessActive(profile.id, session, Instant.now()),
                periodicActivatedAtEpochMillis = activatedPeriodicAt(profile.id, session),
                periodicWaitMinutes = periodicAccessStore.read(profile.id).waitMinutes,
                periodicAccessMinutes = periodicAccessStore.read(profile.id).accessMinutes,
                periodicAnchorEpochMillis = periodicAnchor(session),
                locationSuspended = locationStoreOutside(profile.id),
                activeGrants = emptyMap(),
                disableBackgroundStops = globalProtection.readFor(profile).disableBackgroundStops,
                sessionStartEpochMillis = session.startTime.toEpochMilli(),
            ),
        )

        schedulePeriodicAccess(profile.id, session)
        sessionServiceController.startForegroundService(session.id)
        widgetRefresher.refresh()
    }

    // --- Alarm handlers ----------------------------------------------------------------------

    /**
     * A profile's weekly schedule window has started. Mirrors iOS `ScheduleTimerActivity.start`:
     * continues an already-active session for the same profile untouched, ends a different
     * profile's active session and takes over, or starts a brand new schedule-driven session if
     * nothing is active. Always re-arms the next SCHEDULE_START occurrence.
     */
    private suspend fun handleScheduleStart(profileId: String, at: Instant) {
        val profile = profileRepository.getById(profileId) ?: return
        if (profile.schedule?.isConfigured == true) {
            // A schedule notification is not an explicit Play gesture.
            // Re-arm legacy schedules, but NEVER start a completely stopped profile.
            alarmScheduler.rearmScheduleStart(profile)
        }
    }

    /**
     * A profile's weekly schedule window has ended. Mirrors iOS `ScheduleTimerActivity.stop`: only
     * ends the session if it was actually created by the schedule (a manually-started or
     * strategy-started session on the same profile is left running, matching iOS's behavior of
     * only ever managing sessions it created itself).
     */
    private suspend fun handleScheduleEnd(profileId: String, at: Instant) {
        val current = sessionForProfile(profileId)
        if (current != null && current.profileId == profileId && current.tag == AlarmActivityType.SCHEDULE_START.key) {
            endSession(current, at)
        }
    }

    private suspend fun handleStrategyTimerEnd(profileId: String, extras: Map<String, String>, at: Instant) {
        val current = sessionForProfile(profileId) ?: return
        if (current.profileId != profileId) return
        val expectedSessionId = extras["sessionId"]
        if (expectedSessionId != null && current.id != expectedSessionId) return
        endSession(current, at)
    }

    private suspend fun handleBreakEnd(profileId: String, extras: Map<String, String>, at: Instant) {
        val current = sessionForProfile(profileId) ?: return
        if (current.profileId != profileId) return
        // A stale alarm from an earlier, already-ended session on the same profile must not end
        // the break of whatever session is active *now*.
        val expectedSessionId = extras["sessionId"]
        if (expectedSessionId != null && current.id != expectedSessionId) return
        if (!current.isBreakActive(at)) return
        endBreak(at, profileId)
    }

    /**
     * A pause has run its full duration. **Verified against iOS**
     * (`Foqos/Models/Timers/PauseTimerActivity.swift:stop`): this only reactivates restrictions and
     * records `pauseEndTime` - it does **not** end the session, symmetric with [handleBreakEnd].
     */
    private suspend fun handlePauseEnd(profileId: String, extras: Map<String, String>, at: Instant) {
        val current = sessionForProfile(profileId) ?: return
        if (current.profileId != profileId) return
        // Same staleness guard as handleBreakEnd: only ever act on the alarm's own session.
        val expectedSessionId = extras["sessionId"]
        if (expectedSessionId != null && current.id != expectedSessionId) return
        if (!current.isPauseActive(at)) return

        sessionRepository.endPause(current.id, at)
        com.foqos.android.ui.activitylab.ActivityUnlockStore(appContext).clearPauseMinutes(current.id)
        val resumed = current.copy(pauseEndTime = at)
        cacheSession(resumed)
        blockingStateHolder.updateProfile(profileId) {
            it.copy(pauseActive = false, periodicAnchorEpochMillis = periodicAnchor(resumed),
                periodicActivatedAtEpochMillis = 0L, periodicAccessActive = false)
        }
        schedulePeriodicAccess(profileId, resumed, at)
    }

    private suspend fun handleGrantExpiry(profileId: String, extras: Map<String, String>, at: Instant) {
        softUnblockRepository.expireGrants(at)

        // Re-derive the grants map from the repository (source of truth) rather than blindly
        // stripping `packageName` - a stale expiry alarm for an old grant must not evict a *newer*
        // still-valid grant for the same package in the profile's current session.
        val current = sessionForProfile(profileId)
        if (current != null && current.profileId == profileId) {
            val refreshed = softUnblockRepository.activeGrantsOnce(current.id, at)
                .associate { it.packageName to it.expiresAt.toEpochMilli() }
            blockingStateHolder.updateProfile(profileId) { it.copy(activeGrants = refreshed) }
        }
    }

    private suspend fun handleEmergencyAllowanceReset(at: Instant) {
        settingsDataStore.setEmergencyUnblocksRemaining(SettingsDataStore.DEFAULT_EMERGENCY_UNBLOCKS_REMAINING)
        settingsDataStore.setEmergencyLastResetEpochMillis(at.toEpochMilli())
        scheduleNextEmergencyAllowanceReset(at)
    }

    companion object {
        const val LOCATION_SESSION_TAG = "location_geofence"

        /** Not a real profile id - just a stable scheduling key for the global emergency-allowance reset alarm. */
        const val EMERGENCY_ALLOWANCE_KEY = "global-emergency-allowance"
        private const val DAYS_PER_WEEK = 7L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
