package com.foqos.android.ui.home

import android.content.Context
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [HomeViewModel]'s profile-list mapping and start/stop dispatch through a *real*
 * [SessionManager] (Robolectric-backed, same pattern as [com.foqos.android.domain.session.SessionManagerTest])
 * rather than a fake, since [SessionManager] isn't designed to be mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var profileRepository: ProfileRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var sessionManager: SessionManager
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        profileRepository = ProfileRepository(db.blockedProfileDao(), Dispatchers.Unconfined)
        sessionRepository = SessionRepository(db.blockedProfileSessionDao(), Dispatchers.Unconfined)
        val softUnblockRepository = SoftUnblockRepository(db, db.softUnblockDao(), Dispatchers.Unconfined)
        settingsDataStore = SettingsDataStore(context)
        sessionManager = SessionManager(
            profileRepository = profileRepository,
            sessionRepository = sessionRepository,
            softUnblockRepository = softUnblockRepository,
            blockingStateHolder = BlockingStateHolder(),
            alarmScheduler = FoqosAlarmScheduler(context),
            settingsDataStore = settingsDataStore,
            ioDispatcher = Dispatchers.Unconfined,
            sessionServiceController = NoOpSessionServiceController(),
            widgetRefresher = NoOpWidgetRefresher(),
        )
        viewModel = HomeViewModel(profileRepository, sessionRepository, sessionManager, settingsDataStore, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createProfile(name: String = "Focus"): BlockedProfile = profileRepository.create(
        BlockedProfile(id = "profile-${name.hashCode()}", name = name, strategyId = ManualBlockingStrategy.ID),
    )

    @Test
    fun `uiState reflects stored profiles`() {
        runBlocking {
        createProfile("Deep Work")
        createProfile("Reading")

        val state = awaitState { it.profiles.size == 2 }
        assertThat(state.profiles.map { it.name }).containsExactly("Deep Work", "Reading")
        }
    }

    @Test
    fun `startProfile starts a manual session that shows up as active`() {
        runBlocking {
        val profile = createProfile()

        viewModel.startProfile(profile.id)

        val state = awaitState { it.activeSession != null }
        assertThat(state.activeSession?.profileId).isEqualTo(profile.id)
        assertThat(state.activeProfile?.id).isEqualTo(profile.id)
        }
    }

    @Test
    fun `stopActiveSession ends the active session`() {
        runBlocking {
        val profile = createProfile()
        viewModel.startProfile(profile.id)
        awaitState { it.activeSession != null }

        viewModel.stopActiveSession()

        val state = awaitState { it.activeSession == null }
        assertThat(state.activeSession).isNull()
        }
    }

    @Test
    fun `duplicateProfile clones the profile`() {
        runBlocking {
        val profile = createProfile("Original")

        viewModel.duplicateProfile(profile.id)

        val state = awaitState { it.profiles.size == 2 }
        assertThat(state.profiles.map { it.name }).containsExactly("Original", "Original copy")
        }
    }

    @Test
    fun `deleteProfile removes it from the list`() {
        runBlocking {
        val profile = createProfile()
        awaitState { it.profiles.size == 1 }

        viewModel.deleteProfile(profile.id)

        val state = awaitState { it.profiles.isEmpty() }
        assertThat(state.profiles).isEmpty()
        }
    }

    @Test
    fun `refreshPermissions reports missing when neither accessibility nor usage access is granted`() {
        runBlocking {
        // Robolectric's shadow AppOpsManager defaults unset ops to MODE_ALLOWED - deny it
        // explicitly here (same as PermissionChecksTest) so this exercises the "denied" path.
        val appOps = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        org.robolectric.Shadows.shadowOf(appOps).setMode(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            ApplicationProvider.getApplicationContext<Context>().packageName,
            android.app.AppOpsManager.MODE_ERRORED,
        )

        viewModel.refreshPermissions()
        assertThat(viewModel.uiState.value.permissionsMissing).isTrue()
        }
    }

    private fun awaitState(predicate: (HomeViewModel.UiState) -> Boolean): HomeViewModel.UiState {
        var state = viewModel.uiState.value
        var attempts = 0
        while (!predicate(state) && attempts < 400) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
            state = viewModel.uiState.value
            attempts++
        }
        return state
    }
}
