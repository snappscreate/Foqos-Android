package com.foqos.android.service.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PeriodicAccessScheduleTest {
    private val config = PeriodicAccessStore.Config(waitMinutes = 60, accessMinutes = 5)
    private val minute = 60_000L
    private val anchor = 10_000L

    @Test fun cycleRepeatsAndExpiresAtExactBoundaries() {
        assertThat(PeriodicAccessSchedule.isAccessible(config, anchor, anchor)).isFalse()
        assertThat(PeriodicAccessSchedule.isAccessible(config, anchor, anchor + 59 * minute)).isFalse()
        assertThat(PeriodicAccessSchedule.isAccessible(config, anchor, anchor + 60 * minute)).isTrue()
        assertThat(PeriodicAccessSchedule.isAccessible(config, anchor, anchor + 64 * minute)).isTrue()
        assertThat(PeriodicAccessSchedule.isAccessible(config, anchor, anchor + 65 * minute)).isFalse()
        assertThat(PeriodicAccessSchedule.isAccessible(config, anchor, anchor + 125 * minute)).isTrue()
        assertThat(PeriodicAccessSchedule.isAccessible(config, anchor, anchor + 130 * minute)).isFalse()
    }

    @Test fun schedulesNextBoundaryWithoutAccumulatingTimerDrift() {
        assertThat(PeriodicAccessSchedule.nextBoundary(config, anchor, anchor))
            .isEqualTo(anchor + 60 * minute)
        assertThat(PeriodicAccessSchedule.nextBoundary(config, anchor, anchor + 60 * minute))
            .isEqualTo(anchor + 65 * minute)
        assertThat(PeriodicAccessSchedule.nextBoundary(config, anchor, anchor + 65 * minute))
            .isEqualTo(anchor + 125 * minute)
    }

    @Test fun disabledFeatureNeverGrantsAccess() {
        val disabled = PeriodicAccessStore.Config()
        assertThat(PeriodicAccessSchedule.isAccessible(disabled, anchor, anchor + 1_000_000L)).isFalse()
        assertThat(PeriodicAccessSchedule.nextBoundary(disabled, anchor)).isNull()
    }

    @Test fun resetAnchorAfterPauseRestartsBlockedCountdown() {
        val pauseEnded = anchor + 62 * minute
        assertThat(PeriodicAccessSchedule.isAccessible(config, pauseEnded, pauseEnded + 4 * minute))
            .isFalse()
        assertThat(PeriodicAccessSchedule.nextBoundary(config, pauseEnded, pauseEnded))
            .isEqualTo(pauseEnded + 60 * minute)
    }
}
