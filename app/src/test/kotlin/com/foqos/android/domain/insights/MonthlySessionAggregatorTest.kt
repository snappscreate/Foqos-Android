package com.foqos.android.domain.insights

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Test

/**
 * Behavior tests for [MonthlySessionAggregator] - no iOS `MonthlySessionAggregatorTests.swift`
 * exists to port, but the aggregation logic is identical to [WeeklySessionAggregator]'s (just
 * sized to the month), so these mirror the same day-splitting scenarios.
 */
class MonthlySessionAggregatorTest {

    @Test
    fun `session within a single day is attributed to that day only`() {
        val monthStart = date(2024, 3, 1)
        val sessions = listOf(MonthlySessionInterval(date(2024, 3, 15, 10), date(2024, 3, 15, 14)))

        val result = MonthlySessionAggregator.aggregate(sessions, monthStart, ZoneOffset.UTC)

        assertThat(result.daysInMonth).isEqualTo(31)
        assertThat(result.dailyDurations[14].seconds).isEqualTo(4 * 3600L) // March 15th -> index 14
        assertThat(result.dailySessionCounts[14]).isEqualTo(1)
        assertThat(result.totalFocusTime.seconds).isEqualTo(4 * 3600L)
    }

    @Test
    fun `session spanning month end only counts within the month`() {
        val monthStart = date(2024, 2, 1) // 2024 is a leap year -> 29 days
        val sessions = listOf(MonthlySessionInterval(date(2024, 2, 29, 23), date(2024, 3, 1, 2)))

        val result = MonthlySessionAggregator.aggregate(sessions, monthStart, ZoneOffset.UTC)

        assertThat(result.daysInMonth).isEqualTo(29)
        assertThat(result.dailyDurations[28].seconds).isEqualTo(3600L)
        assertThat(result.totalFocusTime.seconds).isEqualTo(3600L)
    }

    @Test
    fun `non overlapping sessions are ignored`() {
        val monthStart = date(2024, 3, 1)
        val sessions = listOf(
            MonthlySessionInterval(date(2024, 1, 15, 10), date(2024, 1, 15, 12)),
            MonthlySessionInterval(date(2024, 4, 15, 10), date(2024, 4, 15, 12)),
        )

        val result = MonthlySessionAggregator.aggregate(sessions, monthStart, ZoneOffset.UTC)

        assertThat(result.overlappingSessionCount).isEqualTo(0)
        assertThat(result.totalFocusTime.seconds).isEqualTo(0L)
    }

    @Test
    fun `start of month truncates to the first day at midnight`() {
        val result = MonthlySessionAggregator.startOfMonth(date(2024, 3, 17, 13), ZoneOffset.UTC)

        assertThat(result).isEqualTo(date(2024, 3, 1))
    }

    private fun date(year: Int, month: Int, day: Int, hour: Int = 0): Instant =
        LocalDateTime.of(year, month, day, hour, 0).toInstant(ZoneOffset.UTC)
}
