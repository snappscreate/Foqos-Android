package com.foqos.android.domain.insights

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** A completed (or still-active) session's raw wall-clock interval for heatmap purposes. */
data class HeatmapSessionInterval(val startTime: Instant, val endTime: Instant?)

/** Total focus time logged on a single calendar day, mirroring the iOS 4-week heatmap. */
data class HeatmapDay(val date: LocalDate, val totalFocusTime: Duration, val sessionCount: Int)

/**
 * Buckets sessions into per-day totals for the last [DAYS_TO_SHOW] days ending on [today]
 * (inclusive), oldest day first - mirrors iOS `FourWeekHeatmapView` /
 * `DashboardActivityUtil.sessionHoursForDate`. Still-active sessions (`endTime == null`) are
 * treated as running until [now].
 */
object HeatmapAggregator {

    const val DAYS_TO_SHOW = 28

    fun aggregate(
        sessions: List<HeatmapSessionInterval>,
        today: LocalDate,
        zoneId: ZoneId = ZoneOffset.UTC,
        now: Instant = Instant.now(),
    ): List<HeatmapDay> {
        val dates = (0 until DAYS_TO_SHOW).map { today.minusDays(it.toLong()) }.sortedBy { it }

        return dates.map { date ->
            val dayStart = date.atStartOfDay(zoneId).toInstant()
            val dayEnd = dayStart.plus(Duration.ofDays(1))

            var total = Duration.ZERO
            var count = 0
            for (session in sessions) {
                val sessionEnd = session.endTime ?: now
                val overlapStart = maxOf(session.startTime, dayStart)
                val overlapEnd = minOf(sessionEnd, dayEnd)
                if (!overlapStart.isBefore(overlapEnd)) continue

                total = total.plus(Duration.between(overlapStart, overlapEnd))
                count++
            }

            HeatmapDay(date = date, totalFocusTime = total, sessionCount = count)
        }
    }
}
