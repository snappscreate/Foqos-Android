package com.foqos.android.service.accessibility

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserUrlContinuityTrackerTest {
    @Test fun allowedPageRetainedWhenAddressBarTemporarilyDisappears() {
        val tracker = BrowserUrlContinuityTracker()
        assertThat(tracker.observe("com.android.chrome", 11, "https://eden.ac", true, 1_000L))
            .isEqualTo("https://eden.ac")
        assertThat(tracker.observe("com.android.chrome", 11, null, false, 16_000L))
            .isEqualTo("https://eden.ac")
        assertThat(tracker.observe("com.android.chrome", 11, "https://eden.ac/course/1", true, 20_000L))
            .isEqualTo("https://eden.ac/course/1")
    }

    @Test fun unknownUrlNeverBecomesAllowedAndExplicitForeignAddressClearsCache() {
        val tracker = BrowserUrlContinuityTracker()
        assertThat(tracker.observe("com.android.chrome", 9, null, false, 1_000L)).isNull()
        tracker.observe("com.android.chrome", 9, "https://eden.ac/page", true, 2_000L)
        assertThat(tracker.observe("com.android.chrome", 9, "https://youtube.com", false, 3_000L))
            .isEqualTo("https://youtube.com")
        assertThat(tracker.observe("com.android.chrome", 9, null, false, 3_500L)).isNull()
    }

    @Test fun browserSwitchWindowSwitchOrLongMissingPeriodInvalidatesCache() {
        val tracker = BrowserUrlContinuityTracker()
        tracker.observe("com.android.chrome", 10, "https://eden.ac", true, 1_000L)
        assertThat(tracker.observe("com.android.chrome", 11, null, false, 2_000L)).isNull()
        tracker.observe("com.android.chrome", 11, "https://eden.ac", true, 3_000L)
        assertThat(tracker.observe("org.mozilla.firefox", 11, null, false, 4_000L)).isNull()
        tracker.observe("org.mozilla.firefox", 11, "https://eden.ac", true, 5_000L)
        assertThat(tracker.observe("org.mozilla.firefox", 11, null, false, 125_001L)).isNull()
    }
}
