package com.foqos.android.domain.insights

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Test

/** Port of iOS `foqosTests/WeeklySessionAggregatorTests.swift` - same scenarios and expected values. */
class WeeklySessionAggregatorTest {

    @Test
    fun `single day session stays on same day`() {
        val weekStart = date(2024, 3, 3)
        val sessions = listOf(WeeklySessionInterval(date(2024, 3, 4, 10), date(2024, 3, 4, 14)))

        val result = WeeklySessionAggregator.aggregate(sessions, weekStart, ZoneOffset.UTC)

        assertThat(result.dailyDurations[1].seconds).isEqualTo(4 * 3600L)
        assertThat(result.dailySessionCounts[1]).isEqualTo(1)
        assertThat(result.totalFocusTime.seconds).isEqualTo(4 * 3600L)
        assertThat(result.overlappingSessionCount).isEqualTo(1)
    }

    @Test
    fun `overnight session splits across days`() {
        val weekStart = date(2024, 3, 3)
        val sessions = listOf(WeeklySessionInterval(date(2024, 3, 4, 23), date(2024, 3, 5, 2)))

        val result = WeeklySessionAggregator.aggregate(sessions, weekStart, ZoneOffset.UTC)

        assertThat(result.dailyDurations[1].seconds).isEqualTo(3600L)
        assertThat(result.dailyDurations[2].seconds).isEqualTo(2 * 3600L)
        assertThat(result.dailySessionCounts[1]).isEqualTo(1)
        assertThat(result.dailySessionCounts[2]).isEqualTo(1)
        assertThat(result.totalFocusTime.seconds).isEqualTo(3 * 3600L)
    }

    @Test
    fun `session crossing into week only counts in week portion`() {
        val weekStart = date(2024, 3, 3)
        val sessions = listOf(WeeklySessionInterval(date(2024, 3, 2, 23), date(2024, 3, 3, 2)))

        val result = WeeklySessionAggregator.aggregate(sessions, weekStart, ZoneOffset.UTC)

        assertThat(result.dailyDurations[0].seconds).isEqualTo(2 * 3600L)
        assertThat(result.dailySessionCounts[0]).isEqualTo(1)
        assertThat(result.totalFocusTime.seconds).isEqualTo(2 * 3600L)
        assertThat(result.overlappingSessionCount).isEqualTo(1)
    }

    @Test
    fun `session crossing out of week only counts in week portion`() {
        val weekStart = date(2024, 3, 3)
        val sessions = listOf(WeeklySessionInterval(date(2024, 3, 9, 23), date(2024, 3, 10, 2)))

        val result = WeeklySessionAggregator.aggregate(sessions, weekStart, ZoneOffset.UTC)

        assertThat(result.dailyDurations[6].seconds).isEqualTo(3600L)
        assertThat(result.dailySessionCounts[6]).isEqualTo(1)
        assertThat(result.totalFocusTime.seconds).isEqualTo(3600L)
        assertThat(result.overlappingSessionCount).isEqualTo(1)
    }

    @Test
    fun `long session spans multiple days`() {
        val weekStart = date(2024, 3, 3)
        val sessions = listOf(WeeklySessionInterval(date(2024, 3, 4, 22), date(2024, 3, 6, 2)))

        val result = WeeklySessionAggregator.aggregate(sessions, weekStart, ZoneOffset.UTC)

        assertThat(result.dailyDurations[1].seconds).isEqualTo(2 * 3600L)
        assertThat(result.dailyDurations[2].seconds).isEqualTo(24 * 3600L)
        assertThat(result.dailyDurations[3].seconds).isEqualTo(2 * 3600L)
        assertThat(result.totalFocusTime.seconds).isEqualTo(28 * 3600L)
    }

    @Test
    fun `non overlapping sessions are ignored`() {
        val weekStart = date(2024, 3, 3)
        val sessions = listOf(
            WeeklySessionInterval(date(2024, 2, 28, 10), date(2024, 2, 28, 12)),
            WeeklySessionInterval(date(2024, 3, 10, 10), date(2024, 3, 10, 12)),
        )

        val result = WeeklySessionAggregator.aggregate(sessions, weekStart, ZoneOffset.UTC)

        assertThat(result.dailyDurations.fold(0L) { acc, d -> acc + d.seconds }).isEqualTo(0L)
        assertThat(result.dailySessionCounts.sum()).isEqualTo(0)
        assertThat(result.totalFocusTime.seconds).isEqualTo(0L)
        assertThat(result.overlappingSessionCount).isEqualTo(0)
    }

    @Test
    fun `start of week returns sunday for sunday-first weeks`() {
        val result = WeeklySessionAggregator.startOfWeek(date(2024, 3, 6, 15), ZoneOffset.UTC, DayOfWeek.SUNDAY)

        assertThat(result).isEqualTo(date(2024, 3, 3))
    }

    @Test
    fun `start of week returns monday for monday-first weeks`() {
        val result = WeeklySessionAggregator.startOfWeek(date(2026, 5, 6, 15), ZoneOffset.UTC, DayOfWeek.MONDAY)

        assertThat(result).isEqualTo(date(2026, 5, 4))
    }

    private fun date(year: Int, month: Int, day: Int, hour: Int = 0): Instant =
        LocalDateTime.of(year, month, day, hour, 0).toInstant(ZoneOffset.UTC)
}
