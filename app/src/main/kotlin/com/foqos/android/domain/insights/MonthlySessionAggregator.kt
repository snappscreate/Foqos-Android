package com.foqos.android.domain.insights

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A completed session's raw wall-clock interval, mirroring iOS `MonthlySessionInterval`. */
data class MonthlySessionInterval(val startTime: Instant, val endTime: Instant)

/** Per-day totals for a calendar month, mirroring iOS `MonthlySessionAggregation`. */
data class MonthlySessionAggregation(
    val dailyDurations: List<Duration>,
    val dailySessionCounts: List<Int>,
    val overlappingSessionCount: Int,
    val totalFocusTime: Duration,
    val daysInMonth: Int,
)

/**
 * Buckets completed sessions into per-day totals across a calendar month grid. A session spanning
 * multiple days has its time split across each day it actually occurred on. Mirrors iOS
 * `MonthlySessionAggregator` exactly.
 */
object MonthlySessionAggregator {

    fun aggregate(
        sessions: List<MonthlySessionInterval>,
        monthStart: Instant,
        zoneId: ZoneId = ZoneOffset.UTC,
    ): MonthlySessionAggregation {
        val daysInMonth = daysInMonth(monthStart, zoneId)
        val monthEnd = monthStart.plus(Duration.ofDays(daysInMonth.toLong()))

        val overlappingSessions = sessions.filter { it.startTime.isBefore(monthEnd) && it.endTime.isAfter(monthStart) }

        val dailyDurations = MutableList(daysInMonth) { Duration.ZERO }
        val dailySessionCounts = MutableList(daysInMonth) { 0 }

        for (dayOffset in 0 until daysInMonth) {
            val currentDay = monthStart.plus(Duration.ofDays(dayOffset.toLong()))
            val nextDay = currentDay.plus(Duration.ofDays(1))

            for (session in overlappingSessions) {
                val overlapStart = maxOf(session.startTime, currentDay)
                val overlapEnd = minOf(session.endTime, nextDay)

                if (!overlapStart.isBefore(overlapEnd)) continue

                dailyDurations[dayOffset] = dailyDurations[dayOffset].plus(Duration.between(overlapStart, overlapEnd))
                dailySessionCounts[dayOffset]++
            }
        }

        return MonthlySessionAggregation(
            dailyDurations = dailyDurations,
            dailySessionCounts = dailySessionCounts,
            overlappingSessionCount = overlappingSessions.size,
            totalFocusTime = dailyDurations.fold(Duration.ZERO) { acc, d -> acc.plus(d) },
            daysInMonth = daysInMonth,
        )
    }

    /** Midnight (in [zoneId]) of the 1st of the month containing [date]. */
    fun startOfMonth(date: Instant, zoneId: ZoneId = ZoneOffset.UTC): Instant =
        date.atZone(zoneId).toLocalDate().withDayOfMonth(1).atStartOfDay(zoneId).toInstant()

    /** Number of days in the calendar month containing [date]. */
    fun daysInMonth(date: Instant, zoneId: ZoneId = ZoneOffset.UTC): Int =
        date.atZone(zoneId).toLocalDate().lengthOfMonth()
}
