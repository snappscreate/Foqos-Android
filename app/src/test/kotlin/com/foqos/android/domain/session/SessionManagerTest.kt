package com.foqos.android.domain.session

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.foqos.android.data.datastore.SettingsDataStore
import com.foqos.android.data.db.AppDatabase
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.PhysicalUnblockItem
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.ui.activitylab.ProfileUnlockPolicy
import com.foqos.android.ui.activitylab.ProfileUnlockPolicyStore
import com.foqos.android.ui.activitylab.ActivityUnlockStore
import com.foqos.android.ui.activitylab.UnlockMethod
import com.foqos.android.data.model.BlockedProfileSchedule
import com.foqos.android.data.model.SoftUnblockStrategyData
import com.foqos.android.data.model.StrategyPauseTimerData
import com.foqos.android.data.model.StrategyTimerData
import com.foqos.android.data.model.Weekday
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.data.repository.SessionRepository
import com.foqos.android.data.repository.SoftUnblockRepository
import com.foqos.android.domain.strategy.ManualBlockingStrategy
import com.foqos.android.domain.strategy.NfcPauseTimerBlockingStrategy
import com.foqos.android.domain.strategy.NfcSoftUnblockBlockingStrategy
import com.foqos.android.domain.strategy.NfcTimerBlockingStrategy
import com.foqos.android.domain.strategy.StrategyInput
import com.foqos.android.receiver.AlarmReceiver
import com.foqos.android.service.alarm.AlarmActivityType
import com.foqos.android.service.alarm.FoqosAlarmScheduler
import com.foqos.android.service.session.BlockingStateHolder
import com.foqos.android.service.session.FullScreenModeStore
import com.foqos.android.service.location.LocationAutomationStore
import com.foqos.android.service.location.LocationRule
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Covers [SessionManager]'s lifecycle orchestration: manual start/stop, deep-link toggling,
 * breaks, timer strategies, pauses, emergency unblock, and soft-unblock grants. Alarm scheduling
 * is asserted against the real [AlarmManager] via Robolectric's shadow, so these tests double as
 * integration coverage for [FoqosAlarmScheduler].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var profileRepository: ProfileRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var softUnblockRepository: SoftUnblockRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var blockingStateHolder: BlockingStateHolder
    private lateinit var alarmScheduler: FoqosAlarmScheduler
    private lateinit var alarmManager: AlarmManager
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        profileRepository = ProfileRepository(db.blockedProfileDao(), Dispatchers.Unconfined)
        sessionRepository = SessionRepository(db.blockedProfileSessionDao(), Dispatchers.Unconfined)
        softUnblockRepository = SoftUnblockRepository(db, db.softUnblockDao(), Dispatchers.Unconfined)
        settingsDataStore = SettingsDataStore(context)
        blockingStateHolder = BlockingStateHolder()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmScheduler = FoqosAlarmScheduler(context)
        sessionManager = SessionManager(
            profileRepository = profileRepository,
            sessionRepository = sessionRepository,
            softUnblockRepository = softUnblockRepository,
            blockingStateHolder = blockingStateHolder,
            alarmScheduler = alarmScheduler,
            settingsDataStore = settingsDataStore,
            ioDispatcher = Dispatchers.Unconfined,
            sessionServiceController = NoOpSessionServiceController(),
            widgetRefresher = NoOpWidgetRefresher(),
            appContext = context,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createProfile(
        id: String = UUID.randomUUID().toString(),
        strategyId: String,
        strategyDataJson: String? = null,
        blockedPackages: List<String> = emptyList(),
        enableBreaks: Boolean = false,
        breakTimeInMinutes: Int = 15,
        allowMultipleBreaks: Boolean = false,
        disableBackgroundStops: Boolean = false,
        enableEmergencyUnblock: Boolean = true,
        schedule: BlockedProfileSchedule? = null,
    ): BlockedProfile = profileRepository.create(
        BlockedProfile(
            id = id,
            name = "Profile $id",
            strategyId = strategyId,
            strategyDataJson = strategyDataJson,
            blockedPackages = blockedPackages,
            enableBreaks = enableBreaks,
            breakTimeInMinutes = breakTimeInMinutes,
            allowMultipleBreaks = allowMultipleBreaks,
            disableBackgroundStops = disableBackgroundStops,
            enableEmergencyUnblock = enableEmergencyUnblock,
            schedule = schedule,
        ),
    )

    private fun alarmKeys(): List<String> {
        val shadow = Shadows.shadowOf(alarmManager)
        return shadow.scheduledAlarms.mapNotNull {
            Shadows.shadowOf(it.operation).savedIntent.getStringExtra(AlarmReceiver.EXTRA_KEY)
        }
    }

    @Test
    fun `independent manual pause cannot be mistaken for QR full stop`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ProfileUnlockPolicyStore(context)
        config.save(profile.id, ProfileUnlockPolicy(
            stop = UnlockMethod.QR, pause = UnlockMethod.MANUAL, pauseMinutes = 13,
            stopCredentialId = "stop-qr-1"))
        try {
            profileRepository.updatePhysicalUnblockItems(profile.id) {
                it + PhysicalUnblockItem(id = "stop-qr-1", name = "QR stop", type = PhysicalUnblockType.QR_CODE,
                    codeValue = "foqos://stop/sample")
            }
            sessionManager.startProfile(profile.id)
            assertThat(sessionManager.activeSession.value).isNotNull()

            sessionManager.requestConfiguredPause(profile.id)
            assertThat(sessionManager.activeSession.value!!.isPauseActive()).isTrue()
            assertThat(ActivityUnlockStore(context).pauseMinutes(sessionManager.activeSession.value!!.id))
                .isEqualTo(13)

            // Pause is not a stop, and the wrong code cannot stop the session.
            assertThat(sessionManager.authorizeConfiguredCode(
                profile.id, "wrong", PhysicalUnblockType.QR_CODE, forPause = false)).isFalse()
            assertThat(sessionManager.activeSession.value).isNotNull()
            assertThat(sessionManager.authorizeConfiguredCode(
                profile.id, "foqos://stop/sample", PhysicalUnblockType.QR_CODE, forPause = false)).isTrue()
            assertThat(sessionManager.activeSession.value).isNull()
        } finally {
            config.delete(profile.id)
        }
    }

    @Test
    fun `NFC pause does not satisfy QR stop condition`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ProfileUnlockPolicyStore(context)
        config.save(profile.id, ProfileUnlockPolicy(
            stop = UnlockMethod.QR, pause = UnlockMethod.NFC, pauseMinutes = 15,
            stopCredentialId = "stop-qr-2", pauseCredentialId = "pause-nfc-1"))
        try {
            profileRepository.updatePhysicalUnblockItems(profile.id) {
                it + PhysicalUnblockItem(id = "pause-nfc-1", name = "NFC pause", type = PhysicalUnblockType.NFC,
                    codeValue = "nfc-proof") +
                    PhysicalUnblockItem(id = "stop-qr-2", name = "QR stop", type = PhysicalUnblockType.QR_CODE,
                        codeValue = "qr-stop-proof") +
                    PhysicalUnblockItem(id = "other-nfc-1", name = "Unselected NFC", type = PhysicalUnblockType.NFC,
                        codeValue = "unselected-nfc")
            }
            sessionManager.startProfile(profile.id)
            assertThat(sessionManager.activeSession.value).isNotNull()
            assertThat(sessionManager.authorizeConfiguredCode(
                profile.id, "nfc-proof", PhysicalUnblockType.NFC, forPause = false)).isFalse()
            assertThat(sessionManager.authorizeConfiguredCode(
                profile.id, "unselected-nfc", PhysicalUnblockType.NFC, forPause = true)).isFalse()
            assertThat(sessionManager.authorizeConfiguredCode(
                profile.id, "nfc-proof", PhysicalUnblockType.NFC, forPause = true)).isTrue()
            assertThat(sessionManager.activeSession.value!!.isPauseActive()).isTrue()
        } finally {
            config.delete(profile.id)
        }
    }

    @Test
    fun `leaving the location pauses enforcement without ending session and reentry restores it`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val location = LocationAutomationStore(context)
        location.save(LocationRule(profile.id, 33.60, 130.42, 200f))
        try {
            location.setLastInside(true)
            sessionManager.onLocationTransition(profile.id, true)
            assertThat(sessionManager.activeSession.value).isNull()
            // Geofence ENTER must never start a fully stopped profile.
            sessionManager.startProfile(profile.id)
            val originalId = sessionManager.activeSession.value!!.id
            assertThat(blockingStateHolder.state.value?.locationSuspended).isFalse()

            location.setLastInside(false)
            sessionManager.onLocationTransition(profile.id, false)
            assertThat(sessionManager.activeSession.value?.id).isEqualTo(originalId)
            assertThat(blockingStateHolder.state.value?.locationSuspended).isTrue()

            location.setLastInside(true)
            sessionManager.onLocationTransition(profile.id, true)
            assertThat(sessionManager.activeSession.value?.id).isEqualTo(originalId)
            assertThat(blockingStateHolder.state.value?.locationSuspended).isFalse()
        } finally {
            location.clear()
        }
    }

    @Test
    fun `completely stopped location profile stays stopped on reentry until manually restarted`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val location = LocationAutomationStore(context)
        val policy = ProfileUnlockPolicyStore(context)
        location.save(LocationRule(profile.id, 33.60, 130.42, 200f))
        policy.save(profile.id, ProfileUnlockPolicy(
            stop = UnlockMethod.MANUAL, pause = UnlockMethod.MANUAL))
        try {
            location.setLastInside(true)
            sessionManager.onLocationTransition(profile.id, true)
            assertThat(sessionManager.activeSession.value).isNull()
            sessionManager.startProfile(profile.id)
            assertThat(sessionManager.activeSession.value).isNotNull()

            // EXIT suspends the existing session. A separate complete Stop ends it.
            location.setLastInside(false)
            sessionManager.onLocationTransition(profile.id, false)
            assertThat(sessionManager.activeSession.value).isNotNull()
            sessionManager.stopLocationProfileFromHome(profile.id)
            assertThat(sessionManager.activeSession.value).isNull()
            assertThat(location.manuallyStopped(profile.id)).isTrue()

            location.setLastInside(true)
            sessionManager.onLocationTransition(profile.id, true)
            assertThat(sessionManager.activeSession.value).isNull()

            sessionManager.startProfile(profile.id)
            assertThat(location.manuallyStopped(profile.id)).isFalse()
            assertThat(sessionManager.activeSession.value).isNotNull()
        } finally {
            location.clear(profile.id)
            policy.delete(profile.id)
        }
    }

    @Test
    fun `manually started linked profile is suspended outside the area too`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val location = LocationAutomationStore(context)
        location.save(LocationRule(profile.id, 33.60, 130.42, 200f))
        try {
            location.setLastInside(true)
            sessionManager.startProfile(profile.id)
            val originalId = sessionManager.activeSession.value!!.id
            location.setLastInside(false)
            sessionManager.onLocationTransition(profile.id, false)
            assertThat(blockingStateHolder.state.value?.locationSuspended).isTrue()
            assertThat(sessionManager.activeSession.value?.id).isEqualTo(originalId)
            location.setLastInside(true)
            sessionManager.onLocationTransition(profile.id, true)
            assertThat(blockingStateHolder.state.value?.locationSuspended).isFalse()
        } finally {
            location.clear()
        }
    }

    @Test
    fun `home pause cannot bypass NFC condition while inside geofence`() = runTest {
        val profile = createProfile(strategyId = NfcPauseTimerBlockingStrategy.ID)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val location = LocationAutomationStore(context)
        location.save(LocationRule(profile.id, 33.60, 130.42, 200f))
        try {
            location.setLastInside(true)
            sessionManager.onLocationTransition(profile.id, true)
            assertThat(sessionManager.activeSession.value).isNull()
            // Geofence ENTER must never start a fully stopped profile.
            sessionManager.startProfile(profile.id)
            sessionManager.deliverInput(
                PendingStrategyAction.Start(profile.id),
                StrategyInput.PauseData(StrategyPauseTimerData(pauseDurationInMinutes = 20)),
            )
            val originalId = sessionManager.activeSession.value!!.id

            // An unverified tap must never start a pause.
            sessionManager.pauseLocationSessionFromHome(profile.id)
            assertThat(sessionManager.activeSession.value?.id).isEqualTo(originalId)
            assertThat(sessionManager.activeSession.value?.isPauseActive()).isFalse()

            // An arbitrary code cannot authorize the alternate verified path either.
            val approved = sessionManager.pauseLocationWithVerifiedCode(
                profile.id, "unregistered-tag", com.foqos.android.data.model.PhysicalUnblockType.NFC,
            )
            assertThat(approved).isFalse()
            assertThat(sessionManager.activeSession.value?.isPauseActive()).isFalse()
        } finally {
            location.clear()
        }
    }

    // --- Manual start/stop lifecycle --------------------------------------------------------

    @Test
    fun `manual profile start persists a session and publishes blocking state`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID, blockedPackages = listOf("com.instagram.android"))

        sessionManager.startProfile(profile.id)

        val active = sessionManager.activeSession.value
        assertThat(active).isNotNull()
        assertThat(active!!.profileId).isEqualTo(profile.id)
        assertThat(sessionRepository.activeSessionOnce()?.id).isEqualTo(active.id)

        val state = blockingStateHolder.state.value
        assertThat(state).isNotNull()
        assertThat(state!!.profileId).isEqualTo(profile.id)
        assertThat(state.blockedPackages).containsExactly("com.instagram.android")
    }

    @Test
    fun `editing the active profile refreshes the live blocking state`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID, blockedPackages = listOf("com.instagram.android"))
        sessionManager.startProfile(profile.id)
        assertThat(blockingStateHolder.state.value?.allowModeDomains).isFalse()

        // Simulate a mid-session edit: turn on website allow-mode and add a domain.
        profileRepository.upsert(profile.copy(allowModeDomains = true, domains = listOf("youtube.com")))
        sessionManager.refreshActiveProfileConfig(profile.id)

        val state = blockingStateHolder.state.value
        assertThat(state?.allowModeDomains).isTrue()
        assertThat(state?.blockedDomains).containsExactly("youtube.com")
    }

    @Test
    fun `refreshing config for a non-active profile does not touch the live state`() = runTest {
        val active = createProfile(strategyId = ManualBlockingStrategy.ID, blockedPackages = listOf("com.instagram.android"))
        val other = createProfile(strategyId = ManualBlockingStrategy.ID)
        sessionManager.startProfile(active.id)

        profileRepository.upsert(other.copy(allowModeDomains = true))
        sessionManager.refreshActiveProfileConfig(other.id)

        assertThat(blockingStateHolder.state.value?.profileId).isEqualTo(active.id)
        assertThat(blockingStateHolder.state.value?.allowModeDomains).isFalse()
    }

    @Test
    fun `stopping the active session ends it, clears state, and cancels alarms`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID)
        sessionManager.startProfile(profile.id)
        val sessionId = sessionManager.activeSession.value!!.id

        sessionManager.stopActiveSession(fromBackground = false)

        assertThat(sessionManager.activeSession.value).isNull()
        assertThat(sessionManager.activeProfile.value).isNull()
        assertThat(blockingStateHolder.state.value).isNull()
        assertThat(sessionRepository.getById(sessionId)?.endTime).isNotNull()
        assertThat(alarmScheduler.trackedKeys).isEmpty()
    }

    @Test
    fun `stopActiveSession from background refuses when disableBackgroundStops is set`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID, disableBackgroundStops = true)
        sessionManager.startProfile(profile.id)
        val sessionId = sessionManager.activeSession.value!!.id

        sessionManager.errors.test {
            sessionManager.stopActiveSession(fromBackground = true)
            assertThat(awaitItem()).isEqualTo("This profile requires the app to stop the session")
        }

        assertThat(sessionManager.activeSession.value?.id).isEqualTo(sessionId)
        assertThat(sessionRepository.getById(sessionId)?.endTime).isNull()
    }

    @Test
    fun `endSession is a no-op for an already-ended session and does not clobber a newer active session`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID)
        sessionManager.startProfile(profile.id)
        val staleSession = sessionManager.activeSession.value!!
        sessionManager.stopActiveSession(fromBackground = false)

        sessionManager.startProfile(profile.id)
        val newSession = sessionManager.activeSession.value!!
        assertThat(newSession.id).isNotEqualTo(staleSession.id)

        // A stale/late call referencing the already-ended session must not clobber the new one.
        sessionManager.endSession(staleSession, Instant.now())

        assertThat(sessionManager.activeSession.value?.id).isEqualTo(newSession.id)
        assertThat(blockingStateHolder.state.value?.sessionId).isEqualTo(newSession.id)
        assertThat(sessionRepository.getById(newSession.id)?.endTime).isNull()
    }

    @Test
    fun `createSession keeps another active profile while starting a second one`() = runTest {
        val profileA = createProfile(id = "profile-a", strategyId = ManualBlockingStrategy.ID)
        val profileB = createProfile(id = "profile-b", strategyId = ManualBlockingStrategy.ID)
        sessionManager.startProfile(profileA.id)
        val sessionAId = sessionManager.activeSession.value!!.id

        sessionManager.createSession(profileB, tag = ManualBlockingStrategy.ID, forceStarted = false)

        assertThat(sessionRepository.getById(sessionAId)?.endTime).isNull()
        assertThat(sessionManager.activeSessions.value.map { it.profileId })
            .containsExactly(profileA.id, profileB.id)
        assertThat(blockingStateHolder.states.value.keys).containsExactly(profileA.id, profileB.id)
    }

    @Test
    fun `only one fullscreen profile can start at a time`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = FullScreenModeStore(context)
        val a = createProfile(id = "fullscreen-a", strategyId = ManualBlockingStrategy.ID)
        val b = createProfile(id = "fullscreen-b", strategyId = ManualBlockingStrategy.ID)
        try {
            store.save(a.id, true, emptySet())
            store.save(b.id, true, emptySet())
            sessionManager.startProfile(a.id)
            sessionManager.startProfile(b.id)

            assertThat(sessionManager.activeSessions.value.map { it.profileId }).containsExactly(a.id)
            assertThat(sessionRepository.activeSessionForProfile(b.id)).isNull()

            sessionManager.stopProfileSession(a.id)
            sessionManager.startProfile(b.id)
            assertThat(sessionManager.activeSessions.value.map { it.profileId }).containsExactly(b.id)
        } finally {
            store.delete(a.id)
            store.delete(b.id)
        }
    }

    @Test
    fun `fullscreen and multiple standard blocking profiles can run together`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = FullScreenModeStore(context)
        val focus = createProfile(id = "fullscreen-focus", strategyId = ManualBlockingStrategy.ID)
        val blockA = createProfile(id = "regular-a", strategyId = ManualBlockingStrategy.ID)
        val blockB = createProfile(id = "regular-b", strategyId = ManualBlockingStrategy.ID)
        try {
            store.save(focus.id, true, emptySet())
            store.save(blockA.id, false, emptySet())
            store.save(blockB.id, false, emptySet())
            sessionManager.startProfile(focus.id)
            sessionManager.startProfile(blockA.id)
            sessionManager.startProfile(blockB.id)

            assertThat(sessionManager.activeSessions.value.map { it.profileId })
                .containsExactly(focus.id, blockA.id, blockB.id)
        } finally {
            store.delete(focus.id)
            store.delete(blockA.id)
            store.delete(blockB.id)
        }
    }

    @Test
    fun `fullscreen conflict applies to a forced shortcut start`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = FullScreenModeStore(context)
        val a = createProfile(id = "shortcut-fullscreen-a", strategyId = ManualBlockingStrategy.ID)
        val b = createProfile(id = "shortcut-fullscreen-b", strategyId = ManualBlockingStrategy.ID)
        try {
            store.save(a.id, true, emptySet())
            store.save(b.id, true, emptySet())
            sessionManager.toggleFromDeeplink(a.id)
            sessionManager.toggleFromDeeplink(b.id)
            assertThat(sessionManager.activeSessions.value.map { it.profileId }).containsExactly(a.id)
        } finally {
            store.delete(a.id)
            store.delete(b.id)
        }
    }

    @Test
    fun `stopping one profile keeps the second profile enforced`() = runTest {
        val profileA = createProfile(id = "profile-a", strategyId = ManualBlockingStrategy.ID)
        val profileB = createProfile(id = "profile-b", strategyId = ManualBlockingStrategy.ID)
        sessionManager.startProfile(profileA.id)
        sessionManager.startProfile(profileB.id)

        sessionManager.stopProfileSession(profileA.id)

        assertThat(sessionManager.activeSessions.value.map { it.profileId }).containsExactly(profileB.id)
        assertThat(blockingStateHolder.states.value.keys).containsExactly(profileB.id)
        assertThat(sessionRepository.activeSessionForProfile(profileA.id)).isNull()
        assertThat(sessionRepository.activeSessionForProfile(profileB.id)).isNotNull()
    }

    @Test
    fun `a pause in one profile never changes second profile pause state`() = runTest {
        val profileA = createProfile(id = "profile-a", strategyId = ManualBlockingStrategy.ID)
        val profileB = createProfile(id = "profile-b", strategyId = ManualBlockingStrategy.ID)
        sessionManager.startProfile(profileA.id)
        sessionManager.startProfile(profileB.id)
        val sessionA = sessionManager.activeSessions.value.first { it.profileId == profileA.id }

        sessionManager.beginPause(sessionA, 5)

        assertThat(blockingStateHolder.stateForProfile(profileA.id)?.pauseActive).isTrue()
        assertThat(blockingStateHolder.stateForProfile(profileB.id)?.pauseActive).isFalse()
    }

    @Test
    fun `pause-end alarm for B does not resume or end A`() = runTest {
        val a = createProfile(id = "profile-a", strategyId = ManualBlockingStrategy.ID)
        val b = createProfile(id = "profile-b", strategyId = ManualBlockingStrategy.ID)
        sessionManager.startProfile(a.id)
        sessionManager.startProfile(b.id)
        val originalA = sessionManager.activeSessions.value.first { it.profileId == a.id }
        val originalB = sessionManager.activeSessions.value.first { it.profileId == b.id }
        sessionManager.beginPause(originalB, 5)

        sessionManager.onAlarm(
            AlarmActivityType.PAUSE_END, b.id,
            mapOf("sessionId" to originalB.id), Instant.now().plusSeconds(301),
        )

        assertThat(sessionManager.activeSessions.value.map { it.profileId })
            .containsExactly(a.id, b.id)
        assertThat(blockingStateHolder.stateForProfile(a.id)?.pauseActive).isFalse()
        assertThat(blockingStateHolder.stateForProfile(b.id)?.pauseActive).isFalse()
        assertThat(sessionRepository.getById(originalA.id)?.endTime).isNull()
    }

    @Test
    fun `process restoration reinstates both independently active profiles`() = runTest {
        val a = createProfile(id = "profile-a", strategyId = ManualBlockingStrategy.ID)
        val b = createProfile(id = "profile-b", strategyId = ManualBlockingStrategy.ID)
        sessionManager.startProfile(a.id)
        sessionManager.startProfile(b.id)
        blockingStateHolder.clear()

        sessionManager.rebuildStateAfterProcessRestart()

        assertThat(sessionManager.activeSessions.value.map { it.profileId })
            .containsExactly(a.id, b.id)
        assertThat(blockingStateHolder.states.value.keys).containsExactly(a.id, b.id)
    }

    @Test
    fun `reset blocking state closes every running profile`() = runTest {
        val a = createProfile(id = "profile-a", strategyId = ManualBlockingStrategy.ID)
        val b = createProfile(id = "profile-b", strategyId = ManualBlockingStrategy.ID)
        sessionManager.startProfile(a.id)
        sessionManager.startProfile(b.id)

        sessionManager.resetAllState()

        assertThat(sessionManager.activeSessions.value).isEmpty()
        assertThat(sessionRepository.activeSessionsOnce()).isEmpty()
        assertThat(blockingStateHolder.states.value).isEmpty()
    }

    // --- Breaks --------------------------------------------------------------------------------

    @Test
    fun `startBreak activates break state and schedules a BREAK_END alarm`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID, enableBreaks = true, breakTimeInMinutes = 10)
        sessionManager.startProfile(profile.id)

        sessionManager.startBreak()

        assertThat(blockingStateHolder.state.value?.breakActive).isTrue()
        assertThat(sessionManager.activeSession.value?.breakStartTime).isNotNull()
        assertThat(alarmKeys()).contains("BreakScheduleActivity:${profile.id}")
    }

    @Test
    fun `endBreak accumulates the used break duration`() = runTest {
        val profile = createProfile(
            strategyId = ManualBlockingStrategy.ID,
            enableBreaks = true,
            breakTimeInMinutes = 10,
            allowMultipleBreaks = true,
        )
        sessionManager.startProfile(profile.id)
        sessionManager.startBreak()
        val breakStart = sessionManager.activeSession.value!!.breakStartTime!!

        sessionManager.endBreak(breakStart.plusSeconds(300))

        assertThat(sessionManager.activeSession.value!!.usedBreakDurationInSeconds).isEqualTo(300)
        assertThat(blockingStateHolder.state.value?.breakActive).isFalse()
        assertThat(alarmKeys()).doesNotContain("BreakScheduleActivity:${profile.id}")
    }

    @Test
    fun `startBreak refuses when the profile does not enable breaks`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID, enableBreaks = false)
        sessionManager.startProfile(profile.id)

        sessionManager.errors.test {
            sessionManager.startBreak()
            assertThat(awaitItem()).isEqualTo("Breaks are not enabled for this profile")
        }
        assertThat(sessionManager.activeSession.value?.breakStartTime).isNull()
    }

    @Test
    fun `a stale BREAK_END alarm carrying an old sessionId does not end the break of a newer session`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID, enableBreaks = true, breakTimeInMinutes = 10)
        sessionManager.startProfile(profile.id)
        val oldSessionId = sessionManager.activeSession.value!!.id
        sessionManager.stopActiveSession(fromBackground = false)

        sessionManager.startProfile(profile.id)
        sessionManager.startBreak()
        val newSessionId = sessionManager.activeSession.value!!.id
        assertThat(newSessionId).isNotEqualTo(oldSessionId)

        // Alarm still carrying the OLD session's id must not touch the new session's break.
        sessionManager.onAlarm(AlarmActivityType.BREAK_END, profile.id, mapOf("sessionId" to oldSessionId), Instant.now())

        assertThat(sessionManager.activeSession.value?.isBreakActive()).isTrue()
        assertThat(blockingStateHolder.state.value?.breakActive).isTrue()
    }

    // --- toggleFromDeeplink ----------------------------------------------------------------

    @Test
    fun `toggleFromDeeplink force-starts a profile when nothing is active`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID)

        sessionManager.toggleFromDeeplink(profile.id, fromBackground = true)

        val active = sessionManager.activeSession.value
        assertThat(active).isNotNull()
        assertThat(active!!.profileId).isEqualTo(profile.id)
        assertThat(active.forceStarted).isTrue()
        assertThat(active.tag).isEqualTo(ManualBlockingStrategy.ID)
    }

    @Test
    fun `toggleFromDeeplink stops the active profile when toggled again`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID)
        sessionManager.toggleFromDeeplink(profile.id, fromBackground = true)
        val sessionId = sessionManager.activeSession.value!!.id

        sessionManager.toggleFromDeeplink(profile.id, fromBackground = true)

        assertThat(sessionManager.activeSession.value).isNull()
        assertThat(sessionRepository.getById(sessionId)?.endTime).isNotNull()
    }

    @Test
    fun `toggleFromDeeplink starts another profile without stopping the first`() = runTest {
        val profileA = createProfile(id = "profile-a", strategyId = ManualBlockingStrategy.ID)
        val profileB = createProfile(id = "profile-b", strategyId = ManualBlockingStrategy.ID)
        sessionManager.toggleFromDeeplink(profileA.id, fromBackground = true)
        val sessionAId = sessionManager.activeSession.value!!.id

        sessionManager.toggleFromDeeplink(profileB.id, fromBackground = true)

        assertThat(sessionRepository.getById(sessionAId)?.endTime).isNull()
        assertThat(sessionManager.activeSessions.value.map { it.profileId })
            .containsExactly(profileA.id, profileB.id)
        assertThat(sessionManager.activeSessions.value.first { it.profileId == profileB.id }.forceStarted).isTrue()
    }

    @Test
    fun `toggleFromDeeplink refuses a background stop when disableBackgroundStops is set`() = runTest {
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID, disableBackgroundStops = true)
        sessionManager.toggleFromDeeplink(profile.id, fromBackground = true)
        val sessionId = sessionManager.activeSession.value!!.id

        sessionManager.errors.test {
            sessionManager.toggleFromDeeplink(profile.id, fromBackground = true)
            assertThat(awaitItem()).isEqualTo("This profile requires the app to stop the session")
        }

        assertThat(sessionManager.activeSession.value?.id).isEqualTo(sessionId)
        assertThat(sessionRepository.getById(sessionId)?.endTime).isNull()
    }

    // --- Timer strategy --------------------------------------------------------------------

    @Test
    fun `starting a timer strategy schedules STRATEGY_TIMER_END at now plus duration`() = runTest {
        val timerData = StrategyTimerData(durationInMinutes = 5)
        val profile = createProfile(strategyId = NfcTimerBlockingStrategy.ID, strategyDataJson = timerData.toJson())

        sessionManager.deliverInput(PendingStrategyAction.Start(profile.id), StrategyInput.TimerData(timerData))

        val session = sessionManager.activeSession.value!!
        val shadow = Shadows.shadowOf(alarmManager)
        val alarm = shadow.scheduledAlarms.single {
            Shadows.shadowOf(it.operation).savedIntent.getStringExtra(AlarmReceiver.EXTRA_KEY) ==
                "StrategyTimerActivity:${profile.id}"
        }
        assertThat(alarm.triggerAtTime).isEqualTo(session.startTime.plusSeconds(300).toEpochMilli())
    }

    @Test
    fun `onAlarm STRATEGY_TIMER_END ends the active session`() = runTest {
        val timerData = StrategyTimerData(durationInMinutes = 5)
        val profile = createProfile(strategyId = NfcTimerBlockingStrategy.ID, strategyDataJson = timerData.toJson())
        sessionManager.deliverInput(PendingStrategyAction.Start(profile.id), StrategyInput.TimerData(timerData))
        val sessionId = sessionManager.activeSession.value!!.id

        sessionManager.onAlarm(AlarmActivityType.STRATEGY_TIMER_END, profile.id, mapOf("sessionId" to sessionId), Instant.now())

        assertThat(sessionManager.activeSession.value).isNull()
        assertThat(sessionRepository.getById(sessionId)?.endTime).isNotNull()
    }

    // --- Pause flow --------------------------------------------------------------------------

    private suspend fun startPausedSession(): Pair<BlockedProfile, String> {
        val pauseData = StrategyPauseTimerData(pauseDurationInMinutes = 20)
        val profile = createProfile(strategyId = NfcPauseTimerBlockingStrategy.ID, strategyDataJson = pauseData.toJson())
        sessionManager.deliverInput(PendingStrategyAction.Start(profile.id), StrategyInput.PauseData(pauseData))
        val session = sessionManager.activeSession.value!!

        sessionManager.deliverInput(
            PendingStrategyAction.Stop(session.id),
            StrategyInput.Code("tag", StrategyInput.CodeSource.NFC),
        )
        return profile to session.id
    }

    @Test
    fun `pausing schedules a PAUSE_END alarm`() = runTest {
        val (profile, _) = startPausedSession()

        assertThat(sessionManager.activeSession.value?.isPauseActive()).isTrue()
        assertThat(blockingStateHolder.state.value?.pauseActive).isTrue()
        assertThat(alarmKeys()).contains("PauseScheduleActivity:${profile.id}")
    }

    @Test
    fun `PAUSE_END alarm resumes blocking without ending the session - verified against iOS PauseTimerActivity stop()`() = runTest {
        val (profile, sessionId) = startPausedSession()

        sessionManager.onAlarm(AlarmActivityType.PAUSE_END, profile.id, emptyMap(), Instant.now())

        val after = sessionManager.activeSession.value
        assertThat(after).isNotNull()
        assertThat(after!!.id).isEqualTo(sessionId)
        assertThat(after.isPauseActive()).isFalse()
        assertThat(after.pauseEndTime).isNotNull()
        assertThat(blockingStateHolder.state.value?.pauseActive).isFalse()
        assertThat(sessionRepository.getById(sessionId)?.endTime).isNull()
    }

    // --- Emergency unblock -------------------------------------------------------------------

    @Test
    fun `emergencyUnblock decrements the remaining counter and ends the session`() = runTest {
        settingsDataStore.setEmergencyUnblocksRemaining(3)
        settingsDataStore.setEmergencyLastResetEpochMillis(Instant.now().toEpochMilli())
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID)
        sessionManager.startProfile(profile.id)
        val sessionId = sessionManager.activeSession.value!!.id

        sessionManager.emergencyUnblock()

        assertThat(sessionManager.activeSession.value).isNull()
        assertThat(sessionRepository.getById(sessionId)?.endTime).isNotNull()
        assertThat(settingsDataStore.emergencyUnblocksRemaining.first()).isEqualTo(2)
    }

    @Test
    fun `emergencyUnblock refuses once the counter is exhausted`() = runTest {
        settingsDataStore.setEmergencyUnblocksRemaining(0)
        settingsDataStore.setEmergencyLastResetEpochMillis(Instant.now().toEpochMilli())
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID)
        sessionManager.startProfile(profile.id)

        sessionManager.errors.test {
            sessionManager.emergencyUnblock()
            assertThat(awaitItem()).isEqualTo("No emergency unblocks remaining")
        }
        assertThat(sessionManager.activeSession.value).isNotNull()
    }

    @Test
    fun `emergencyUnblock respects enableEmergencyUnblock=false`() = runTest {
        settingsDataStore.setEmergencyUnblocksRemaining(3)
        settingsDataStore.setEmergencyLastResetEpochMillis(Instant.now().toEpochMilli())
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID, enableEmergencyUnblock = false)
        sessionManager.startProfile(profile.id)

        sessionManager.errors.test {
            sessionManager.emergencyUnblock()
            assertThat(awaitItem()).isEqualTo("Emergency unblock is disabled for this profile")
        }
        assertThat(sessionManager.activeSession.value).isNotNull()
        assertThat(settingsDataStore.emergencyUnblocksRemaining.first()).isEqualTo(3)
    }

    @Test
    fun `emergency allowance resets once the reset interval has elapsed`() = runTest {
        val longAgo = Instant.parse("2020-01-01T00:00:00Z")
        settingsDataStore.setEmergencyUnblocksRemaining(0)
        settingsDataStore.setEmergencyResetIntervalWeeks(4)
        settingsDataStore.setEmergencyLastResetEpochMillis(longAgo.toEpochMilli())
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID)
        sessionManager.startProfile(profile.id)

        sessionManager.emergencyUnblock()

        assertThat(sessionManager.activeSession.value).isNull()
        assertThat(settingsDataStore.emergencyUnblocksRemaining.first())
            .isEqualTo(SettingsDataStore.DEFAULT_EMERGENCY_UNBLOCKS_REMAINING - 1)
    }

    // --- Soft-unblock grants -----------------------------------------------------------------

    @Test
    fun `issueSoftUnblockGrant adds the grant to the state holder and schedules GRANT_EXPIRY`() = runTest {
        val data = SoftUnblockStrategyData(accessDurationInMinutes = 10, maximumUnblockCount = 2, allowanceResetIntervalInHours = null)
        val profile = createProfile(strategyId = NfcSoftUnblockBlockingStrategy.ID, strategyDataJson = data.toJson())
        sessionManager.deliverInput(PendingStrategyAction.Start(profile.id), StrategyInput.SoftUnblockData(data))
        val session = sessionManager.activeSession.value!!

        val grant = sessionManager.issueSoftUnblockGrant("com.instagram.android")

        assertThat(grant).isNotNull()
        assertThat(blockingStateHolder.state.value?.activeGrants).containsKey("com.instagram.android")
        assertThat(alarmKeys()).contains("SoftUnblockGrantTimerActivity:${profile.id}|${session.id}|${grant!!.id}")
    }

    @Test
    fun `issueSoftUnblockGrant refuses once the allowance is exhausted`() = runTest {
        val data = SoftUnblockStrategyData(accessDurationInMinutes = 10, maximumUnblockCount = 1, allowanceResetIntervalInHours = null)
        val profile = createProfile(strategyId = NfcSoftUnblockBlockingStrategy.ID, strategyDataJson = data.toJson())
        sessionManager.deliverInput(PendingStrategyAction.Start(profile.id), StrategyInput.SoftUnblockData(data))
        sessionManager.issueSoftUnblockGrant("com.a")

        sessionManager.errors.test {
            val grant = sessionManager.issueSoftUnblockGrant("com.b")
            assertThat(grant).isNull()
            assertThat(awaitItem()).isEqualTo("No temporary access remaining")
        }
    }

    @Test
    fun `onAlarm GRANT_EXPIRY removes the grant from the state holder`() = runTest {
        val data = SoftUnblockStrategyData(accessDurationInMinutes = 10, maximumUnblockCount = 2, allowanceResetIntervalInHours = null)
        val profile = createProfile(strategyId = NfcSoftUnblockBlockingStrategy.ID, strategyDataJson = data.toJson())
        sessionManager.deliverInput(PendingStrategyAction.Start(profile.id), StrategyInput.SoftUnblockData(data))
        val session = sessionManager.activeSession.value!!
        val grant = sessionManager.issueSoftUnblockGrant("com.instagram.android")!!

        sessionManager.onAlarm(
            AlarmActivityType.GRANT_EXPIRY,
            profile.id,
            mapOf("sessionId" to session.id, "grantId" to grant.id, "packageName" to "com.instagram.android"),
            grant.expiresAt,
        )

        assertThat(blockingStateHolder.state.value?.activeGrants).doesNotContainKey("com.instagram.android")
    }

    @Test
    fun `a stale GRANT_EXPIRY alarm does not evict a newer valid grant for the same package`() = runTest {
        val data = SoftUnblockStrategyData(accessDurationInMinutes = 10, maximumUnblockCount = 5, allowanceResetIntervalInHours = null)
        val profile = createProfile(strategyId = NfcSoftUnblockBlockingStrategy.ID, strategyDataJson = data.toJson())
        sessionManager.deliverInput(PendingStrategyAction.Start(profile.id), StrategyInput.SoftUnblockData(data))
        val session = sessionManager.activeSession.value!!

        val staleGrant = sessionManager.issueSoftUnblockGrant("com.instagram.android")!!
        // A second grant for the same package is issued before the first one's expiry alarm fires
        // (e.g. the user re-opened the app within the allowance).
        val freshGrant = sessionManager.issueSoftUnblockGrant("com.instagram.android")!!
        assertThat(freshGrant.id).isNotEqualTo(staleGrant.id)

        // The stale grant's own (now-late) expiry alarm must not blindly evict the package - the
        // still-valid fresh grant must survive.
        sessionManager.onAlarm(
            AlarmActivityType.GRANT_EXPIRY,
            profile.id,
            mapOf("sessionId" to session.id, "grantId" to staleGrant.id, "packageName" to "com.instagram.android"),
            Instant.now(),
        )

        assertThat(blockingStateHolder.state.value?.activeGrants).containsKey("com.instagram.android")
    }

    // --- Schedule alarms -----------------------------------------------------------------------

    @Test
    fun `onAlarm SCHEDULE_START never starts a stopped profile but rearms next occurrence`() = runTest {
        val schedule = BlockedProfileSchedule(days = listOf(Weekday.MONDAY), startHour = 9, startMinute = 0, endHour = 17, endMinute = 0)
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID, schedule = schedule)

        sessionManager.onAlarm(AlarmActivityType.SCHEDULE_START, profile.id, emptyMap(), Instant.now())

        val active = sessionManager.activeSession.value
        assertThat(active).isNull()
        assertThat(alarmScheduler.trackedKeys).contains("ScheduleTimerActivityStart:${profile.id}")
    }

    @Test
    fun `onAlarm SCHEDULE_END only ends sessions the schedule itself created`() = runTest {
        val schedule = BlockedProfileSchedule(days = listOf(Weekday.MONDAY), startHour = 9, startMinute = 0, endHour = 17, endMinute = 0)
        val profile = createProfile(strategyId = ManualBlockingStrategy.ID, schedule = schedule)
        sessionManager.startProfile(profile.id)
        val sessionId = sessionManager.activeSession.value!!.id

        sessionManager.onAlarm(AlarmActivityType.SCHEDULE_END, profile.id, emptyMap(), Instant.now())

        assertThat(sessionManager.activeSession.value?.id).isEqualTo(sessionId)
        assertThat(sessionRepository.getById(sessionId)?.endTime).isNull()
    }

    // --- Process-restart alarm re-arming ------------------------------------------------------

    @Test
    fun `rebuildStateAfterProcessRestart re-arms STRATEGY_TIMER_END for a still-active session`() = runTest {
        val timerData = StrategyTimerData(durationInMinutes = 5)
        val profile = createProfile(strategyId = NfcTimerBlockingStrategy.ID, strategyDataJson = timerData.toJson())
        sessionManager.deliverInput(PendingStrategyAction.Start(profile.id), StrategyInput.TimerData(timerData))
        val session = sessionManager.activeSession.value!!

        // Simulate a fresh process: a brand new FoqosAlarmScheduler/BlockingStateHolder (their
        // in-memory state is gone) wired into a new SessionManager sharing the same persisted
        // repositories - the underlying AlarmManager/session data survives the "restart".
        val context = ApplicationProvider.getApplicationContext<Context>()
        val freshAlarmScheduler = FoqosAlarmScheduler(context)
        val freshStateHolder = BlockingStateHolder()
        val freshSessionManager = SessionManager(
            profileRepository = profileRepository,
            sessionRepository = sessionRepository,
            softUnblockRepository = softUnblockRepository,
            blockingStateHolder = freshStateHolder,
            alarmScheduler = freshAlarmScheduler,
            settingsDataStore = settingsDataStore,
            ioDispatcher = Dispatchers.Unconfined,
            sessionServiceController = NoOpSessionServiceController(),
            widgetRefresher = NoOpWidgetRefresher(),
            appContext = context,
        )

        freshSessionManager.rebuildStateAfterProcessRestart()

        assertThat(freshAlarmScheduler.trackedKeys).contains("StrategyTimerActivity:${profile.id}")
        assertThat(freshStateHolder.state.value?.sessionId).isEqualTo(session.id)
    }
}
