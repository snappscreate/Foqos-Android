package com.foqos.android.domain.insights

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Test

class HeatmapAggregatorTest {

    private val today = LocalDate.of(2024, 3, 28)

    @Test
    fun `produces 28 days ending today, oldest first`() {
        val result = HeatmapAggregator.aggregate(emptyList(), today, ZoneOffset.UTC)

        assertThat(result).hasSize(28)
        assertThat(result.first().date).isEqualTo(today.minusDays(27))
        assertThat(result.last().date).isEqualTo(today)
    }

    @Test
    fun `session hours are attributed to the day they occurred on`() {
        val sessions = listOf(
            HeatmapSessionInterval(
                startTime = instant(today.minusDays(1), 9),
                endTime = instant(today.minusDays(1), 11),
            ),
        )

        val result = HeatmapAggregator.aggregate(sessions, today, ZoneOffset.UTC)

        val yesterday = result.first { it.date == today.minusDays(1) }
        assertThat(yesterday.totalFocusTime.toHours()).isEqualTo(2L)
        assertThat(yesterday.sessionCount).isEqualTo(1)

        val todayEntry = result.first { it.date == today }
        assertThat(todayEntry.totalFocusTime.isZero).isTrue()
    }

    @Test
    fun `still-active session is treated as running until now`() {
        val now = instant(today, 10)
        val sessions = listOf(HeatmapSessionInterval(startTime = instant(today, 8), endTime = null))

        val result = HeatmapAggregator.aggregate(sessions, today, ZoneOffset.UTC, now = now)

        val todayEntry = result.first { it.date == today }
        assertThat(todayEntry.totalFocusTime.toHours()).isEqualTo(2L)
    }

    private fun instant(date: LocalDate, hour: Int): Instant =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)).toInstant(ZoneOffset.UTC)
}
