package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.SoftUnblockStrategyData
import java.time.Instant
import java.util.UUID

/** A recording [StrategyHost] test double - no persistence, just captures the calls made to it. */
class FakeStrategyHost : StrategyHost {
    var createdSessions: MutableList<BlockedProfileSession> = mutableListOf()
    var endedSessions: MutableList<Pair<BlockedProfileSession, Instant>> = mutableListOf()
    var pauseCalls: MutableList<Pair<BlockedProfileSession, Int>> = mutableListOf()
    var softUnblockCalls: MutableList<Pair<BlockedProfileSession, SoftUnblockStrategyData>> = mutableListOf()
    var errors: MutableList<String> = mutableListOf()

    override suspend fun createSession(profile: BlockedProfile, tag: String, forceStarted: Boolean): BlockedProfileSession {
        val session = BlockedProfileSession(
            id = UUID.randomUUID().toString(),
            profileId = profile.id,
            tag = tag,
            startTime = Instant.now(),
            forceStarted = forceStarted,
        )
        createdSessions.add(session)
        return session
    }

    override suspend fun endSession(session: BlockedProfileSession, at: Instant) {
        endedSessions.add(session to at)
    }

    override suspend fun beginPause(session: BlockedProfileSession, durationMinutes: Int) {
        pauseCalls.add(session to durationMinutes)
    }

    override suspend fun beginSoftUnblockSession(
        profile: BlockedProfile,
        session: BlockedProfileSession,
        data: SoftUnblockStrategyData,
    ) {
        softUnblockCalls.add(session to data)
    }

    override fun emitError(message: String) {
        errors.add(message)
    }
}
