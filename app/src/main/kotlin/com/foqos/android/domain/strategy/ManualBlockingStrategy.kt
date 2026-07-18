package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import java.time.Instant

/** Start and stop entirely from in-app buttons - mirrors iOS `ManualBlockingStrategy`. */
class ManualBlockingStrategy : BlockingStrategy {
    override val id: String = ID
    override val name: String = "Manual"
    override val description: String = "Start and stop in the app. You do not need to scan anything."
    override val category: StrategyCategory = StrategyCategory.EASY_TO_START
    override val startsManually: Boolean = true

    override fun startRequest(profile: BlockedProfile): StrategyUiRequest = StrategyUiRequest.None

    override suspend fun onStartInput(
        profile: BlockedProfile,
        input: StrategyInput,
        host: StrategyHost,
    ): SessionOutcome {
        val session = host.createSession(profile, tag = ID, forceStarted = false)
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
        const val ID = "ManualBlockingStrategy"
    }
}
