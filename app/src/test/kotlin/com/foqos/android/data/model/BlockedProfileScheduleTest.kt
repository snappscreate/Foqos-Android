package com.foqos.android.data.model

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import org.junit.Test

class BlockedProfileScheduleTest {

    private fun schedule(
        days: List<Weekday>,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
    ) = BlockedProfileSchedule(
        days = days,
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute,
    )

    @Test
    fun `not active when no days configured`() {
        val schedule = schedule(emptyList(), 9, 0, 17, 0)
        assertThat(schedule.isActiveAt(LocalDateTime.of(2026, 7, 20, 10, 0))).isFalse() // a Monday
    }

    @Test
    fun `active within a same-day window on a scheduled day`() {
        // 2026-07-20 is a Monday.
        val schedule = schedule(listOf(Weekday.MONDAY), 9, 0, 17, 0)
        assertThat(schedule.isActiveAt(LocalDateTime.of(2026, 7, 20, 12, 0))).isTrue()
        assertThat(schedule.isActiveAt(LocalDateTime.of(2026, 7, 20, 9, 0))).isTrue()
        assertThat(schedule.isActiveAt(LocalDateTime.of(2026, 7, 20, 16, 59))).isTrue()
    }

    @Test
    fun `inactive outside a same-day window`() {
        val schedule = schedule(listOf(Weekday.MONDAY), 9, 0, 17, 0)
        assertThat(schedule.isActiveAt(LocalDateTime.of(2026, 7, 20, 8, 59))).isFalse()
        assertThat(schedule.isActiveAt(LocalDateTime.of(2026, 7, 20, 17, 0))).isFalse()
    }

    @Test
    fun `inactive on a day not scheduled`() {
        // 2026-07-21 is a Tuesday, not scheduled.
        val schedule = schedule(listOf(Weekday.MONDAY), 9, 0, 17, 0)
        assertThat(schedule.isActiveAt(LocalDateTime.of(2026, 7, 21, 12, 0))).isFalse()
    }

    @Test
    fun `midnight-wrapping window is active late at night on the start day`() {
        // Window 22:00 -> 06:00, scheduled on Monday.
        val schedule = schedule(listOf(Weekday.MONDAY), 22, 0, 6, 0)
        // Monday 23:30 - started today, hasn't wrapped yet.
        assertThat(schedule.isActiveAt(LocalDateTime.of(2026, 7, 20, 23, 30))).isTrue()
    }

    @Test
    fun `midnight-wrapping window is active early morning on the day after`() {
        // Window 22:00 -> 06:00, scheduled on Monday (the window continues into Tuesday morning).
        val schedule = schedule(listOf(Weekday.MONDAY), 22, 0, 6, 0)
        // Tuesday 03:00 - continuing from Monday's window.
        assertThat(schedule.isActiveAt(LocalDateTime.of(2026, 7, 21, 3, 0))).isTrue()
    }

    @Test
    fun `midnight-wrapping window is inactive once past the end time the next day`() {
        val schedule = schedule(listOf(Weekday.MONDAY), 22, 0, 6, 0)
        // Tuesday 07:00 - past the wrapped end time.
        assertThat(schedule.isActiveAt(LocalDateTime.of(2026, 7, 21, 7, 0))).isFalse()
    }

    @Test
    fun `midnight-wrapping window does not continue if the start day wasn't scheduled`() {
        // Only Wednesday is scheduled; Tuesday morning must not be treated as "continuing".
        val schedule = schedule(listOf(Weekday.WEDNESDAY), 22, 0, 6, 0)
        assertThat(schedule.isActiveAt(LocalDateTime.of(2026, 7, 21, 3, 0))).isFalse()
    }

    @Test
    fun `zero-length window (start equals end) is never active`() {
        val schedule = schedule(listOf(Weekday.MONDAY), 9, 0, 9, 0)
        assertThat(schedule.isActiveAt(LocalDateTime.of(2026, 7, 20, 9, 0))).isFalse()
        assertThat(schedule.totalDurationInSeconds).isEqualTo(0)
    }

    @Test
    fun `totalDurationInSeconds for a same-day window`() {
        val schedule = schedule(listOf(Weekday.MONDAY), 9, 0, 17, 30)
        assertThat(schedule.totalDurationInSeconds).isEqualTo((8 * 3600) + (30 * 60))
    }

    @Test
    fun `totalDurationInSeconds wraps past midnight`() {
        val schedule = schedule(listOf(Weekday.MONDAY), 22, 0, 6, 0)
        assertThat(schedule.totalDurationInSeconds).isEqualTo(8 * 3600)
    }

    @Test
    fun `nextStartDateTime finds today's start when buffer still allows it`() {
        // 2026-07-20 is a Monday. Now is 07:00, schedule starts at 09:00 -> within reach with 15min buffer.
        val schedule = schedule(listOf(Weekday.MONDAY), 9, 0, 17, 0)
        val now = LocalDateTime.of(2026, 7, 20, 7, 0)
        assertThat(schedule.nextStartDateTime(now)).isEqualTo(LocalDateTime.of(2026, 7, 20, 9, 0))
    }

    @Test
    fun `nextStartDateTime skips today if inside the buffer window and picks next scheduled day`() {
        // Now is Monday 08:50, buffer is 15min -> 09:05 is past 09:00 start, so today no longer qualifies.
        // Next scheduled day within the 7-day lookahead is Wednesday.
        val schedule = schedule(listOf(Weekday.MONDAY, Weekday.WEDNESDAY), 9, 0, 17, 0)
        val now = LocalDateTime.of(2026, 7, 20, 8, 50)
        val next = schedule.nextStartDateTime(now)
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 7, 22, 9, 0))
    }

    @Test
    fun `nextStartDateTime returns null if the only scheduled day is more than 7 days away`() {
        // Only Monday is scheduled; once today's slot is inside the buffer window, the next
        // Monday is 7 days out - outside the (0..6 days ahead) lookahead - so there is no result.
        val schedule = schedule(listOf(Weekday.MONDAY), 9, 0, 17, 0)
        val now = LocalDateTime.of(2026, 7, 20, 8, 50)
        assertThat(schedule.nextStartDateTime(now)).isNull()
    }

    @Test
    fun `nextStartDateTime returns null when no days configured`() {
        val schedule = schedule(emptyList(), 9, 0, 17, 0)
        assertThat(schedule.nextStartDateTime(LocalDateTime.of(2026, 7, 20, 7, 0))).isNull()
    }

    @Test
    fun `isScheduledOn reflects configured days`() {
        val schedule = schedule(listOf(Weekday.MONDAY, Weekday.WEDNESDAY), 9, 0, 17, 0)
        assertThat(schedule.isScheduledOn(Weekday.MONDAY)).isTrue()
        assertThat(schedule.isScheduledOn(Weekday.TUESDAY)).isFalse()
    }
}
