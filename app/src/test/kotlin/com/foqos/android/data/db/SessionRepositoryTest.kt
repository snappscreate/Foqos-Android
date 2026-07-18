package com.foqos.android.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.data.repository.SessionRepository
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var profileRepository: ProfileRepository
    private lateinit var sessionRepository: SessionRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        profileRepository = ProfileRepository(db.blockedProfileDao(), Dispatchers.Unconfined)
        sessionRepository = SessionRepository(db.blockedProfileSessionDao(), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createProfile(id: String): BlockedProfile =
        profileRepository.create(BlockedProfile(id = id, name = "Profile $id", blockedPackages = listOf("a")))

    @Test
    fun `deleting a profile cascades to its sessions`() = runTest {
        val profile = createProfile("profile-1")
        val session = sessionRepository.startSession(profile, tag = "NFCBlockingStrategy")

        assertThat(sessionRepository.getById(session.id)).isNotNull()

        profileRepository.delete(profile.id)

        assertThat(sessionRepository.getById(session.id)).isNull()
    }

    @Test
    fun `active session query picks the most recently started open session`() = runTest {
        val profile = createProfile("profile-1")

        val older = sessionRepository.startSession(profile, tag = "tag-a", at = Instant.parse("2026-01-01T10:00:00Z"))
        val newer = sessionRepository.startSession(profile, tag = "tag-b", at = Instant.parse("2026-01-01T12:00:00Z"))

        val active = sessionRepository.activeSessionOnce()
        assertThat(active).isNotNull()
        assertThat(active!!.id).isEqualTo(newer.id)
        assertThat(active.id).isNotEqualTo(older.id)
    }

    @Test
    fun `active session query ignores ended sessions`() = runTest {
        val profile = createProfile("profile-1")

        val ended = sessionRepository.startSession(profile, tag = "tag-a", at = Instant.parse("2026-01-01T10:00:00Z"))
        sessionRepository.endSession(ended.id, at = Instant.parse("2026-01-01T10:30:00Z"))

        val stillOpen = sessionRepository.startSession(profile, tag = "tag-b", at = Instant.parse("2026-01-01T09:00:00Z"))

        val active = sessionRepository.activeSessionOnce()
        assertThat(active).isNotNull()
        assertThat(active!!.id).isEqualTo(stillOpen.id)
    }

    @Test
    fun `observeActiveSession flow reflects the active session`() = runTest {
        val profile = createProfile("profile-1")
        val session = sessionRepository.startSession(profile, tag = "tag-a")

        val observed = sessionRepository.observeActiveSession().first()
        assertThat(observed).isNotNull()
        assertThat(observed!!.id).isEqualTo(session.id)

        sessionRepository.endSession(session.id)

        val afterEnd = sessionRepository.observeActiveSession().first()
        assertThat(afterEnd).isNull()
    }

    @Test
    fun `endBreak accumulates used break duration when multiple breaks are allowed`() = runTest {
        val profile = createProfile("profile-1")
        val session = sessionRepository.startSession(profile, tag = "tag-a", at = Instant.parse("2026-01-01T09:00:00Z"))

        sessionRepository.startBreak(session.id, allowMultipleBreaks = true, at = Instant.parse("2026-01-01T09:00:00Z"))
        sessionRepository.endBreak(
            session.id,
            allowMultipleBreaks = true,
            totalBreakAllowanceInSeconds = 900,
            at = Instant.parse("2026-01-01T09:05:00Z"),
        )

        val reloaded = sessionRepository.getById(session.id)
        assertThat(reloaded!!.usedBreakDurationInSeconds).isEqualTo(300)
    }

    @Test
    fun `endBreak caps used duration at the total allowance`() = runTest {
        val profile = createProfile("profile-1")
        val session = sessionRepository.startSession(profile, tag = "tag-a", at = Instant.parse("2026-01-01T09:00:00Z"))

        sessionRepository.startBreak(session.id, allowMultipleBreaks = true, at = Instant.parse("2026-01-01T09:00:00Z"))
        sessionRepository.endBreak(
            session.id,
            allowMultipleBreaks = true,
            totalBreakAllowanceInSeconds = 60,
            at = Instant.parse("2026-01-01T09:05:00Z"),
        )

        val reloaded = sessionRepository.getById(session.id)
        assertThat(reloaded!!.usedBreakDurationInSeconds).isEqualTo(60)
    }
}
