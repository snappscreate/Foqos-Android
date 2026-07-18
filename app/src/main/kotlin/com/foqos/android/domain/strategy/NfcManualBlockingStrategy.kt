package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.PhysicalUnblockType
import java.time.Instant

/**
 * Start from the app, stop by scanning any NFC tag (or a configured physical unblock item) -
 * mirrors iOS `NFCManualBlockingStrategy`.
 */
class NfcManualBlockingStrategy : BlockingStrategy {
    override val id: String = ID
    override val name: String = "NFC + Manual"
    override val description: String =
        "Start in the app. To stop, scan any NFC tag. Use Strict Unlocks if you want only selected tags to work."
    override val category: StrategyCategory = StrategyCategory.EASY_TO_START
    override val usesNfc: Boolean = true
    override val startsManually: Boolean = true

    override fun startRequest(profile: BlockedProfile): StrategyUiRequest = StrategyUiRequest.None

    override suspend fun onStartInput(
        profile: BlockedProfile,
        input: StrategyInput,
        host: StrategyHost,
    ): SessionOutcome {
        // Manually starting sessions - since nothing was scanned to start, there is no tag to
        // store for the session, so the Manual strategy's id is used as a placeholder (matches
        // iOS behavior exactly).
        val session = host.createSession(profile, tag = ManualBlockingStrategy.ID, forceStarted = false)
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
        const val ID = "NFCManualBlockingStrategy"
    }
}
