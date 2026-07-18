package com.foqos.android.ui.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.data.db.AppDatabase
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.data.repository.SessionRepository
import com.foqos.android.domain.strategy.ManualBlockingStrategy
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Covers [ExportViewModel]'s profile-selection defaulting and CSV building for the selected subset. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var profileRepository: ProfileRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var viewModel: ExportViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        profileRepository = ProfileRepository(db.blockedProfileDao(), Dispatchers.Unconfined)
        sessionRepository = SessionRepository(db.blockedProfileSessionDao(), Dispatchers.Unconfined)
        viewModel = ExportViewModel(profileRepository, sessionRepository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun awaitProfilesLoaded(count: Int) {
        var attempts = 0
        while (viewModel.uiState.value.profiles.size < count && attempts < 400) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
            attempts++
        }
    }

    @Test
    fun `all profiles are pre-selected on first load`() {
        runBlocking {
        profileRepository.create(BlockedProfile(id = "p1", name = "Work", strategyId = ManualBlockingStrategy.ID))
        profileRepository.create(BlockedProfile(id = "p2", name = "Reading", strategyId = ManualBlockingStrategy.ID))
        awaitProfilesLoaded(2)

        assertThat(viewModel.uiState.value.selectedProfileIds).containsExactly("p1", "p2")
        }
    }

    @Test
    fun `csv only includes sessions for the selected profiles`() {
        runBlocking {
        val work = profileRepository.create(BlockedProfile(id = "p1", name = "Work", strategyId = ManualBlockingStrategy.ID))
        val reading = profileRepository.create(BlockedProfile(id = "p2", name = "Reading", strategyId = ManualBlockingStrategy.ID))
        awaitProfilesLoaded(2)

        val workSession = sessionRepository.startSession(work, tag = ManualBlockingStrategy.ID, at = Instant.parse("2024-01-01T10:00:00Z"))
        sessionRepository.endSession(workSession.id, Instant.parse("2024-01-01T10:30:00Z"))
        val readingSession = sessionRepository.startSession(reading, tag = ManualBlockingStrategy.ID, at = Instant.parse("2024-01-02T10:00:00Z"))
        sessionRepository.endSession(readingSession.id, Instant.parse("2024-01-02T10:15:00Z"))

        viewModel.toggleProfile(reading.id) // deselect Reading, keep only Work selected

        val csv = viewModel.buildCsv()

        assertThat(csv).contains("Work")
        assertThat(csv).doesNotContain("Reading")
        assertThat(csv.lines().size).isEqualTo(2) // header + one Work session row
        }
    }

    @Test
    fun `sort order is respected`() {
        runBlocking {
        val profile = profileRepository.create(BlockedProfile(id = "p1", name = "Work", strategyId = ManualBlockingStrategy.ID))
        awaitProfilesLoaded(1)

        val first = sessionRepository.startSession(profile, tag = ManualBlockingStrategy.ID, at = Instant.parse("2024-01-01T10:00:00Z"))
        sessionRepository.endSession(first.id, Instant.parse("2024-01-01T10:30:00Z"))
        val second = sessionRepository.startSession(profile, tag = ManualBlockingStrategy.ID, at = Instant.parse("2024-01-05T10:00:00Z"))
        sessionRepository.endSession(second.id, Instant.parse("2024-01-05T10:30:00Z"))

        viewModel.setAscending(false)
        val csv = viewModel.buildCsv()
        val dataLines = csv.lines().drop(1)

        assertThat(dataLines.first()).contains(second.id)
        assertThat(dataLines.last()).contains(first.id)
        }
    }
}
