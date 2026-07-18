package com.foqos.android.domain.softunblock

import com.foqos.android.data.model.SoftUnblockSessionState
import java.time.Instant

/**
 * Pure helpers around [SoftUnblockSessionState]'s allowance window, mirroring iOS
 * `SoftUnblockSessionState` / `SoftUnblockGrantStore` semantics (used by the Phase 4
 * `SessionManager` to decide whether a soft-unblock grant can be issued).
 */
object SoftUnblockAllowanceCalculator {

    /** Remaining unblock count at [now], after applying an allowance reset if one is due. */
    fun remainingCount(state: SoftUnblockSessionState, now: Instant): Int =
        stateAfterReset(state, now).remainingUnblockCount

    /** Whether [state]'s allowance window is due for a reset at [now]. */
    fun shouldReset(state: SoftUnblockSessionState, now: Instant): Boolean {
        val nextReset = state.nextAllowanceResetAt ?: return false
        if (state.allowanceResetIntervalInHours == null) return false
        return !now.isBefore(nextReset)
    }

    /**
     * [state] with its allowance window advanced and usage count cleared if a reset is due at
     * [now]; returns [state] unchanged otherwise (mirrors
     * [SoftUnblockSessionState.resetAllowanceIfNeeded]).
     */
    fun nextStateAfterReset(state: SoftUnblockSessionState, now: Instant): SoftUnblockSessionState =
        state.resetAllowanceIfNeeded(now)

    /** Whether a new soft-unblock grant can be issued at [now] (allowance remaining, after reset). */
    fun canIssueGrant(state: SoftUnblockSessionState, now: Instant): Boolean =
        stateAfterReset(state, now).remainingUnblockCount > 0

    private fun stateAfterReset(state: SoftUnblockSessionState, now: Instant): SoftUnblockSessionState =
        if (shouldReset(state, now)) nextStateAfterReset(state, now) else state
}
