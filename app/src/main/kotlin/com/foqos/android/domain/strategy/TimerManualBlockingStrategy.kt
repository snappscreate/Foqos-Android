package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.StrategyTimerData
import java.time.Instant

/**
 * Pick a duration in-app, stop early with the in-app Stop button - mirrors iOS
 * `ShortcutTimerBlockingStrategy` (kept as its exact iOS id for profile JSON compatibility).
 */
class TimerManualBlockingStrategy : BlockingStrategy {
    override val id: String = ID
    override val name: String = "Timer + Manual"
    override val description: String = "Choose how long blocking should last. Stop early with the Stop button."
    override val category: StrategyCategory = StrategyCategory.TIMERS
    override val hasTimer: Boolean = true
    override val startsManually: Boolean = true

    override fun startRequest(profile: BlockedProfile): StrategyUiRequest =
        StrategyUiRequest.PickTimerDuration(StrategyTimerData.fromJsonOrNull(profile.strategyDataJson))

    override suspend fun onStartInput(
        profile: BlockedProfile,
        input: StrategyInput,
        host: StrategyHost,
    ): SessionOutcome {
        if (input !is StrategyInput.TimerData) {
            host.emitError("No timer duration specified for this profile")
            return SessionOutcome.Rejected("No timer duration specified for this profile")
        }
        // Mirrors iOS, which tags the session with the profile's own strategy id (falling back to
        // Manual) rather than this strategy's id.
        val session = host.createSession(
            profile,
            tag = profile.strategyId ?: ManualBlockingStrategy.ID,
            forceStarted = false,
        )
        return SessionOutcome.Started(session)
    }

    override fun stopRequest(profile: BlockedProfile, session: BlockedProfileSession): StrategyUiRequest =
        StrategyUiRequest.None

    override suspend fun onStopInput(
        profile: BlockedProfile,
        session: BlockedProfileSession,
        input: StrategyInput,
        host: StrategyHost,
    ): SessionOutcome {
        host.endSession(session, Instant.now())
        return SessionOutcome.Stopped
    }

    companion object {
        const val ID = "ShortcutTimerBlockingStrategy"
    }
}
