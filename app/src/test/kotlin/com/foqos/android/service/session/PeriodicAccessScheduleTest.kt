package com.foqos.android.service.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PeriodicAccessScheduleTest {
    private val config = PeriodicAccessStore.Config(waitMinutes = 60, accessMinutes = 5)
    private val minute = 60_000L
    private val anchor = 10_000L

    @Test fun earnedBreakWaitsForManualActivation() {
        assertThat(PeriodicAccessSchedule.isAvailable(config, anchor, 0L, anchor + 59 * minute)).isFalse()
        assertThat(PeriodicAccessSchedule.isAvailable(config, anchor, 0L, anchor + 60 * minute)).isTrue()
        assertThat(PeriodicAccessSchedule.isAvailable(config, anchor, 0L, anchor + 100 * minute)).isTrue()
        assertThat(PeriodicAccessSchedule.isActive(config, anchor, 0L, anchor + 100 * minute)).isFalse()
    }

    @Test fun pressingLateStillGrantsTheFullFiveMinutes() {
        val pressed = anchor + 100 * minute
        assertThat(PeriodicAccessSchedule.isActive(config, anchor, pressed, pressed)).isTrue()
        assertThat(PeriodicAccessSchedule.isActive(config, anchor, pressed, pressed + 4 * minute)).isTrue()
        assertThat(PeriodicAccessSchedule.isActive(config, anchor, pressed, pressed + 5 * minute)).isFalse()
        assertThat(PeriodicAccessSchedule.remainingMillis(config, anchor, pressed, pressed + 4 * minute))
            .isEqualTo(minute)
    }

    @Test fun aNewWaitStartsWhenTheUsedBreakEnds() {
        val pressed = anchor + 60 * minute
        assertThat(PeriodicAccessSchedule.isAvailable(config, anchor, pressed, pressed + 5 * minute)).isFalse()
        assertThat(PeriodicAccessSchedule.isAvailable(config, anchor, pressed, pressed + 64 * minute)).isFalse()
        assertThat(PeriodicAccessSchedule.isAvailable(config, anchor, pressed, pressed + 65 * minute)).isTrue()
        assertThat(PeriodicAccessSchedule.nextBoundary(config, anchor, pressed, pressed))
            .isEqualTo(pressed + 5 * minute)
        assertThat(PeriodicAccessSchedule.nextBoundary(config, anchor, pressed, pressed + 6 * minute))
            .isEqualTo(pressed + 65 * minute)
    }

    @Test fun disabledScheduleCannotGrantBreak() {
        val disabled = PeriodicAccessStore.Config()
        assertThat(PeriodicAccessSchedule.isAvailable(disabled, anchor, 0L, anchor + 100 * minute)).isFalse()
        assertThat(PeriodicAccessSchedule.isActive(disabled, anchor, 0L, anchor + 100 * minute)).isFalse()
        assertThat(PeriodicAccessSchedule.nextBoundary(disabled, anchor, 0L)).isNull()
    }
}
