package com.foqos.android.ui.common

import com.foqos.android.data.model.BlockedProfileSchedule
import com.foqos.android.data.model.Weekday
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SHORT_DAY_NAMES = mapOf(
    Weekday.SUNDAY to "Sun",
    Weekday.MONDAY to "Mon",
    Weekday.TUESDAY to "Tue",
    Weekday.WEDNESDAY to "Wed",
    Weekday.THURSDAY to "Thu",
    Weekday.FRIDAY to "Fri",
    Weekday.SATURDAY to "Sat",
)

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/** One-line human-readable summary of [schedule], e.g. "Mon, Wed, Fri - 9:00 AM-5:00 PM". */
fun formatScheduleSummary(schedule: BlockedProfileSchedule): String {
    val days = schedule.days.sortedBy { it.ordinal }.joinToString(", ") { SHORT_DAY_NAMES.getValue(it) }
    val start = LocalTime.of(schedule.startHour, schedule.startMinute).format(TIME_FORMATTER)
    val end = LocalTime.of(schedule.endHour, schedule.endMinute).format(TIME_FORMATTER)
    return "$days - $start-$end"
}

fun shortDayName(day: Weekday): String = SHORT_DAY_NAMES.getValue(day)

/** All weekdays in Sun..Sat display order, for schedule day-of-week chip pickers. */
val WEEKDAY_DISPLAY_ORDER: List<Weekday> = listOf(
    Weekday.SUNDAY,
    Weekday.MONDAY,
    Weekday.TUESDAY,
    Weekday.WEDNESDAY,
    Weekday.THURSDAY,
    Weekday.FRIDAY,
    Weekday.SATURDAY,
)
