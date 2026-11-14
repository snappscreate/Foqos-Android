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
        // 170s average leaves room for randomized samples up to the 180s ceiling.
        return scaled.roundToLong().coerceIn(10_000L, 170_000L)
    }

    /** Randomized capture interval, bounded to 10-180 seconds. */
    fun nextGapMillis(targetMinutes: Int, random: Random = Random.Default): Long {
        val average = averageIntervalMillis(targetMinutes)
        val high = (average * 1.25).roundToLong().coerceIn(10_000L, 180_000L)
        // Preserve the configured mean even when the upper random bound is clipped.
        val low = (2 * average - high).coerceIn(10_000L, high)
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
        val intervalSeconds = sampleTimesMillis.zipWithNext { first, second ->
            (second - first).coerceAtLeast(0L)
        }
        val averageSeconds = intervalSeconds.takeIf { it.isNotEmpty() }
            ?.average()?.div(1_000.0)
        val moments = sampleTimesMillis.joinToString(", ") {
            "%.1f".format(java.util.Locale.US, it / 60_000.0)
        }
        return buildString {
            append(activity)
            append("\n【間欠撮影による活動判定】")
            append("実際の活動計測時間は")
            append("%.1f".format(java.util.Locale.US, elapsedMinutes))
            append("分。")
            append("この1本の動画は録画の一時停止と再開を繰り返して作られたもので、")
            append("各撮影区間は約3秒。")
            append("撮影区間は")
            append(sampleTimesMillis.size)
            append("回。")
            if (averageSeconds != null) {
                append("撮影開始時刻同士の平均間隔は約")
                append("%.1f".format(java.util.Locale.US, averageSeconds))
                append("秒。")
            }
            append("撮影開始時点（活動開始からの分）は")
            append(moments)
            append("。動画の再生時間と実際の活動計測時間を混同しないこと。")
            append("\n【一致の基準】")
            append("設定時間が経過しており、時間的に離れた複数の撮影区間で対象の動作が確認でき、")
            append("長時間の未実施や明らかな中断を示す場面がなく、")
            append("一連の継続した活動だったと合理的に推定できるなら「一致」。")
            append("録画していない時間まで映像で直接確認できる必要はない。")
            append("短いクリップをつないだ動画であることだけを理由に不一致や判断不能にしないこと。")
            append("\n【不一致・判断不能の基準】")
            append("対象動作をほとんど確認できない、または長い休憩や別の活動が明確に観測されるなら「不一致」。")
            append("対象が画角から外れている、映像が不鮮明、撮影区間が少なすぎるなど、")
            append("推定に必要な情報が不足するなら「判断不能」。")
            append("未撮影時間に何をしていたかを事実として断定しないこと。")
            append("設定時間に達していない場合は「不一致」。")
            append("判定理由では複数の撮影区間に観測された具体的な動作と中断の有無を説明すること。")
        }
    }

}
