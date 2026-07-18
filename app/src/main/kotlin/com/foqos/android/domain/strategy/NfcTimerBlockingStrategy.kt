package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.data.model.StrategyTimerData
import java.time.Instant

/**
 * Pick a duration in-app, session auto-ends at the timer (scheduled by the Phase 4
 * `SessionManager`), stop early by scanning any NFC tag - mirrors iOS `NFCTimerBlockingStrategy`.
 */
class NfcTimerBlockingStrategy : BlockingStrategy {
    override val id: String = ID
    override val name: String = "NFC + Timer"
    override val description: String =
        "Choose how long blocking should last. To stop early, scan any NFC tag. " +
            "Use Strict Unlocks if you want only selected tags to work."
    override val category: StrategyCategory = StrategyCategory.TIMERS
    override val usesNfc: Boolean = true
    override val hasTimer: Boolean = true

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
        // The chosen duration is persisted onto the profile by the UI layer before this is
        // called (so it's available in the background, e.g. shortcuts) - the strategy itself only
        // needs to create the session; the timer alarm is scheduled by the SessionManager from
        // the strategy's flags/data.
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
            host.emitError("Expected a scanned NFC tag to stop this profile")
            return SessionOutcome.Rejected("Expected a scanned NFC tag to stop this profile")
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

        host.endSession(session, Instant.now())
        return SessionOutcome.Stopped
    }

    companion object {
        const val ID = "NFCTimerBlockingStrategy"
    }
}
