package com.foqos.android.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.data.model.SoftUnblockSessionState
import com.foqos.android.data.repository.SoftUnblockRepository
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class SoftUnblockRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SoftUnblockRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = SoftUnblockRepository(db, db.softUnblockDao(), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val sessionId = "session-1"
    private val profileId = "profile-1"

    private suspend fun beginSessionWithAllowance(maximumUnblockCount: Int) {
        repository.beginSession(
            SoftUnblockSessionState(
                sessionId = sessionId,
                profileId = profileId,
                maximumUnblockCount = maximumUnblockCount,
                allowanceResetIntervalInHours = null,
                allowanceWindowStartedAt = Instant.parse("2026-01-01T00:00:00Z"),
                nextAllowanceResetAt = null,
                usedUnblockCount = 0,
            ),
        )
    }

    @Test
    fun `issueGrant decrements the remaining allowance`() = runTest {
        beginSessionWithAllowance(maximumUnblockCount = 2)

        val grant = repository.issueGrant(
            sessionId = sessionId,
            profileId = profileId,
            packageName = "com.instagram.android",
            accessDurationInMinutes = 5,
            now = Instant.parse("2026-01-01T01:00:00Z"),
        )

        assertThat(grant).isNotNull()
        assertThat(grant!!.packageName).isEqualTo("com.instagram.android")
        assertThat(grant.expiresAt).isEqualTo(Instant.parse("2026-01-01T01:05:00Z"))

        val state = repository.getSessionState(sessionId)
        assertThat(state).isNotNull()
        assertThat(state!!.usedUnblockCount).isEqualTo(1)
        assertThat(state.remainingUnblockCount).isEqualTo(1)
    }

    @Test
    fun `issueGrant refuses once the allowance is exhausted`() = runTest {
        beginSessionWithAllowance(maximumUnblockCount = 1)

        val first = repository.issueGrant(sessionId, profileId, "com.a", 5)
        val second = repository.issueGrant(sessionId, profileId, "com.b", 5)

        assertThat(first).isNotNull()
        assertThat(second).isNull()

        val state = repository.getSessionState(sessionId)
        assertThat(state!!.remainingUnblockCount).isEqualTo(0)
        assertThat(state.usedUnblockCount).isEqualTo(1)
    }

    @Test
    fun `issueGrant returns null when no session state exists`() = runTest {
        val grant = repository.issueGrant("unknown-session", profileId, "com.a", 5)
        assertThat(grant).isNull()
    }

    @Test
    fun `activeGrantsOnce excludes expired grants`() = runTest {
        beginSessionWithAllowance(maximumUnblockCount = 2)

        repository.issueGrant(
            sessionId,
            profileId,
            "com.a",
            accessDurationInMinutes = 1,
            now = Instant.parse("2026-01-01T00:00:00Z"),
        )

        val activeSoon = repository.activeGrantsOnce(sessionId, now = Instant.parse("2026-01-01T00:00:30Z"))
        assertThat(activeSoon).hasSize(1)

        val activeLater = repository.activeGrantsOnce(sessionId, now = Instant.parse("2026-01-01T00:05:00Z"))
        assertThat(activeLater).isEmpty()
    }

    @Test
    fun `clearForSession removes grants and allowance state`() = runTest {
        beginSessionWithAllowance(maximumUnblockCount = 2)
        repository.issueGrant(sessionId, profileId, "com.a", 5)

        repository.clearForSession(sessionId)

        assertThat(repository.getSessionState(sessionId)).isNull()
        assertThat(repository.activeGrantsOnce(sessionId)).isEmpty()
    }

    @Test
    fun `resetAllowanceIfDue resets used count once the reset time has passed`() = runTest {
        repository.beginSession(
            SoftUnblockSessionState(
                sessionId = sessionId,
                profileId = profileId,
                maximumUnblockCount = 1,
                allowanceResetIntervalInHours = 6,
                allowanceWindowStartedAt = Instant.parse("2026-01-01T00:00:00Z"),
                nextAllowanceResetAt = Instant.parse("2026-01-01T06:00:00Z"),
                usedUnblockCount = 1,
            ),
        )

        val notYetDue = repository.resetAllowanceIfDue(sessionId, now = Instant.parse("2026-01-01T05:00:00Z"))
        assertThat(notYetDue!!.usedUnblockCount).isEqualTo(1)

        val due = repository.resetAllowanceIfDue(sessionId, now = Instant.parse("2026-01-01T07:00:00Z"))
        assertThat(due!!.usedUnblockCount).isEqualTo(0)
        assertThat(due.nextAllowanceResetAt).isEqualTo(Instant.parse("2026-01-01T12:00:00Z"))
    }
}
