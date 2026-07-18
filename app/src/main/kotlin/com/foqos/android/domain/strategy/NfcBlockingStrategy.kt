package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.PhysicalUnblockType
import java.time.Instant

/**
 * Start by scanning any NFC tag, stop by scanning the same tag again - mirrors iOS
 * `NFCBlockingStrategy`.
 */
class NfcBlockingStrategy : BlockingStrategy {
    override val id: String = ID
    override val name: String = "NFC Tags"
    override val description: String = "Start by scanning an NFC tag. To stop, scan the same tag again."
    override val category: StrategyCategory = StrategyCategory.MOST_POPULAR
    override val usesNfc: Boolean = true
    override val requiresSameCodeToStop: Boolean = true

    override fun startRequest(profile: BlockedProfile): StrategyUiRequest =
        StrategyUiRequest.ScanNfc(StrategyUiRequest.Purpose.START)

    override suspend fun onStartInput(
        profile: BlockedProfile,
        input: StrategyInput,
        host: StrategyHost,
    ): SessionOutcome {
        if (input !is StrategyInput.Code) {
            host.emitError("Expected a scanned NFC tag to start this profile")
            return SessionOutcome.Rejected("Expected a scanned NFC tag to start this profile")
        }
        val session = host.createSession(profile, tag = input.value, forceStarted = false)
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

        val rejection = PhysicalUnlockRules.validateSameCodeOrPhysical(
            profile = profile,
            session = session,
            code = input.value,
            type = PhysicalUnblockType.NFC,
            mismatchMessage =
                "This NFC tag is not allowed to unblock this profile. Physical unblock setting is on for this profile",
            originalCodeMessage = "You must scan the original tag to stop focus",
        )
        if (rejection != null) {
            host.emitError(rejection)
            return SessionOutcome.Rejected(rejection)
        }

        host.endSession(session, Instant.now())
        return SessionOutcome.Stopped
    }

    companion object {
        const val ID = "NFCBlockingStrategy"
    }
}
