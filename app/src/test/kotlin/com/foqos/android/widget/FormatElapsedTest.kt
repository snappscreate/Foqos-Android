package com.foqos.android.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Covers [formatElapsed], the widget's static (non-ticking) elapsed-time formatter. */
class FormatElapsedTest {

    @Test
    fun `under an hour formats as MM colon SS`() {
        assertThat(formatElapsed(startEpochMillis = 0L, nowEpochMillis = 65_000L)).isEqualTo("01:05")
    }

    @Test
    fun `an hour or more formats as H colon MM colon SS`() {
        assertThat(formatElapsed(startEpochMillis = 0L, nowEpochMillis = 3_661_000L)).isEqualTo("1:01:01")
    }

    @Test
    fun `now before start clamps to zero instead of going negative`() {
        assertThat(formatElapsed(startEpochMillis = 10_000L, nowEpochMillis = 0L)).isEqualTo("00:00")
    }
}
