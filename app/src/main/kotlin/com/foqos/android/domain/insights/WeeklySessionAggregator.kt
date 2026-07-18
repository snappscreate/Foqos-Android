package com.foqos.android.domain.insights

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A completed session's raw wall-clock interval, mirroring iOS `WeeklySessionInterval`. */
data class WeeklySessionInterval(val startTime: Instant, val endTime: Instant)

/** Per-day totals for a week, mirroring iOS `WeeklySessionAggregation`. */
data class WeeklySessionAggregation(
    val dailyDurations: List<Duration>,
    val dailySessionCounts: List<Int>,
    val overlappingSessionCount: Int,
    val totalFocusTime: Duration,
)

/**
 * Buckets completed sessions into per-day totals across a 7-day week. A session spanning multiple
 * days has its time split across each day it actually occurred on. Mirrors iOS
 * `WeeklySessionAggregator` exactly.
 */
object WeeklySessionAggregator {

    fun aggregate(
        sessions: List<WeeklySessionInterval>,
        weekStart: Instant,
        zoneId: ZoneId = ZoneOffset.UTC,
    ): WeeklySessionAggregation {
        val weekEnd = weekStart.plus(Duration.ofDays(7))
        val overlappingSessions = sessions.filter { it.startTime.isBefore(weekEnd) && it.endTime.isAfter(weekStart) }

        val dailyDurations = MutableList(7) { Duration.ZERO }
        val dailySessionCounts = MutableList(7) { 0 }

        for (dayOffset in 0 until 7) {
            val currentDay = weekStart.plus(Duration.ofDays(dayOffset.toLong()))
            val nextDay = currentDay.plus(Duration.ofDays(1))

            for (session in overlappingSessions) {
                val overlapStart = maxOf(session.startTime, currentDay)
                val overlapEnd = minOf(session.endTime, nextDay)

                if (!overlapStart.isBefore(overlapEnd)) continue

                dailyDurations[dayOffset] = dailyDurations[dayOffset].plus(Duration.between(overlapStart, overlapEnd))
                dailySessionCounts[dayOffset]++
            }
        }

        return WeeklySessionAggregation(
            dailyDurations = dailyDurations,
            dailySessionCounts = dailySessionCounts,
            overlappingSessionCount = overlappingSessions.size,
            totalFocusTime = dailyDurations.fold(Duration.ZERO) { acc, d -> acc.plus(d) },
        )
    }

    /**
     * The start of the week containing [date] (at local midnight in [zoneId]), where a week
     * starts on [firstDayOfWeek].
     */
    fun startOfWeek(
        date: Instant,
        zoneId: ZoneId = ZoneOffset.UTC,
        firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
    ): Instant {
        val localDate = date.atZone(zoneId).toLocalDate()
        val daysBack = (localDate.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        return localDate.minusDays(daysBack.toLong()).atStartOfDay(zoneId).toInstant()
    }
}
