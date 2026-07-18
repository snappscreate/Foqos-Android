package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.SoftUnblockStrategyData
import java.time.Instant

/**
 * The operations a [BlockingStrategy] calls into to actually persist session state. Implemented
 * by the Phase 4 `SessionManager`, which reacts to these calls by persisting sessions, publishing
 * blocking state, scheduling alarms, etc. Strategies never touch persistence or Android
 * frameworks directly - they only orchestrate calls through this interface.
 */
interface StrategyHost {

    /** Creates and persists a new active session for [profile] started with [tag]. */
    suspend fun createSession(profile: BlockedProfile, tag: String, forceStarted: Boolean): BlockedProfileSession

    /** Ends [session] at [at]. */
    suspend fun endSession(session: BlockedProfileSession, at: Instant)

    /** Puts [session] into a paused state for [durationMinutes]. */
    suspend fun beginPause(session: BlockedProfileSession, durationMinutes: Int)

    /** Starts soft-unblock allowance tracking for [session], configured by [data]. */
    suspend fun beginSoftUnblockSession(
        profile: BlockedProfile,
        session: BlockedProfileSession,
        data: SoftUnblockStrategyData,
    )

    /** Surfaces a user-facing error message (e.g. a rejected stop attempt). */
    fun emitError(message: String)
}
