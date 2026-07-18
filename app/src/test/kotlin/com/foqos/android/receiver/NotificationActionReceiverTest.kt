package com.foqos.android.receiver

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.data.datastore.SettingsDataStore
import com.foqos.android.data.db.AppDatabase
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.data.repository.SessionRepository
import com.foqos.android.data.repository.SoftUnblockRepository
import com.foqos.android.domain.session.NoOpSessionServiceController
import com.foqos.android.domain.session.NoOpWidgetRefresher
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.domain.strategy.ManualBlockingStrategy
import com.foqos.android.service.alarm.FoqosAlarmScheduler
import com.foqos.android.service.session.BlockingStateHolder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [NotificationActionReceiver]'s stop path.
 *
 * Exercises [handleStopAction] and [NotificationActionReceiver.shouldHandle] directly rather than
 * `onReceive` itself: this codebase has no Hilt test infrastructure, and `@AndroidEntryPoint`'s
 * bytecode-injected `inject(this)` call (which runs at the top of every real `onReceive`) would
 * silently overwrite a test-assigned `sessionManager` field with whatever the real
 * `FoqosApplication` Hilt graph provides - `handleStopAction`/`shouldHandle` exist precisely so the
 * receiver's actual logic can be tested without that. Still built as a real [SessionManager] on a
 * real (in-memory) Room database, same as `SessionManagerTest`, so this covers the genuine stop
 * behavior end to end.
 *
 * `application = android.app.Application::class` deliberately avoids instantiating the real
 * `FoqosApplication` (the manifest's default under Robolectric): that's a real `@HiltAndroidApp`
 * whose `onCreate()` kicks off a real Hilt graph + a background `sessionManager.rebuildStateAfterProcessRestart()`
 * coroutine - unnecessary here (this test builds its own [SessionManager] directly) and a source
 * of test-order-dependent flakiness (an unrelated leftover exception from that background
 * coroutine can surface as `UncaughtExceptionsBeforeTest` on whichever test happens to run next).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NotificationActionReceiverTest {

    private lateinit var db: AppDatabase
    private lateinit var profileRepository: ProfileRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var sessionManager: SessionManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        profileRepository = ProfileRepository(db.blockedProfileDao(), Dispatchers.Unconfined)
        sessionRepository = SessionRepository(db.blockedProfileSessionDao(), Dispatchers.Unconfined)
        val softUnblockRepository = SoftUnblockRepository(db, db.softUnblockDao(), Dispatchers.Unconfined)
        sessionManager = SessionManager(
            profileRepository = profileRepository,
            sessionRepository = sessionRepository,
            softUnblockRepository = softUnblockRepository,
            blockingStateHolder = BlockingStateHolder(),
            alarmScheduler = FoqosAlarmScheduler(context),
            settingsDataStore = SettingsDataStore(context),
            ioDispatcher = Dispatchers.Unconfined,
            sessionServiceController = NoOpSessionServiceController(),
            widgetRefresher = NoOpWidgetRefresher(),
        )
    }

    @After
    fun tearDown() {
        // SessionManager's init{} block fires a fire-and-forget `scope.launch` (on its own
        // internal scope, not this test's) to restore persisted state - with Dispatchers.Unconfined
        // it starts synchronously but resumes, after Room's real suspension point, on a thread this
        // test doesn't control. Our tests here are fast enough that db.close() can otherwise race
        // that resumption, leaving a "connection pool closed" exception to leak, uncaught, into
        // whichever *other* test happens to call `runTest` next (surfacing as that unrelated
        // test's `UncaughtExceptionsBeforeTest` failure) - giving it a brief moment to settle first
        // avoids that.
        Thread.sleep(200)
        db.close()
    }

    private suspend fun createProfile(disableBackgroundStops: Boolean = false): BlockedProfile =
        profileRepository.create(
            BlockedProfile(
                id = "profile-1",
                name = "Test Profile",
                strategyId = ManualBlockingStrategy.ID,
                disableBackgroundStops = disableBackgroundStops,
            ),
        )

    // --- shouldHandle: pure action-string filtering -----------------------------------------

    @Test
    fun `shouldHandle is true for the stop-session action`() {
        val intent = Intent(NotificationActionReceiver.ACTION_STOP_SESSION)
        assertThat(NotificationActionReceiver.shouldHandle(intent)).isTrue()
    }

    @Test
    fun `shouldHandle is false for an unrelated action`() {
        val intent = Intent("something.else")
        assertThat(NotificationActionReceiver.shouldHandle(intent)).isFalse()
    }

    @Test
    fun `shouldHandle is false for a null action`() {
        assertThat(NotificationActionReceiver.shouldHandle(Intent())).isFalse()
    }

    // --- handleStopAction: the actual stop path ----------------------------------------------

    @Test
    fun `handleStopAction ends the active session`() = runTest {
        val profile = createProfile()
        sessionManager.startProfile(profile.id)
        assertThat(sessionManager.activeSession.value).isNotNull()

        handleStopAction(sessionManager)

        assertThat(sessionManager.activeSession.value).isNull()
        assertThat(sessionRepository.activeSessionOnce()).isNull()
    }

    @Test
    fun `handleStopAction is refused when disableBackgroundStops is set`() = runTest {
        val profile = createProfile(disableBackgroundStops = true)
        sessionManager.startProfile(profile.id)
        assertThat(sessionManager.activeSession.value).isNotNull()

        handleStopAction(sessionManager)

        // The notification's underlying session must be left untouched - a background stop from
        // a `disableBackgroundStops` profile's notification is silently refused.
        assertThat(sessionManager.activeSession.value).isNotNull()
        assertThat(sessionRepository.activeSessionOnce()).isNotNull()
    }
}
