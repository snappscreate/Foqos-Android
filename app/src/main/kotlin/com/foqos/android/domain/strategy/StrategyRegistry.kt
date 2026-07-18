package com.foqos.android.domain.strategy

/**
 * The full set of available [BlockingStrategy] implementations, mirroring iOS
 * `StrategyManager.availableStrategies` (same order, same ids).
 */
object StrategyRegistry {

    val all: List<BlockingStrategy> = listOf(
        NfcSoftUnblockBlockingStrategy(),
        QrSoftUnblockBlockingStrategy(),
        ManualBlockingStrategy(),
        NfcBlockingStrategy(),
        NfcManualBlockingStrategy(),
        NfcTimerBlockingStrategy(),
        NfcPauseTimerBlockingStrategy(),
        QrCodeBlockingStrategy(),
        QrManualBlockingStrategy(),
        QrTimerBlockingStrategy(),
        QrPauseTimerBlockingStrategy(),
        TimerManualBlockingStrategy(),
    )

    private val byIdMap: Map<String, BlockingStrategy> = all.associateBy { it.id }

    /**
     * Looks up a strategy by [id]. Falls back to [NfcBlockingStrategy] for `null` or unknown ids,
     * mirroring iOS `StrategyManager.getStrategyFromId`.
     */
    fun byId(id: String?): BlockingStrategy =
        id?.let { byIdMap[it] } ?: byIdMap.getValue(NfcBlockingStrategy.ID)

    /** All strategies grouped by their picker [StrategyCategory], in registry order. */
    fun categorized(): Map<StrategyCategory, List<BlockingStrategy>> = all.groupBy { it.category }
}
