package com.foqos.android.ui.activitylab

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class IntermittentCapturePlanTest {
    @Test fun randomizedIntervalsStayWithinOneToTwoMinutes() {
        val random = Random(191)
        val intervals = (1..1000).map { IntermittentCapturePlan.nextGapMillis(random) }
        assertThat(intervals.minOrNull()).isAtLeast(60_000L)
        assertThat(intervals.maxOrNull()).isAtMost(120_000L)
        assertThat(intervals.distinct().size).isGreaterThan(50)
    }

    @Test fun sampleScheduleIsExplainedAsNonContinuousEvidence() {
        val prompt = IntermittentCapturePlan.evidenceDescription(
            "部屋を30分掃除する", 1_800_000L, listOf(0L, 90_000L, 183_000L))
        assertThat(prompt).contains("30.0分")
        assertThat(prompt).contains("0.0, 1.5, 3.1")
        assertThat(prompt).contains("連続記録ではなく")
        assertThat(prompt).contains("判断不能")
        assertThat(prompt).contains("経過時間が足りない場合")
    }

    @Test fun oneMinuteOfFootageFromHalfHourActivityIsExpected() {
        val clips = 30L * 60L / 90L
        val recordingSeconds = clips * IntermittentCapturePlan.CLIP_MILLIS / 1_000L
        assertThat(recordingSeconds).isEqualTo(60L)
    }
}
