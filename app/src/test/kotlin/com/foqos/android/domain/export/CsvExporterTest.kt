package com.foqos.android.domain.export

import com.foqos.android.data.model.BlockedProfileSession
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import org.junit.Test

class CsvExporterTest {

    private fun session(
        id: String,
        startTime: Instant,
        endTime: Instant? = null,
        breakStartTime: Instant? = null,
        breakEndTime: Instant? = null,
    ) = BlockedProfileSession(
        id = id,
        profileId = "profile-1",
        tag = "tag",
        startTime = startTime,
        endTime = endTime,
        breakStartTime = breakStartTime,
        breakEndTime = breakEndTime,
    )

    @Test
    fun `header is always the first line`() {
        val csv = CsvExporter.buildCsv(emptyList(), ZoneOffset.UTC)

        assertThat(csv).isEqualTo("session_id,profile_name,start_time,end_time,break_start_time,break_end_time")
    }

    @Test
    fun `null timestamps render as empty fields`() {
        val s = session(id = "s1", startTime = Instant.parse("2024-03-04T10:00:00Z"))

        val csv = CsvExporter.buildCsv(listOf(s to "Focus"), ZoneOffset.UTC)
        val dataLine = csv.lines()[1]

        assertThat(dataLine).isEqualTo("s1,Focus,2024-03-04T10:00:00.000Z,,,")
    }

    @Test
    fun `all timestamps are formatted as ISO-8601 in the requested timezone`() {
        val s = session(
            id = "s1",
            startTime = Instant.parse("2024-03-04T10:00:00Z"),
            endTime = Instant.parse("2024-03-04T12:00:00Z"),
            breakStartTime = Instant.parse("2024-03-04T11:00:00Z"),
            breakEndTime = Instant.parse("2024-03-04T11:15:00Z"),
        )

        val csv = CsvExporter.buildCsv(listOf(s to "Focus"), ZoneId.of("Europe/Berlin"))
        val dataLine = csv.lines()[1]

        assertThat(dataLine).isEqualTo(
            "s1,Focus,2024-03-04T11:00:00.000+01:00,2024-03-04T13:00:00.000+01:00," +
                "2024-03-04T12:00:00.000+01:00,2024-03-04T12:15:00.000+01:00",
        )
    }

    @Test
    fun `profile names with commas and quotes are escaped`() {
        val s = session(id = "s1", startTime = Instant.parse("2024-03-04T10:00:00Z"))

        val csv = CsvExporter.buildCsv(listOf(s to "Deep Work, \"Focus Mode\""), ZoneOffset.UTC)
        val dataLine = csv.lines()[1]

        assertThat(dataLine).startsWith("s1,\"Deep Work, \"\"Focus Mode\"\"\",")
    }

    @Test
    fun `sessions are ordered by start time ascending or descending`() {
        val earlier = session(id = "early", startTime = Instant.parse("2024-03-04T08:00:00Z"))
        val later = session(id = "late", startTime = Instant.parse("2024-03-04T09:00:00Z"))

        val ascending = CsvExporter.buildCsv(listOf(later to "A", earlier to "B"), ZoneOffset.UTC, ascending = true)
        assertThat(ascending.lines()[1]).startsWith("early")
        assertThat(ascending.lines()[2]).startsWith("late")

        val descending = CsvExporter.buildCsv(listOf(earlier to "A", later to "B"), ZoneOffset.UTC, ascending = false)
        assertThat(descending.lines()[1]).startsWith("late")
        assertThat(descending.lines()[2]).startsWith("early")
    }
}
