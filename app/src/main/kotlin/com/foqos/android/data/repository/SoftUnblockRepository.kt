package com.foqos.android.data.repository

import androidx.room.withTransaction
import com.foqos.android.data.db.AppDatabase
import com.foqos.android.data.db.dao.SoftUnblockDao
import com.foqos.android.data.model.SoftUnblockGrant
import com.foqos.android.data.model.SoftUnblockSessionState
import com.foqos.android.data.model.toDomain
import com.foqos.android.data.model.toEntity
import com.foqos.android.di.IoDispatcher
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Soft-unblock grant issuance + allowance bookkeeping, backed by [SoftUnblockDao]. */
@Singleton
class SoftUnblockRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: SoftUnblockDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Initializes (or replaces) the allowance state for a soft-unblock session. */
    suspend fun beginSession(state: SoftUnblockSessionState) = withContext(ioDispatcher) {
        dao.upsertSessionState(state.toEntity())
    }

    suspend fun getSessionState(sessionId: String): SoftUnblockSessionState? = withContext(ioDispatcher) {
        dao.getSessionState(sessionId)?.toDomain()
    }

    fun activeGrantsFlow(sessionId: String, now: Instant = Instant.now()): Flow<List<SoftUnblockGrant>> =
        dao.activeGrantsAt(sessionId, now.toEpochMilli()).map { entities -> entities.map { it.toDomain() } }

    suspend fun activeGrantsOnce(sessionId: String, now: Instant = Instant.now()): List<SoftUnblockGrant> =
        withContext(ioDispatcher) {
            dao.activeGrantsAtOnce(sessionId, now.toEpochMilli()).map { it.toDomain() }
        }

    /**
     * Attempts to issue a grant for [packageName] within [sessionId]'s soft-unblock allowance, as
     * a single transaction: checks the remaining allowance, inserts the grant and increments the
     * used count atomically. Returns the created grant, or null if the session has no allowance
     * state or its allowance is already exhausted.
     */
    suspend fun issueGrant(
        sessionId: String,
        profileId: String,
        packageName: String,
        accessDurationInMinutes: Int,
        now: Instant = Instant.now(),
    ): SoftUnblockGrant? = withContext(ioDispatcher) {
        database.withTransaction {
            val state = dao.getSessionState(sessionId)?.toDomain() ?: return@withTransaction null
            if (state.remainingUnblockCount <= 0) return@withTransaction null

            val grant = SoftUnblockGrant(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                profileId = profileId,
                packageName = packageName,
                createdAt = now,
                expiresAt = now.plus(Duration.ofMinutes(accessDurationInMinutes.toLong())),
            )

            dao.insertGrant(grant.toEntity())
            dao.upsertSessionState(state.copy(usedUnblockCount = state.usedUnblockCount + 1).toEntity())
            grant
        }
    }

    suspend fun expireGrants(now: Instant = Instant.now()) = withContext(ioDispatcher) {
        dao.deleteExpiredGrants(now.toEpochMilli())
    }

    /** Resets the session's allowance window/count if it is due, persisting the change if so. */
    suspend fun resetAllowanceIfDue(sessionId: String, now: Instant = Instant.now()): SoftUnblockSessionState? =
        withContext(ioDispatcher) {
            database.withTransaction {
                val current = dao.getSessionState(sessionId)?.toDomain() ?: return@withTransaction null
                val updated = current.resetAllowanceIfNeeded(now)
                if (updated != current) {
                    dao.upsertSessionState(updated.toEntity())
                }
                updated
            }
        }

    suspend fun clearForSession(sessionId: String) = withContext(ioDispatcher) {
        dao.clearForSession(sessionId)
    }
}
