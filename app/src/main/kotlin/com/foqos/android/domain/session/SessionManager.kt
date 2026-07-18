package com.foqos.android.domain.session

import com.foqos.android.data.model.BlockedProfile
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What a [StrategyUiRequest] emitted by [SessionManager.uiRequests] resolves to once fed input. */
sealed interface PendingStrategyAction {
    data class Start(val profileId: String) : PendingStrategyAction
    data class Stop(val sessionId: String) : PendingStrategyAction
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
) : StrategyHost {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _activeSession = MutableStateFlow<BlockedProfileSession?>(null)
    val activeSession: StateFlow<BlockedProfileSession?> = _activeSession.asStateFlow()

    private val _activeProfile = MutableStateFlow<BlockedProfile?>(null)
    val activeProfile: StateFlow<BlockedProfile?> = _activeProfile.asStateFlow()

    private val _uiRequests = MutableSharedFlow<Pair<StrategyUiRequest, PendingStrategyAction>>(extraBufferCapacity = 1)
    val uiRequests: SharedFlow<Pair<StrategyUiRequest, PendingStrategyAction>> = _uiRequests.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    init {
        scope.launch {
            _activeSession.value = sessionRepository.activeSessionOnce()
            _activeProfile.value = _activeSession.value?.let { profileRepository.getById(it.profileId) }
        }
    }

    // --- Public API ------------------------------------------------------------------------

    /** Resolves [profileId]'s configured strategy and either starts it directly or requests UI. */
    suspend fun startProfile(profileId: String) {
        val profile = profileRepository.getById(profileId) ?: run {
            emitError("Failed to find a profile stored locally that matches the tag")
            return
        }
        val strategy = StrategyRegistry.byId(profile.strategyId)
        val uiRequest = strategy.startRequest(profile)
        if (uiRequest == StrategyUiRequest.None) {
            strategy.onStartInput(profile, StrategyInput.NoInput, this)
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
                val strategy = StrategyRegistry.byId(profile.strategyId)
                strategy.onStartInput(profile, input, this)
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
        val session = _activeSession.value ?: run {
            emitError("No active session to stop")
            return
        }
        val profile = profileRepository.getById(session.profileId) ?: run {
            emitError("Failed to find a profile stored locally that matches the tag")
            return
        }
        if (fromBackground && profile.disableBackgroundStops) {
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
        val current = _activeSession.value
        if (current == null) {
            val target = profileRepository.getById(profileId) ?: run {
                emitError("Failed to find a profile stored locally that matches the tag")
                return
            }
            createSession(target, tag = ManualBlockingStrategy.ID, forceStarted = true)
            return
        }

        val currentProfile = profileRepository.getById(current.profileId)
        if (fromBackground && currentProfile?.disableBackgroundStops == true) {
            emitError("This profile requires the app to stop the session")
            return
        }

        endSession(current, Instant.now())

        if (current.profileId != profileId) {
            val target = profileRepository.getById(profileId) ?: run {
                emitError("Failed to find a profile stored locally that matches the tag")
                return
            }
            createSession(target, tag = ManualBlockingStrategy.ID, forceStarted = true)
        }
    }

    /**
     * Starts a timed break on the active session, if the profile/strategy allow it and allowance
     * remains. Mirrors iOS `StrategyManager.startBreak`.
     */
    suspend fun startBreak() {
        val session = _activeSession.value ?: run {
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
        blockingStateHolder.setBreakActive(true)

        val durationSeconds = minOf(totalAllowanceSeconds, remainingAllowance)
        alarmScheduler.scheduleExactAt(
            AlarmActivityType.BREAK_END,
            profile.id,
            now.plusSeconds(durationSeconds),
            mapOf("sessionId" to session.id),
        )

        _activeSession.value = session.copy(breakStartTime = now, breakEndTime = null)
    }

    /** Ends the active session's break at [at], accumulating used break duration. */
    suspend fun endBreak(at: Instant = Instant.now()) {
        val session = _activeSession.value ?: return
        val profile = profileRepository.getById(session.profileId) ?: return

        alarmScheduler.cancel(AlarmActivityType.BREAK_END, profile.id, mapOf("sessionId" to session.id))
        sessionRepository.endBreak(session.id, profile.allowMultipleBreaks, profile.breakTimeInMinutes * 60L, at)
        blockingStateHolder.setBreakActive(false)

        val breakStart = session.breakStartTime
        val elapsedSeconds = if (breakStart != null) maxOf(0L, at.toEpochMilli() - breakStart.toEpochMilli()) / 1000L else 0L
        val newUsedDuration = if (profile.allowMultipleBreaks) {
            minOf(profile.breakTimeInMinutes * 60L, session.usedBreakDurationInSeconds + elapsedSeconds)
        } else {
            elapsedSeconds
        }

        _activeSession.value = session.copy(breakEndTime = at, usedBreakDurationInSeconds = newUsedDuration)
    }

    /**
     * Force-stops the active session via a global "break-glass" emergency counter (default 3,
     * refilled every `emergencyResetIntervalWeeks`), if the profile allows it and allowance
     * remains. Mirrors iOS `StrategyManager.emergencyUnblock`.
     */
    suspend fun emergencyUnblock() {
        val session = _activeSession.value ?: run {
            emitError("No active session to unblock")
            return
        }
        val profile = profileRepository.getById(session.profileId) ?: run {
            emitError("Failed to find a profile stored locally that matches the tag")
            return
        }
        if (!profile.enableEmergencyUnblock) {
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
    suspend fun issueSoftUnblockGrant(packageName: String): SoftUnblockGrant? {
        val session = _activeSession.value ?: return null
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

        blockingStateHolder.updateGrants { it + (packageName to grant.expiresAt.toEpochMilli()) }
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
            AlarmActivityType.GRANT_EXPIRY -> handleGrantExpiry(profileId, extras, at)
            AlarmActivityType.EMERGENCY_ALLOWANCE_RESET -> handleEmergencyAllowanceReset(at)
            AlarmActivityType.REMINDER -> Unit // The receiver posts the reminder notification itself; nothing to update here.
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
        val session = sessionRepository.activeSessionOnce()
        _activeSession.value = session
        if (session == null) {
            _activeProfile.value = null
            blockingStateHolder.clear()
            return
        }

        val profile = profileRepository.getById(session.profileId)
        _activeProfile.value = profile
        if (profile == null) return

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
                strictMode = profile.enableStrictMode,
                blockAppInstallation = profile.enableBlockAppInstallation,
                breakActive = session.isBreakActive(now),
                pauseActive = session.isPauseActive(now),
                activeGrants = activeGrants,
                disableBackgroundStops = profile.disableBackgroundStops,
                sessionStartEpochMillis = session.startTime.toEpochMilli(),
            ),
        )

        sessionServiceController.startForegroundService(session.id)

        // FoqosAlarmScheduler's cancelAllForProfile only knows about alarms scheduled since this
        // process started - the alarms themselves survive process death at the OS level, but our
        // in-memory tracked-keys registry does not. Re-arming (idempotently, same trigger time)
        // every alarm implied by the restored session/grants keeps that registry accurate so a
        // later endSession()/cancelAllForProfile() call can actually cancel them.
        rearmAlarmsAfterRestart(profile, session, now, grants)
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
            val pauseDurationMinutes = StrategyPauseTimerData.fromJsonOrNull(profile.strategyDataJson).pauseDurationInMinutes
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

    override suspend fun createSession(profile: BlockedProfile, tag: String, forceStarted: Boolean): BlockedProfileSession {
        // Enforce "single active session at a time" at this single choke point, regardless of
        // which caller forgot to end a still-active *different* session first (toggleFromDeeplink
        // and the schedule-start handler already do this explicitly, so this is a no-op for them).
        val current = _activeSession.value
        if (current != null && current.profileId != profile.id) {
            endSession(current, Instant.now())
        }

        val session = sessionRepository.startSession(profile, tag = tag, forceStarted = forceStarted)
        onSessionStarted(profile, session)
        return session
    }

    override suspend fun endSession(session: BlockedProfileSession, at: Instant) {
        // Guard against a stale/late call targeting a session that isn't active anymore (e.g. a
        // queued stop-UI action for a session that a background alarm already ended) - without
        // this, the destructive steps below would wrongly clobber a *different*, currently active
        // session on the same profile.
        val persisted = sessionRepository.getById(session.id)
        if (persisted == null || !persisted.isActive) return

        sessionRepository.endSession(session.id, at)
        alarmScheduler.cancelAllForProfile(session.profileId)
        softUnblockRepository.clearForSession(session.id)

        if (_activeSession.value?.id == session.id) {
            blockingStateHolder.clear()
            _activeSession.value = null
            _activeProfile.value = null
        }

        sessionServiceController.stopForegroundService(session.id)
        widgetRefresher.refresh()

        // cancelAllForProfile above also wipes any still-armed weekly schedule alarms - re-arm
        // them so a profile's recurring schedule survives ending an (unrelated) session on it.
        val profile = profileRepository.getById(session.profileId)
        if (profile?.schedule?.isConfigured == true) {
            alarmScheduler.scheduleWeeklySchedule(profile)
        }
    }

    override suspend fun beginPause(session: BlockedProfileSession, durationMinutes: Int) {
        val now = Instant.now()
        sessionRepository.startPause(session.id, now)
        blockingStateHolder.setPauseActive(true)
        alarmScheduler.scheduleExactAt(
            AlarmActivityType.PAUSE_END,
            session.profileId,
            now.plusSeconds(durationMinutes * 60L),
            mapOf("sessionId" to session.id),
        )

        if (_activeSession.value?.id == session.id) {
            _activeSession.value = session.copy(pauseStartTime = now, pauseEndTime = null)
        }
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
        _activeSession.value = session
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
                strictMode = profile.enableStrictMode,
                blockAppInstallation = profile.enableBlockAppInstallation,
                breakActive = false,
                pauseActive = false,
                activeGrants = emptyMap(),
                disableBackgroundStops = profile.disableBackgroundStops,
                sessionStartEpochMillis = session.startTime.toEpochMilli(),
            ),
        )

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
            val current = _activeSession.value
            when {
                current == null -> createSession(profile, tag = AlarmActivityType.SCHEDULE_START.key, forceStarted = false)
                current.profileId != profileId -> {
                    endSession(current, at)
                    createSession(profile, tag = AlarmActivityType.SCHEDULE_START.key, forceStarted = false)
                }
                else -> Unit // Same profile already running (started manually or by a prior fire) - leave it be.
            }
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
        val current = _activeSession.value
        if (current != null && current.profileId == profileId && current.tag == AlarmActivityType.SCHEDULE_START.key) {
            endSession(current, at)
        }
    }

    private suspend fun handleStrategyTimerEnd(profileId: String, extras: Map<String, String>, at: Instant) {
        val current = _activeSession.value ?: return
        if (current.profileId != profileId) return
        val expectedSessionId = extras["sessionId"]
        if (expectedSessionId != null && current.id != expectedSessionId) return
        endSession(current, at)
    }

    private suspend fun handleBreakEnd(profileId: String, extras: Map<String, String>, at: Instant) {
        val current = _activeSession.value ?: return
        if (current.profileId != profileId) return
        // A stale alarm from an earlier, already-ended session on the same profile must not end
        // the break of whatever session is active *now*.
        val expectedSessionId = extras["sessionId"]
        if (expectedSessionId != null && current.id != expectedSessionId) return
        if (!current.isBreakActive(at)) return
        endBreak(at)
    }

    /**
     * A pause has run its full duration. **Verified against iOS**
     * (`Foqos/Models/Timers/PauseTimerActivity.swift:stop`): this only reactivates restrictions and
     * records `pauseEndTime` - it does **not** end the session, symmetric with [handleBreakEnd].
     */
    private suspend fun handlePauseEnd(profileId: String, extras: Map<String, String>, at: Instant) {
        val current = _activeSession.value ?: return
        if (current.profileId != profileId) return
        // Same staleness guard as handleBreakEnd: only ever act on the alarm's own session.
        val expectedSessionId = extras["sessionId"]
        if (expectedSessionId != null && current.id != expectedSessionId) return
        if (!current.isPauseActive(at)) return

        sessionRepository.endPause(current.id, at)
        blockingStateHolder.setPauseActive(false)
        _activeSession.value = current.copy(pauseEndTime = at)
    }

    private suspend fun handleGrantExpiry(profileId: String, extras: Map<String, String>, at: Instant) {
        softUnblockRepository.expireGrants(at)

        // Re-derive the grants map from the repository (source of truth) rather than blindly
        // stripping `packageName` - a stale expiry alarm for an old grant must not evict a *newer*
        // still-valid grant for the same package in the profile's current session.
        val current = _activeSession.value
        if (current != null && current.profileId == profileId) {
            val refreshed = softUnblockRepository.activeGrantsOnce(current.id, at)
                .associate { it.packageName to it.expiresAt.toEpochMilli() }
            blockingStateHolder.updateGrants { refreshed }
        }
    }

    private suspend fun handleEmergencyAllowanceReset(at: Instant) {
        settingsDataStore.setEmergencyUnblocksRemaining(SettingsDataStore.DEFAULT_EMERGENCY_UNBLOCKS_REMAINING)
        settingsDataStore.setEmergencyLastResetEpochMillis(at.toEpochMilli())
        scheduleNextEmergencyAllowanceReset(at)
    }

    companion object {
        /** Not a real profile id - just a stable scheduling key for the global emergency-allowance reset alarm. */
        const val EMERGENCY_ALLOWANCE_KEY = "global-emergency-allowance"
        private const val DAYS_PER_WEEK = 7L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
