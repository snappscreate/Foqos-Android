package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.data.model.StrategyTimerData

/**
 * A pure description of one way a profile can be started/stopped, mirroring iOS
 * `BlockingStrategy`. Implementations never touch Android frameworks or persistence: they declare
 * what UI is needed via [startRequest]/[stopRequest] and react to the resulting [StrategyInput] via
 * [onStartInput]/[onStopInput], calling into a [StrategyHost] to actually persist changes.
 */
interface BlockingStrategy {
    /** Stable identifier persisted on profiles - MUST match the iOS strategy id exactly. */
    val id: String

    val name: String
    val description: String
    val category: StrategyCategory

    val usesNfc: Boolean get() = false
    val usesQr: Boolean get() = false
    val hasTimer: Boolean get() = false
    val hasPauseMode: Boolean get() = false
    val startsManually: Boolean get() = false
    val requiresSameCodeToStop: Boolean get() = false
    val allowsTimedBreaks: Boolean get() = true
    val isBeta: Boolean get() = false

    /** What UI (if any) must be shown before [onStartInput] can be called. */
    fun startRequest(profile: BlockedProfile): StrategyUiRequest

    /** Called once the UI has produced the [input] requested by [startRequest]. */
    suspend fun onStartInput(profile: BlockedProfile, input: StrategyInput, host: StrategyHost): SessionOutcome

    /** What UI (if any) must be shown before [onStopInput] can be called. */
    fun stopRequest(profile: BlockedProfile, session: BlockedProfileSession): StrategyUiRequest

    /** Called once the UI has produced the [input] requested by [stopRequest]. */
    suspend fun onStopInput(
        profile: BlockedProfile,
        session: BlockedProfileSession,
        input: StrategyInput,
        host: StrategyHost,
    ): SessionOutcome
}

/**
 * Whether the in-app Stop button should be shown for [profile] given [elapsedSeconds] of focus
 * time, mirroring iOS `BlockedProfiles.showStopButton(elapsedTime:)`. Only timer strategies with
 * [StrategyTimerData.hideStopButton] enabled ever hide it, and only until the configured duration
 * has elapsed.
 */
fun showStopButton(profile: BlockedProfile, elapsedSeconds: Long): Boolean {
    val strategyDataJson = profile.strategyDataJson ?: return true
    val timerData = StrategyTimerData.fromJsonOrNull(strategyDataJson)
    if (!timerData.hideStopButton) return true

    val durationInSeconds = timerData.durationInMinutes * 60L
    return elapsedSeconds >= durationInSeconds
}

/**
 * Shared physical-unlock validation rules used by the NFC/QR strategy stop flows. Mirrors the two
 * validation shapes seen across the iOS strategies:
 *  - [validateAnyCodeOrPhysical]: any scanned code stops the session, unless physical unblock
 *    items are configured for [type], in which case the code must match one of them (used by the
 *    *Manual, *Timer, *PauseTimer and *SoftUnblock strategies).
 *  - [validateSameCodeOrPhysical]: the scanned code must match the code used to start the session
 *    (unless force-started), unless physical unblock items are configured for [type], in which
 *    case the code must match one of them instead (used by the plain NFC/QR strategies, which set
 *    `requiresSameCodeToStop = true`).
 */
internal object PhysicalUnlockRules {

    fun validateAnyCodeOrPhysical(
        profile: BlockedProfile,
        code: String,
        type: PhysicalUnblockType,
        mismatchMessage: String,
    ): String? =
        if (profile.hasPhysicalUnblockItem(type) && !profile.canUnblock(code, type)) mismatchMessage else null

    fun validateSameCodeOrPhysical(
        profile: BlockedProfile,
        session: BlockedProfileSession,
        code: String,
        type: PhysicalUnblockType,
        mismatchMessage: String,
        originalCodeMessage: String,
    ): String? = when {
        profile.hasPhysicalUnblockItem(type) ->
            if (!profile.canUnblock(code, type)) mismatchMessage else null
        !session.forceStarted && session.tag != code -> originalCodeMessage
        else -> null
    }
}
