package com.foqos.android.data.repository

import com.foqos.android.data.db.dao.BlockedProfileSessionDao
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.toDomain
import com.foqos.android.data.model.toEntity
import com.foqos.android.di.IoDispatcher
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Session lifecycle (start/end/break/pause) + history queries, backed by [BlockedProfileSessionDao]. */
@Singleton
class SessionRepository @Inject constructor(
    private val dao: BlockedProfileSessionDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun startSession(
        profile: BlockedProfile,
        tag: String,
        forceStarted: Boolean = false,
        at: Instant = Instant.now(),
    ): BlockedProfileSession = withContext(ioDispatcher) {
        val session = BlockedProfileSession(
            id = UUID.randomUUID().toString(),
            profileId = profile.id,
            tag = tag,
            startTime = at,
            forceStarted = forceStarted,
        )
        dao.upsert(session.toEntity())
        session
    }

    suspend fun endSession(sessionId: String, at: Instant = Instant.now()) = withContext(ioDispatcher) {
        val entity = dao.getById(sessionId) ?: return@withContext
        dao.upsert(entity.copy(endTime = at.toEpochMilli()))
    }

    /**
     * Starts a break. When [allowMultipleBreaks] is true this (re)opens a new break window;
     * otherwise it records the profile's single one-shot break start time.
     */
    suspend fun startBreak(
        sessionId: String,
        allowMultipleBreaks: Boolean,
        at: Instant = Instant.now(),
    ) = withContext(ioDispatcher) {
        val entity = dao.getById(sessionId) ?: return@withContext
        val updated = if (allowMultipleBreaks) {
            entity.copy(breakStartTime = at.toEpochMilli(), breakEndTime = null)
        } else {
            entity.copy(breakStartTime = at.toEpochMilli())
        }
        dao.upsert(updated)
    }

    /**
     * Ends the currently active break, accumulating [usedBreakDurationInSeconds] up to
     * [totalBreakAllowanceInSeconds] when [allowMultipleBreaks] is true, or recording the single
     * completed break duration otherwise.
     */
    suspend fun endBreak(
        sessionId: String,
        allowMultipleBreaks: Boolean,
        totalBreakAllowanceInSeconds: Long,
        at: Instant = Instant.now(),
    ) = withContext(ioDispatcher) {
        val entity = dao.getById(sessionId) ?: return@withContext
        val breakStart = entity.breakStartTime
        val elapsedSeconds = if (breakStart != null) {
            maxOf(0L, at.toEpochMilli() - breakStart) / 1000L
        } else {
            0L
        }

        val updatedUsedDuration = if (allowMultipleBreaks) {
            minOf(totalBreakAllowanceInSeconds, entity.usedBreakDurationInSeconds + elapsedSeconds)
        } else {
            elapsedSeconds
        }

        dao.upsert(
            entity.copy(
                breakEndTime = at.toEpochMilli(),
                usedBreakDurationInSeconds = updatedUsedDuration,
            ),
        )
    }

    suspend fun startPause(sessionId: String, at: Instant = Instant.now()) = withContext(ioDispatcher) {
        val entity = dao.getById(sessionId) ?: return@withContext
        dao.upsert(entity.copy(pauseStartTime = at.toEpochMilli(), pauseEndTime = null))
    }

    suspend fun endPause(sessionId: String, at: Instant = Instant.now()) = withContext(ioDispatcher) {
        val entity = dao.getById(sessionId) ?: return@withContext
        dao.upsert(entity.copy(pauseEndTime = at.toEpochMilli()))
    }

    fun observeActiveSession(): Flow<BlockedProfileSession?> =
        dao.observeActiveSession().map { it?.toDomain() }

    suspend fun activeSessionOnce(): BlockedProfileSession? = withContext(ioDispatcher) {
        dao.activeSessionOnce()?.toDomain()
    }

    suspend fun getById(sessionId: String): BlockedProfileSession? = withContext(ioDispatcher) {
        dao.getById(sessionId)?.toDomain()
    }

    fun sessionsForProfile(profileId: String): Flow<List<BlockedProfileSession>> =
        dao.sessionsForProfile(profileId).map { entities -> entities.map { it.toDomain() } }

    suspend fun recentInactiveSessions(limit: Int = 50): List<BlockedProfileSession> = withContext(ioDispatcher) {
        dao.recentInactiveSessions(limit).map { it.toDomain() }
    }

    /** Sessions whose start time falls within [from, to) - used by the insights aggregators. */
    suspend fun sessionsBetween(from: Instant, to: Instant): List<BlockedProfileSession> = withContext(ioDispatcher) {
        dao.sessionsBetween(from.toEpochMilli(), to.toEpochMilli()).map { it.toDomain() }
    }

    suspend fun delete(sessionId: String) = withContext(ioDispatcher) {
        dao.deleteById(sessionId)
    }

    suspend fun deleteAllForProfile(profileId: String) = withContext(ioDispatcher) {
        dao.deleteAllForProfile(profileId)
    }
}
