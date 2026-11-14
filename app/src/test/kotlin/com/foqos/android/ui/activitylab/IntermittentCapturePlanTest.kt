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

    @Test fun intervalScalesWithActivityGoalAndHonorsTenTo180SecondBounds() {
        val short = IntermittentCapturePlan.averageIntervalMillis(2)
        val medium = IntermittentCapturePlan.averageIntervalMillis(10)
        val baseline = IntermittentCapturePlan.averageIntervalMillis(30)
        val long = IntermittentCapturePlan.averageIntervalMillis(60)
        assertThat(short).isLessThan(medium)
        assertThat(medium).isLessThan(90_000L)
        assertThat(baseline).isEqualTo(90_000L)
        assertThat(long).isGreaterThan(90_000L)
        assertThat(IntermittentCapturePlan.averageIntervalMillis(300))
            .isEqualTo(170_000L)

        val random = Random(993)
        listOf(2, 5, 10, 20, 30, 60, 120, 300).forEach { minutes ->
            val samples = (1..1000).map { IntermittentCapturePlan.nextGapMillis(minutes, random) }
            assertThat(samples.minOrNull()).isAtLeast(10_000L)
            assertThat(samples.maxOrNull()).isAtMost(180_000L)
            assertThat(samples.distinct().size).isGreaterThan(1)
        }
    }

    @Test fun sampleScheduleIsExplainedAsNonContinuousEvidence() {
        val prompt = IntermittentCapturePlan.evidenceDescription(
            "部屋を30分掃除する", 1_800_000L, listOf(0L, 90_000L, 183_000L))
        assertThat(prompt).contains("30.0分")
        assertThat(prompt).contains("0.0, 1.5, 3.1")
        assertThat(prompt).contains("この1本の動画")
        assertThat(prompt).contains("平均間隔は約91.5秒")
        assertThat(prompt).contains("合理的に推定")
        assertThat(prompt).contains("短いクリップをつないだ動画であることだけを理由に")
        assertThat(prompt).contains("判断不能")
        assertThat(prompt).contains("未撮影時間に何をしていたかを事実として断定しない")
    }

    @Test fun minimumDurationRecognizesMinutesAndHours() {
        assertThat(IntermittentCapturePlan.minimumDurationMillis("部屋を30分掃除する"))
            .isEqualTo(1_800_000L)
        assertThat(IntermittentCapturePlan.minimumDurationMillis("1時間20分勉強する"))
            .isEqualTo(4_800_000L)
        assertThat(IntermittentCapturePlan.minimumDurationMillis("腕立て伏せ30回"))
            .isNull()
    }

    @Test fun oneMinuteOfFootageFromHalfHourActivityIsExpected() {
        val clips = 30L * 60L / 90L
        val recordingSeconds = clips * IntermittentCapturePlan.CLIP_MILLIS / 1_000L
        assertThat(recordingSeconds).isEqualTo(60L)
    }
}
