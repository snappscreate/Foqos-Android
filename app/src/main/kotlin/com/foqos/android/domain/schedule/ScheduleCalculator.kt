package com.foqos.android.domain.schedule

import com.foqos.android.data.model.BlockedProfileSchedule
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Thin wrapper around [BlockedProfileSchedule]'s pure calculations, exposing the
 * [Instant]-based shape the alarm-scheduling layer (Phase 4) needs. Display text (e.g. "Today at
 * 5:00 PM") remains a UI-layer concern and is intentionally not implemented here.
 */
object ScheduleCalculator {

    /**
     * The next start/end instants (within the next 7 days) for [schedule], or `null` for either
     * if no day is configured / no matching day is found. The end instant is `null` if the
     * schedule's window has zero duration.
     */
    fun nextOccurrences(
        schedule: BlockedProfileSchedule,
        now: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
        bufferMinutes: Long = 15L,
    ): Pair<Instant?, Instant?> {
        val nowLocal = LocalDateTime.ofInstant(now, zoneId)
        val nextStartLocal = schedule.nextStartDateTime(nowLocal, bufferMinutes) ?: return null to null
        val nextStart = nextStartLocal.atZone(zoneId).toInstant()

        val durationInSeconds = schedule.totalDurationInSeconds
        val nextEnd = if (durationInSeconds > 0) nextStart.plusSeconds(durationInSeconds.toLong()) else null

        return nextStart to nextEnd
    }
}
