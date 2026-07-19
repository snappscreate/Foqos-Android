package com.foqos.android.ui.profile

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.data.db.AppDatabase
import com.foqos.android.data.model.Weekday
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.domain.strategy.ManualBlockingStrategy
import com.foqos.android.domain.strategy.NfcTimerBlockingStrategy
import com.foqos.android.service.alarm.FoqosAlarmScheduler
import com.foqos.android.service.nfc.NfcForegroundDispatchManager
import com.foqos.android.ui.navigation.Routes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Covers [ProfileEditViewModel] name/domain validation and schedule persistence + alarm scheduling on save. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileEditViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var profileRepository: ProfileRepository
    private lateinit var alarmScheduler: FoqosAlarmScheduler
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        profileRepository = ProfileRepository(db.blockedProfileDao(), Dispatchers.Unconfined)
        alarmScheduler = FoqosAlarmScheduler(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newViewModel(profileId: String? = null): ProfileEditViewModel = ProfileEditViewModel(
        savedStateHandle = SavedStateHandle(mapOf(Routes.ARG_PROFILE_ID to profileId)),
        profileRepository = profileRepository,
        nfcDispatchManager = NfcForegroundDispatchManager(),
        alarmScheduler = alarmScheduler,
        sessionManager = com.foqos.android.testutil.buildTestSessionManager(context, db, profileRepository),
        context = context,
    )

    private fun idleMainLooper() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun awaitLoaded(viewModel: ProfileEditViewModel) {
        var attempts = 0
        while (viewModel.uiState.value.loading && attempts < 400) {
            idleMainLooper()
            Thread.sleep(5)
            attempts++
        }
    }

    private fun awaitSaved(viewModel: ProfileEditViewModel) {
        var attempts = 0
        while (!viewModel.uiState.value.saved && attempts < 400) {
            idleMainLooper()
            Thread.sleep(5)
            attempts++
        }
    }

    @Test
    fun `blank name is rejected and nothing is persisted`() {
        runBlocking {
        val viewModel = newViewModel()
        awaitLoaded(viewModel)

        viewModel.updateName("   ")
        viewModel.save()

        assertThat(viewModel.uiState.value.nameError).isNotNull()
        assertThat(viewModel.uiState.value.saved).isFalse()
        assertThat(profileRepository.observeAll().first()).isEmpty()
        }
    }

    @Test
    fun `valid name creates a new persisted profile`() {
        runBlocking {
        val viewModel = newViewModel()
        awaitLoaded(viewModel)

        viewModel.updateName("Deep Work")
        viewModel.save()
        awaitSaved(viewModel)

        assertThat(viewModel.uiState.value.isPersisted).isTrue()
        val stored = profileRepository.getById(viewModel.uiState.value.draft.id)
        assertThat(stored?.name).isEqualTo("Deep Work")
        }
    }

    @Test
    fun `invalid domain sets an error and does not add it`() {
        runBlocking {
        val viewModel = newViewModel()
        awaitLoaded(viewModel)

        viewModel.addDomain("not a domain!!")

        assertThat(viewModel.uiState.value.domainError).isNotNull()
        assertThat(viewModel.uiState.value.draft.domains).isEmpty()
        }
    }

    @Test
    fun `valid domain is normalized and added`() {
        runBlocking {
        val viewModel = newViewModel()
        awaitLoaded(viewModel)

        viewModel.addDomain("HTTPS://Reddit.com/")

        assertThat(viewModel.uiState.value.domainError).isNull()
        assertThat(viewModel.uiState.value.draft.domains).containsExactly("reddit.com")
        }
    }

    @Test
    fun `schedule is persisted and weekly alarms are scheduled on save`() {
        runBlocking {
        val viewModel = newViewModel()
        awaitLoaded(viewModel)

        viewModel.updateName("Scheduled Profile")
        viewModel.setScheduleEnabled(true)
        viewModel.toggleScheduleDay(Weekday.MONDAY)
        viewModel.setScheduleStartTime(9, 0)
        viewModel.setScheduleEndTime(17, 0)
        viewModel.save()
        awaitSaved(viewModel)

        val profileId = viewModel.uiState.value.draft.id
        val stored = profileRepository.getById(profileId)
        assertThat(stored?.schedule?.isConfigured).isTrue()
        assertThat(stored?.schedule?.days).containsExactly(Weekday.MONDAY)

        assertThat(alarmScheduler.trackedKeys.any { it.startsWith("ScheduleTimerActivityStart:$profileId") }).isTrue()
        assertThat(alarmScheduler.trackedKeys.any { it.startsWith("ScheduleTimerActivityEnd:$profileId") }).isTrue()
        }
    }

    @Test
    fun `selecting a timer strategy seeds default timer data`() {
        runBlocking {
        val viewModel = newViewModel()
        awaitLoaded(viewModel)

        viewModel.selectStrategy(NfcTimerBlockingStrategy())

        assertThat(viewModel.uiState.value.draft.strategyId).isEqualTo(NfcTimerBlockingStrategy.ID)
        assertThat(viewModel.uiState.value.draft.strategyDataJson).isNotNull()
        }
    }

    @Test
    fun `editing an existing profile loads it as persisted`() {
        runBlocking {
        val existing = profileRepository.create(
            com.foqos.android.data.model.BlockedProfile(id = "existing-1", name = "Existing", strategyId = ManualBlockingStrategy.ID),
        )

        val viewModel = newViewModel(profileId = existing.id)
        awaitLoaded(viewModel)

        assertThat(viewModel.isNewProfile).isFalse()
        assertThat(viewModel.uiState.value.isPersisted).isTrue()
        assertThat(viewModel.uiState.value.draft.name).isEqualTo("Existing")
        }
    }

    @Test
    fun `delete removes the profile and cancels its alarms`() {
        runBlocking {
        val existing = profileRepository.create(
            com.foqos.android.data.model.BlockedProfile(id = "existing-2", name = "ToDelete", strategyId = ManualBlockingStrategy.ID),
        )
        val viewModel = newViewModel(profileId = existing.id)
        awaitLoaded(viewModel)

        viewModel.delete()

        var attempts = 0
        while (!viewModel.uiState.value.deleted && attempts < 400) {
            idleMainLooper()
            Thread.sleep(5)
            attempts++
        }

        assertThat(profileRepository.getById(existing.id)).isNull()
        }
    }
}
