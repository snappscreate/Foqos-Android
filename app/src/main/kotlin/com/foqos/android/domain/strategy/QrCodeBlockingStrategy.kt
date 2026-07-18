package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.PhysicalUnblockType
import java.time.Instant

/**
 * Start by scanning any QR code/barcode, stop by scanning the same code again - mirrors iOS
 * `QRCodeBlockingStrategy`.
 */
class QrCodeBlockingStrategy : BlockingStrategy {
    override val id: String = ID
    override val name: String = "QR Code/Barcode"
    override val description: String = "Start by scanning a QR code or barcode. To stop, scan the same code again."
    override val category: StrategyCategory = StrategyCategory.MOST_POPULAR
    override val usesQr: Boolean = true
    override val requiresSameCodeToStop: Boolean = true

    override fun startRequest(profile: BlockedProfile): StrategyUiRequest =
        StrategyUiRequest.ScanQr(StrategyUiRequest.Purpose.START)

    override suspend fun onStartInput(
        profile: BlockedProfile,
        input: StrategyInput,
        host: StrategyHost,
    ): SessionOutcome {
        if (input !is StrategyInput.Code) {
            host.emitError("Expected a scanned QR code to start this profile")
            return SessionOutcome.Rejected("Expected a scanned QR code to start this profile")
        }
        val session = host.createSession(profile, tag = input.value, forceStarted = false)
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
            host.emitError("Expected a scanned QR code to stop this profile")
            return SessionOutcome.Rejected("Expected a scanned QR code to stop this profile")
        }

        val rejection = PhysicalUnlockRules.validateSameCodeOrPhysical(
            profile = profile,
            session = session,
            code = input.value,
            type = PhysicalUnblockType.QR_CODE,
            mismatchMessage =
                "This QR code is not allowed to unblock this profile. Physical unblock setting is on for this profile",
            originalCodeMessage = "You must scan the original QR code to stop focus",
        )
        if (rejection != null) {
            host.emitError(rejection)
            return SessionOutcome.Rejected(rejection)
        }

        host.endSession(session, Instant.now())
        return SessionOutcome.Stopped
    }

    companion object {
        const val ID = "QRCodeBlockingStrategy"
    }
}
