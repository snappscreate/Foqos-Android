package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.PhysicalUnblockType
import java.time.Instant

/**
 * Start from the app, stop by scanning any QR code/barcode (or a configured physical unblock
 * item) - mirrors iOS `QRManualBlockingStrategy`.
 */
class QrManualBlockingStrategy : BlockingStrategy {
    override val id: String = ID
    override val name: String = "QR/Barcode + Manual"
    override val description: String =
        "Start in the app. To stop, scan any QR code or barcode. " +
            "Use Strict Unlocks if you want only selected codes to work."
    override val category: StrategyCategory = StrategyCategory.EASY_TO_START
    override val usesQr: Boolean = true
    override val startsManually: Boolean = true

    override fun startRequest(profile: BlockedProfile): StrategyUiRequest = StrategyUiRequest.None

    override suspend fun onStartInput(
        profile: BlockedProfile,
        input: StrategyInput,
        host: StrategyHost,
    ): SessionOutcome {
        val session = host.createSession(profile, tag = ManualBlockingStrategy.ID, forceStarted = false)
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

        val rejection = PhysicalUnlockRules.validateAnyCodeOrPhysical(
            profile = profile,
            code = input.value,
            type = PhysicalUnblockType.QR_CODE,
            mismatchMessage =
                "This QR code is not allowed to unblock this profile. Physical unblock setting is on for this profile",
        )
        if (rejection != null) {
            host.emitError(rejection)
            return SessionOutcome.Rejected(rejection)
        }

        host.endSession(session, Instant.now())
        return SessionOutcome.Stopped
    }

    companion object {
        const val ID = "QRManualBlockingStrategy"
    }
}
