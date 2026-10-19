package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.data.model.StrategyPauseTimerData

/**
 * Pick a pause duration in-app; scanning an NFC tag once pauses the session for that duration,
 * scanning again while paused cannot stop or extend the current pause.
 */
class NfcPauseTimerBlockingStrategy : BlockingStrategy {
    override val id: String = ID
    override val name: String = "NFC + Pause Timer"
    override val description: String =
        "Choose how long a pause should last. Scan an NFC tag once to pause. " +
            "Additional scans while paused do not end the session."
    override val category: StrategyCategory = StrategyCategory.FOREVER
    override val usesNfc: Boolean = true
    override val hasPauseMode: Boolean = true

    override fun startRequest(profile: BlockedProfile): StrategyUiRequest =
        StrategyUiRequest.PickPauseDuration(StrategyPauseTimerData.fromJsonOrNull(profile.strategyDataJson))

    override suspend fun onStartInput(
        profile: BlockedProfile,
        input: StrategyInput,
        host: StrategyHost,
    ): SessionOutcome {
        if (input !is StrategyInput.PauseData) {
            host.emitError("No pause duration specified for this profile")
            return SessionOutcome.Rejected("No pause duration specified for this profile")
        }
        val session = host.createSession(profile, tag = ID, forceStarted = false)
        return SessionOutcome.Started(session)
    }

    override fun stopRequest(profile: BlockedProfile, session: BlockedProfileSession): StrategyUiRequest =
        StrategyUiRequest.ScanNfc(StrategyUiRequest.Purpose.STOP)

    override suspend fun onStopInput(
        profile: BlockedProfile,
        session: BlockedProfileSession,
        input: StrategyInput,
        host: StrategyHost,
    ): SessionOutcome {
        if (input !is StrategyInput.Code) {
            host.emitError("Expected a scanned NFC tag")
            return SessionOutcome.Rejected("Expected a scanned NFC tag")
        }

        val rejection = PhysicalUnlockRules.validateAnyCodeOrPhysical(
            profile = profile,
            code = input.value,
            type = PhysicalUnblockType.NFC,
            mismatchMessage =
                "This NFC tag is not allowed to unblock this profile. Physical unblock setting is on for this profile",
        )
        if (rejection != null) {
            host.emitError(rejection)
            return SessionOutcome.Rejected(rejection)
        }

        if (session.isPauseActive()) {
            val reason = "Pause is already active; scanning again cannot stop the session"
            host.emitError(reason)
            return SessionOutcome.Rejected(reason)
        }
        val pauseDurationMinutes =
            StrategyPauseTimerData.fromJsonOrNull(profile.strategyDataJson).pauseDurationInMinutes
        host.beginPause(session, pauseDurationMinutes)
        return SessionOutcome.Paused
    }

    companion object {
        const val ID = "NFCPauseTimerBlockingStrategy"
    }
}
