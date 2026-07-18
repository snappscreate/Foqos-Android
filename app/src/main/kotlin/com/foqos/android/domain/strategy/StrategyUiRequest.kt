package com.foqos.android.domain.strategy

import com.foqos.android.data.model.SoftUnblockStrategyData
import com.foqos.android.data.model.StrategyPauseTimerData
import com.foqos.android.data.model.StrategyTimerData

/**
 * Describes the UI a [BlockingStrategy] needs the caller to present before it can proceed with a
 * start or stop. Strategies stay pure: they never touch Android UI themselves, they only declare
 * what is needed - the UI layer renders it and feeds the result back in as a [StrategyInput].
 */
sealed interface StrategyUiRequest {

    /** Whether the requested scan is for starting or stopping a session. */
    enum class Purpose { START, STOP }

    data class ScanNfc(val purpose: Purpose) : StrategyUiRequest
    data class ScanQr(val purpose: Purpose) : StrategyUiRequest
    data class PickTimerDuration(val initial: StrategyTimerData) : StrategyUiRequest
    data class PickPauseDuration(val initial: StrategyPauseTimerData) : StrategyUiRequest
    data class ConfigureSoftUnblock(val initial: SoftUnblockStrategyData) : StrategyUiRequest

    /** No UI is needed - the strategy can proceed immediately with [StrategyInput.NoInput]. */
    data object None : StrategyUiRequest
}
