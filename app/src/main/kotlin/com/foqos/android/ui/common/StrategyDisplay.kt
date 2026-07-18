package com.foqos.android.ui.common

import com.foqos.android.domain.strategy.BlockingStrategy

/** Small emoji glyph representing [strategy]'s primary trigger, used across profile/strategy rows. */
fun strategyEmoji(strategy: BlockingStrategy): String = when {
    strategy.usesNfc -> "🏷️" // 🏷️
    strategy.usesQr -> "🔳" // 🔳
    strategy.hasTimer -> "⏱️" // ⏱️
    strategy.hasPauseMode -> "⏸️" // ⏸️
    strategy.startsManually -> "👆" // 👆
    else -> "🔒" // 🔒
}

/** Short badge labels for a strategy's capability flags, shown in the strategy picker. */
fun strategyBadges(strategy: BlockingStrategy): List<String> = buildList {
    if (strategy.usesNfc) add("NFC")
    if (strategy.usesQr) add("QR")
    if (strategy.hasTimer) add("Timer")
    if (strategy.isBeta) add("Beta")
}
