package com.foqos.android.service.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FocusUsageEligibilityTest {
    private fun state(fullScreen: Boolean = false) = BlockingState(
        profileId = "profile", sessionId = "session", profileName = "Focus",
        blockedPackages = setOf("com.example.blocked"), fullScreenMode = fullScreen,
    )

    @Test fun regularModeOnlyCountsBlockedForegroundApp() {
        val state = state()
        assertThat(FocusUsageEligibility.counts(state, "com.example.blocked", true)).isTrue()
        assertThat(FocusUsageEligibility.counts(state, "com.example.allowed", true)).isFalse()
        assertThat(FocusUsageEligibility.counts(state, null, true)).isFalse()
    }

    @Test fun fullScreenModeRequiresScreenOn() {
        val state = state(fullScreen = true)
        assertThat(FocusUsageEligibility.counts(state, "com.example.allowed", true)).isTrue()
        assertThat(FocusUsageEligibility.counts(state, "com.example.allowed", false)).isFalse()
        assertThat(FocusUsageEligibility.counts(state, null, false)).isFalse()
    }

    @Test fun pausesAndLocationSuspensionNeverCount() {
        val state = state()
        listOf(
            state.copy(pauseActive = true),
            state.copy(breakActive = true),
            state.copy(locationSuspended = true),
            state.copy(periodicAccessActive = true),
        ).forEach { suspended ->
            assertThat(FocusUsageEligibility.counts(suspended, "com.example.blocked", true)).isFalse()
        }
    }
}
