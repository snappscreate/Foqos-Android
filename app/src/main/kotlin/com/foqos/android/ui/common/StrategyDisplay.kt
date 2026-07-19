package com.foqos.android.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PanToolAlt
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import com.foqos.android.domain.strategy.BlockingStrategy
import com.foqos.android.domain.strategy.ManualBlockingStrategy
import com.foqos.android.domain.strategy.NfcBlockingStrategy
import com.foqos.android.domain.strategy.NfcManualBlockingStrategy
import com.foqos.android.domain.strategy.NfcPauseTimerBlockingStrategy
import com.foqos.android.domain.strategy.NfcSoftUnblockBlockingStrategy
import com.foqos.android.domain.strategy.NfcTimerBlockingStrategy
import com.foqos.android.domain.strategy.QrCodeBlockingStrategy
import com.foqos.android.domain.strategy.QrManualBlockingStrategy
import com.foqos.android.domain.strategy.QrPauseTimerBlockingStrategy
import com.foqos.android.domain.strategy.QrSoftUnblockBlockingStrategy
import com.foqos.android.domain.strategy.QrTimerBlockingStrategy
import com.foqos.android.domain.strategy.TimerManualBlockingStrategy

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

/**
 * The 3 mechanism-based groups shown in the redesigned [com.foqos.android.ui.profile.StrategyPickerSheet]
 * (replacing the old 5-way [com.foqos.android.domain.strategy.StrategyCategory] section list, which
 * user testing found unclear - "the modes should be better sorted and more visible/understandable").
 * Display order is [APP], [NFC], [QR].
 */
enum class StrategyPickerGroup(val title: String, val subtitle: String) {
    APP(
        title = "Start & stop in the app",
        subtitle = "No scanning required - start and stop with in-app buttons.",
    ),
    NFC(
        title = "NFC tag",
        subtitle = "Scan a physical NFC tag to start and/or stop a focus session.",
    ),
    QR(
        title = "QR code",
        subtitle = "Scan a QR code or barcode to start and/or stop a focus session.",
    ),
}

/** Which [StrategyPickerGroup] a strategy belongs to, based on its primary trigger mechanism. */
fun strategyPickerGroup(strategy: BlockingStrategy): StrategyPickerGroup = when {
    strategy.usesNfc -> StrategyPickerGroup.NFC
    strategy.usesQr -> StrategyPickerGroup.QR
    else -> StrategyPickerGroup.APP
}

/**
 * Distinct leading icon per underlying mechanism (hand / timer / NFC / QR), independent of
 * [StrategyPickerGroup] - e.g. "Timer + Manual" lives in the [StrategyPickerGroup.APP] group but
 * still gets the timer glyph rather than the generic hand glyph, so the row itself communicates
 * how the strategy behaves at a glance.
 */
fun strategyPickerIcon(strategy: BlockingStrategy): ImageVector = when {
    strategy.usesNfc -> Icons.Filled.Nfc
    strategy.usesQr -> Icons.Filled.QrCode
    strategy.hasTimer -> Icons.Filled.Timer
    else -> Icons.Filled.PanToolAlt
}

/**
 * The two most common strategies (Manual and NFC Tags), called out with a "Recommended" chip in
 * the picker per user feedback that the list needed clearer, more visible guidance for newcomers.
 */
fun isRecommendedStrategy(strategy: BlockingStrategy): Boolean =
    strategy.id == ManualBlockingStrategy.ID || strategy.id == NfcBlockingStrategy.ID

/** Capability badges shown on a strategy row, distinct from the mechanism already conveyed by the icon/group. */
fun strategyCapabilityBadges(strategy: BlockingStrategy): List<String> = buildList {
    if (strategy.hasTimer) add("Timer")
    if (strategy.hasPauseMode) add("Pause")
    if (!strategy.allowsTimedBreaks) add("Temporary access")
    if (strategy.isBeta) add("Beta")
}

/**
 * One clear, plain-language sentence describing exactly how starting and stopping work for
 * [strategy] - written fresh for the redesigned picker rather than reusing [BlockingStrategy.description]
 * (which is tuned for the profile editor's strategy summary card and is often too terse to stand
 * alone as the only explanation a user sees before picking a strategy).
 */
fun strategyOneLiner(strategy: BlockingStrategy): String = STRATEGY_ONE_LINERS[strategy.id] ?: strategy.description

private val STRATEGY_ONE_LINERS: Map<String, String> = mapOf(
    ManualBlockingStrategy.ID to
        "Tap Start in the app to begin, and tap Stop in the app to end. No scanning either way.",
    TimerManualBlockingStrategy.ID to
        "Tap Start and pick a duration - the session ends automatically when time's up, or tap Stop to end it early.",
    NfcBlockingStrategy.ID to
        "Start by scanning any NFC tag - only the same tag stops the session.",
    NfcManualBlockingStrategy.ID to
        "Start by scanning any NFC tag. Stop anytime from the app - no second scan needed.",
    NfcTimerBlockingStrategy.ID to
        "Start by scanning any NFC tag and pick a duration. Stop early by scanning any NFC tag, or let the timer end it.",
    NfcPauseTimerBlockingStrategy.ID to
        "Pick a pause length and tap Start in the app. Scan any NFC tag once to pause; scan again during the pause to fully stop.",
    NfcSoftUnblockBlockingStrategy.ID to
        "Start in the app, with a few short opens allowed while blocking is active. Scan any NFC tag to stop the session.",
    QrCodeBlockingStrategy.ID to
        "Start by scanning any QR code or barcode - only the same code stops the session.",
    QrManualBlockingStrategy.ID to
        "Start by scanning any QR code or barcode. Stop anytime from the app - no second scan needed.",
    QrTimerBlockingStrategy.ID to
        "Start by scanning any QR code or barcode and pick a duration. Stop early by scanning any code, or let the timer end it.",
    QrPauseTimerBlockingStrategy.ID to
        "Pick a pause length and tap Start in the app. Scan any QR code or barcode once to pause; scan again during the pause to fully stop.",
    QrSoftUnblockBlockingStrategy.ID to
        "Start in the app, with a few short opens allowed while blocking is active. Scan any QR code or barcode to stop the session.",
)
