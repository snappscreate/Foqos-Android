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

/** Localized names for the strategy picker and profile editor. */
fun strategyDisplayName(strategy: BlockingStrategy): String = when (strategy.id) {
    ManualBlockingStrategy.ID -> "手動で開始・停止"
    TimerManualBlockingStrategy.ID -> "タイマー・手動"
    NfcBlockingStrategy.ID -> "NFCタグで開始・停止"
    NfcManualBlockingStrategy.ID -> "NFC開始・手動停止"
    NfcTimerBlockingStrategy.ID -> "NFC・タイマー"
    NfcPauseTimerBlockingStrategy.ID -> "NFCで一時停止"
    NfcSoftUnblockBlockingStrategy.ID -> "NFC・短時間利用"
    QrCodeBlockingStrategy.ID -> "QRコードで開始・停止"
    QrManualBlockingStrategy.ID -> "QR開始・手動停止"
    QrTimerBlockingStrategy.ID -> "QR・タイマー"
    QrPauseTimerBlockingStrategy.ID -> "QRで一時停止"
    QrSoftUnblockBlockingStrategy.ID -> "QR・短時間利用"
    else -> strategy.name
}

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
    if (strategy.hasTimer) add("タイマー")
    if (strategy.isBeta) add("試験運用")
}

/**
 * The 3 mechanism-based groups shown in the redesigned [com.foqos.android.ui.profile.StrategyPickerSheet]
 * (replacing the old 5-way [com.foqos.android.domain.strategy.StrategyCategory] section list, which
 * user testing found unclear - "the modes should be better sorted and more visible/understandable").
 * Display order is [APP], [NFC], [QR].
 */
enum class StrategyPickerGroup(val title: String, val subtitle: String) {
    APP(
        title = "アプリで開始・停止",
        subtitle = "スマホのボタンで開始・停止。タグの読み取りは不要。",
    ),
    NFC(
        title = "NFCタグ",
        subtitle = "NFCタグを読み取って開始・停止。",
    ),
    QR(
        title = "QRコード",
        subtitle = "QRコードやバーコードで開始・停止。",
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
    if (strategy.hasTimer) add("タイマー")
    if (strategy.hasPauseMode) add("一時停止")
    if (!strategy.allowsTimedBreaks) add("一時利用")
    if (strategy.isBeta) add("試験運用")
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
        "アプリの開始・停止ボタンで操作。読み取り不要。",
    TimerManualBlockingStrategy.ID to
        "Tap Start and pick a duration - the session ends automatically when time's up, or tap Stop to end it early.",
    NfcBlockingStrategy.ID to
        "NFCタグで開始。登録されたタグを読み取ると停止。",
    NfcManualBlockingStrategy.ID to
        "NFCタグで開始。アプリ内の停止ボタンで終了。",
    NfcTimerBlockingStrategy.ID to
        "NFCタグで開始し時間を指定。タグまたは時間切れで終了。",
    NfcPauseTimerBlockingStrategy.ID to
        "アプリから開始。登録済みNFCタグで指定時間だけ一時停止。停止中の再読み取りでは終了しない。",
    NfcSoftUnblockBlockingStrategy.ID to
        "アプリで開始。一定回数の短時間利用が可能。NFCタグで終了。",
    QrCodeBlockingStrategy.ID to
        "QRコードで開始。同じコードを読み取って停止。",
    QrManualBlockingStrategy.ID to
        "QRコードで開始。停止はアプリ内から操作。",
    QrTimerBlockingStrategy.ID to
        "QRコードで開始し時間を指定。QRコードまたは時間切れで終了。",
    QrPauseTimerBlockingStrategy.ID to
        "アプリから開始。QRコードで指定時間だけ一時停止。",
    QrSoftUnblockBlockingStrategy.ID to
        "アプリで開始。短時間の一時利用が可能。QRコードで停止。",
)
