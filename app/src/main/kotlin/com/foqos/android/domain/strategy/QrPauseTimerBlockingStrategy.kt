package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.data.model.StrategyPauseTimerData
import java.time.Instant

/**
 * Pick a pause duration in-app; scanning a QR code/barcode once pauses the session for that
 * duration, scanning again while paused fully stops it - mirrors iOS
 * `QRPauseTimerBlockingStrategy`.
 */
class QrPauseTimerBlockingStrategy : BlockingStrategy {
    override val id: String = ID
    override val name: String = "QR/Barcode + Pause Timer"
    override val description: String =
        "Choose how long a pause should last. Scan a QR code or barcode once to pause. " +
            "Scan it again during the pause to fully stop."
    override val category: StrategyCategory = StrategyCategory.FOREVER
    override val usesQr: Boolean = true
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
            mismatchMessage =
                "This QR code is not allowed to unblock this profile. Physical unblock setting is on for this profile",
        )
        if (rejection != null) {
            host.emitError(rejection)
            return SessionOutcome.Rejected(rejection)
        }

        return if (session.isPauseActive()) {
            host.endSession(session, Instant.now())
            SessionOutcome.Stopped
        } else {
            val pauseDurationMinutes =
                StrategyPauseTimerData.fromJsonOrNull(profile.strategyDataJson).pauseDurationInMinutes
            host.beginPause(session, pauseDurationMinutes)
            SessionOutcome.Paused
        }
    }

    companion object {
        const val ID = "QRPauseTimerBlockingStrategy"
    }
}
