package com.foqos.android.data.model

import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.serialization.Serializable

/**
 * A recurring weekly blocking schedule for a profile, mirroring iOS `BlockedProfileSchedule`.
 *
 * This is a pure data + calculation model (no Android imports, no formatting/strings) - display
 * text such as "Today at 5:00 PM" is a UI-layer concern and is intentionally not implemented here.
 */
@Serializable
data class BlockedProfileSchedule(
    val days: List<Weekday>,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
) {

    /** True if at least one day is configured for this schedule. */
    val isConfigured: Boolean
        get() = days.isNotEmpty()

    fun isScheduledOn(day: Weekday): Boolean = days.contains(day)

    /**
     * Total length of the daily blocking window in seconds. If the end time is strictly earlier
     * than the start time, the window is treated as wrapping past midnight into the next day. A
     * zero-length window (start == end) yields 0.
     */
    val totalDurationInSeconds: Int
        get() {
            val startTotal = startHour * SECONDS_PER_HOUR + startMinute * SECONDS_PER_MINUTE
            val endTotal = endHour * SECONDS_PER_HOUR + endMinute * SECONDS_PER_MINUTE
            val diff = endTotal - startTotal
            return if (diff >= 0) diff else diff + SECONDS_PER_DAY
        }

    /**
     * Whether the schedule's blocking window is active at [now], accounting for windows that
     * wrap past midnight (end time earlier than start time).
     */
    fun isActiveAt(now: LocalDateTime): Boolean {
        if (!isConfigured) return false
        if (startHour == endHour && startMinute == endMinute) return false

        val nowTime = now.toLocalTime()
        val today = Weekday.fromJavaDayOfWeek(now.dayOfWeek)
        val startTime = LocalTime.of(startHour, startMinute)
        val endTime = LocalTime.of(endHour, endMinute)

        return if (endTime.isAfter(startTime)) {
            isScheduledOn(today) && !nowTime.isBefore(startTime) && nowTime.isBefore(endTime)
        } else {
            // Window wraps past midnight: active either because it started earlier today (and
            // hasn't reached endTime tomorrow yet, which is out of scope for "today"), or because
            // it started yesterday and hasn't reached endTime yet today.
            val yesterday = today.previous()
            val startedToday = isScheduledOn(today) && !nowTime.isBefore(startTime)
            val continuingFromYesterday = isScheduledOn(yesterday) && nowTime.isBefore(endTime)
            startedToday || continuingFromYesterday
        }
    }

    /**
     * The next date/time (within the next 7 days) at which this schedule's window starts, at
     * least [bufferMinutes] after [now]. Returns null if no day is configured or no matching day
     * is found in the next week.
     */
    fun nextStartDateTime(now: LocalDateTime, bufferMinutes: Long = DEFAULT_BUFFER_MINUTES): LocalDateTime? {
        if (!isConfigured) return null

        val bufferTime = now.plusMinutes(bufferMinutes)

        for (daysAhead in 0 until 7) {
            val candidateDate = bufferTime.toLocalDate().plusDays(daysAhead.toLong())
            val candidateWeekday = Weekday.fromJavaDayOfWeek(candidateDate.dayOfWeek)
            if (!isScheduledOn(candidateWeekday)) continue

            val scheduleStart = candidateDate.atTime(startHour, startMinute)
            if (!scheduleStart.isBefore(bufferTime)) {
                return scheduleStart
            }
        }

        return null
    }

    companion object {
        private const val SECONDS_PER_MINUTE = 60
        private const val SECONDS_PER_HOUR = 3600
        private const val SECONDS_PER_DAY = 24 * SECONDS_PER_HOUR
        private const val DEFAULT_BUFFER_MINUTES = 15L
    }
}
