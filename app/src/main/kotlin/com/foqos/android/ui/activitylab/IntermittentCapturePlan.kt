package com.foqos.android.ui.activitylab

import kotlin.random.Random
import kotlin.math.sqrt
import kotlin.math.roundToLong

/**
 * Coarse activity evidence, not uninterrupted monitoring.
 * Capture windows are hidden from the activity UI, while camera-use disclosure stays visible.
 */
object IntermittentCapturePlan {
    const val CLIP_MILLIS = 3_000L
    const val MIN_GAP_MILLIS = 60_000L
    const val MAX_GAP_MILLIS = 120_000L
    const val MAX_UPLOAD_BYTES = 12_000_000L

    /** Target interval between short samples. 30 minutes of activity -> 90-second mean. */
    fun averageIntervalMillis(targetMinutes: Int): Long {
        val scaled = 90_000.0 * sqrt(targetMinutes.coerceAtLeast(1) / 30.0)
        return scaled.roundToLong().coerceIn(10_000L, 180_000L)
    }

    /** Randomized capture interval, bounded to 10-180 seconds. */
    fun nextGapMillis(targetMinutes: Int, random: Random = Random.Default): Long {
        val average = averageIntervalMillis(targetMinutes)
        val low = (average * 0.75).roundToLong().coerceIn(10_000L, 180_000L)
        val high = (average * 1.25).roundToLong().coerceIn(10_000L, 180_000L)
        return if (low == high) low else random.nextLong(low, high + 1)
    }

    /** Legacy callers without an explicit goal retain their old 60-120s range. */
    fun nextGapMillis(random: Random = Random.Default): Long =
        random.nextLong(MIN_GAP_MILLIS, MAX_GAP_MILLIS + 1)

    /** Explicit time-based challenges cannot be completed by stopping the recording early. */
    fun minimumDurationMillis(description: String): Long? {
        val hours = Regex("""(\d{1,2})\s*時間""").find(description)
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val minutes = Regex("""(\d{1,3})\s*分""").find(description)
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val total = (hours * 60L + minutes) * 60_000L
        return total.takeIf { it > 0L }
    }

    fun evidenceDescription(activity: String, elapsedMillis: Long, sampleTimesMillis: List<Long>): String {
        val elapsedMinutes = elapsedMillis / 60_000.0
        val moments = sampleTimesMillis.joinToString(", ") { "%.1f".format(java.util.Locale.US, it / 60_000.0) }
        return buildString {
            append(activity)
            append("\n【断続撮影の判定条件】活動全体の経過時間は")
            append("%.1f".format(java.util.Locale.US, elapsedMinutes))
            append("分。添付の動画は連続記録ではなく、時間を空けて撮影した約3秒のクリップを")
            append("時系列に連結したもの。撮影開始時点（活動開始からの分）: ")
            append(moments)
            append("。切り取られた動画を連続した行動と誤認しないこと。")
            append("観測されていない時間に活動したと断言しないこと。")
            append("指定した活動や継続性を裏付ける観測が足りないなら「判断不能」。")
            append("「30分行った」などの所要時間が条件なら、経過時間が足りない場合は「不一致」。")
        }
    }
}
