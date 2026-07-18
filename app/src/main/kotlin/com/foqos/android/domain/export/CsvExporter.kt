package com.foqos.android.domain.export

import com.foqos.android.data.model.BlockedProfileSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Builds session-history CSV exports, mirroring iOS `DataExporter.exportSessionsCSV`. */
object CsvExporter {

    private const val HEADER = "session_id,profile_name,start_time,end_time,break_start_time,break_end_time"

    private val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    /**
     * Renders [sessions] (paired with their profile's display name) as CSV text, sorted by
     * session start time ([ascending] or descending) and with all timestamps formatted as
     * ISO-8601 in [timezone]. Missing timestamps (e.g. an active session's `endTime`) render as
     * an empty field.
     */
    fun buildCsv(
        sessions: List<Pair<BlockedProfileSession, String>>,
        timezone: ZoneId,
        ascending: Boolean = true,
    ): String {
        val orderedByStart = sessions.sortedBy { it.first.startTime }
        val ordered = if (ascending) orderedByStart else orderedByStart.asReversed()

        val lines = mutableListOf(HEADER)
        for ((session, profileName) in ordered) {
            val row = listOf(
                session.id,
                profileName,
                formatInstant(session.startTime, timezone),
                formatInstant(session.endTime, timezone),
                formatInstant(session.breakStartTime, timezone),
                formatInstant(session.breakEndTime, timezone),
            ).joinToString(",") { escapeCsvField(it) }
            lines.add(row)
        }

        return lines.joinToString("\n")
    }

    private fun formatInstant(instant: Instant?, timezone: ZoneId): String =
        instant?.let { TIMESTAMP_FORMATTER.format(it.atZone(timezone)) } ?: ""

    private fun escapeCsvField(field: String): String =
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
}
