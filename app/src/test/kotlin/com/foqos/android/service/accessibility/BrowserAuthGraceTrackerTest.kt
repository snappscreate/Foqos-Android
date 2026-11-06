package com.foqos.android.service.accessibility

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserAuthGraceTrackerTest {
    private val browsers = setOf("com.android.chrome", "org.mozilla.firefox")
    private val allowedApps = setOf("com.example.reader")

    @Test fun `permitted app handoff allows browser for exactly five seconds`() {
        val tracker = BrowserAuthGraceTracker()
        assertThat(tracker.observe("com.example.reader", browsers, allowedApps, 1000L)).isFalse()
        assertThat(tracker.observe("com.android.chrome", browsers, allowedApps, 2000L)).isTrue()
        assertThat(tracker.observe("com.android.chrome", browsers, allowedApps, 6999L)).isTrue()
        assertThat(tracker.observe("com.android.chrome", browsers, allowedApps, 7000L)).isFalse()
    }

    @Test fun `repeated browser events never extend the deadline`() {
        val tracker = BrowserAuthGraceTracker()
        tracker.observe("com.example.reader", browsers, allowedApps, 1000L)
        tracker.observe("com.android.chrome", browsers, allowedApps, 2000L)
        tracker.observe("com.android.chrome", browsers, allowedApps, 4500L)
        assertThat(tracker.observe("com.android.chrome", browsers, allowedApps, 7000L)).isFalse()
    }

    @Test fun `opening browser without a permitted source is never exempted`() {
        val tracker = BrowserAuthGraceTracker()
        tracker.observe("com.example.unapproved", browsers, allowedApps, 1000L)
        assertThat(tracker.observe("com.android.chrome", browsers, allowedApps, 2000L)).isFalse()
    }

    @Test fun `grace ends on leaving browser and cannot transfer to second browser`() {
        val tracker = BrowserAuthGraceTracker()
        tracker.observe("com.example.reader", browsers, allowedApps, 1000L)
        assertThat(tracker.observe("com.android.chrome", browsers, allowedApps, 2000L)).isTrue()
        assertThat(tracker.observe("org.mozilla.firefox", browsers, allowedApps, 3000L)).isFalse()
        assertThat(tracker.observe("com.android.chrome", browsers, allowedApps, 3500L)).isFalse()
    }

    @Test fun `returning to approved app allows a fresh login handoff`() {
        val tracker = BrowserAuthGraceTracker()
        tracker.observe("com.example.reader", browsers, allowedApps, 1000L)
        tracker.observe("com.android.chrome", browsers, allowedApps, 2000L)
        tracker.observe("com.example.reader", browsers, allowedApps, 8000L)
        assertThat(tracker.observe("com.android.chrome", browsers, allowedApps, 9000L)).isTrue()
    }

    @Test fun `no grace when source app loses permission`() {
        val tracker = BrowserAuthGraceTracker()
        tracker.observe("com.example.reader", browsers, allowedApps, 1000L)
        assertThat(tracker.observe("com.android.chrome", browsers, emptySet(), 2000L)).isFalse()
    }
}
