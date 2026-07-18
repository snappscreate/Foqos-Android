package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.data.model.SoftUnblockStrategyData
import java.time.Instant

/**
 * Blocks apps but allows a few short, allowance-limited opens; stop by scanning a QR
 * code/barcode - mirrors iOS `QRSoftUnblockBlockingStrategy`. Does not support timed breaks.
 */
class QrSoftUnblockBlockingStrategy : BlockingStrategy {
    override val id: String = ID
    override val name: String = "Temporary Access + QR"
    override val description: String =
        "Block your apps, but allow a few short opens when you need them. " +
            "Scan a QR code or barcode to stop the session."
    override val category: StrategyCategory = StrategyCategory.FOREVER
    override val usesQr: Boolean = true
    override val startsManually: Boolean = true
    override val allowsTimedBreaks: Boolean = false
    override val isBeta: Boolean = true

    override fun startRequest(profile: BlockedProfile): StrategyUiRequest =
        StrategyUiRequest.ConfigureSoftUnblock(SoftUnblockStrategyData.fromJsonOrNull(profile.strategyDataJson))

    override suspend fun onStartInput(
        profile: BlockedProfile,
        input: StrategyInput,
        host: StrategyHost,
    ): SessionOutcome {
        if (input !is StrategyInput.SoftUnblockData) {
            host.emitError("Failed to save the temporary access settings.")
            return SessionOutcome.Rejected("Failed to save the temporary access settings.")
        }

        val session = host.createSession(profile, tag = ID, forceStarted = false)
        host.beginSoftUnblockSession(profile, session, input.data)
        return SessionOutcome.Started(session)
    }

    override fun stopRequest(profile: BlockedProfile, session: BlockedProfileSession): StrategyUiRequest =
        StrategyUiRequest.ScanQr(StrategyUiRequest.Purpose.STOP)

    override suspend fun onStopInput(
        profile: BlockedProfile,
        session: BlockedProfileSession,
        input: StrategyInput,
        host: StrategyHost,
    ): SessionOutcome {
        if (input !is StrategyInput.Code) {
            host.emitError("Expected a scanned QR code")
            return SessionOutcome.Rejected("Expected a scanned QR code")
        }

        val rejection = PhysicalUnlockRules.validateAnyCodeOrPhysical(
            profile = profile,
            code = input.value,
            type = PhysicalUnblockType.QR_CODE,
            mismatchMessage = "This QR code or barcode can't stop this profile.",
        )
        if (rejection != null) {
            host.emitError(rejection)
            return SessionOutcome.Rejected(rejection)
        }

        host.endSession(session, Instant.now())
        return SessionOutcome.Stopped
    }

    companion object {
        const val ID = "QRSoftUnblockBlockingStrategy"
    }
}
