package com.foqos.android.domain.strategy

import com.foqos.android.data.model.SoftUnblockStrategyData
import com.foqos.android.data.model.StrategyPauseTimerData
import com.foqos.android.data.model.StrategyTimerData

/**
 * The result of a [StrategyUiRequest] fed back into a [BlockingStrategy]'s `onStartInput` /
 * `onStopInput`.
 */
sealed interface StrategyInput {

    /** Where a scanned [Code] came from. */
    enum class CodeSource { NFC, QR }

    data class Code(val value: String, val source: CodeSource) : StrategyInput
    data class TimerData(val data: StrategyTimerData) : StrategyInput
    data class PauseData(val data: StrategyPauseTimerData) : StrategyInput
    data class SoftUnblockData(val data: SoftUnblockStrategyData) : StrategyInput

    /** Fed back for manual/no-UI flows (e.g. tapping a Start/Stop button). */
    data object NoInput : StrategyInput
}
