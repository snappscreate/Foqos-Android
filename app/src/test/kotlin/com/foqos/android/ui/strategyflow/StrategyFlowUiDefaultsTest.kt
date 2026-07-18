package com.foqos.android.ui.strategyflow

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure logic behind the [StrategyUiRequestSheet] duration pickers - kept Compose-free so it's
 * plain-JVM-testable.
 *
 * NOTE ON COVERAGE: a true `createComposeRule` render test for [StrategyUiRequestSheet] (e.g.
 * "a PickTimerDuration request renders the duration chips/confirm button") needs
 * `androidx.compose.ui:ui-test-junit4` on the `testImplementation` classpath. That artifact is
 * currently wired only as `androidTestImplementation` (instrumented tests) - Phase 6's gradle
 * changes are scoped to exactly the two zxing entries per the phase brief, so adding it here would
 * be a third, out-of-scope dependency wiring. This is flagged as a deviation in the phase report;
 * the sheet-selection/duration-resolution logic itself is fully covered below instead.
 */
class StrategyFlowUiDefaultsTest {

    @Test
    fun `timer quick chips match the phase spec`() {
        assertThat(StrategyFlowUiDefaults.TIMER_QUICK_MINUTES).containsExactly(15, 30, 45, 60, 90, 120).inOrder()
    }

    @Test
    fun `pause quick chips match the phase spec`() {
        assertThat(StrategyFlowUiDefaults.PAUSE_QUICK_MINUTES).containsExactly(5, 10, 15, 30).inOrder()
    }

    @Test
    fun `soft unblock reset options include none plus the three interval hours`() {
        assertThat(StrategyFlowUiDefaults.SOFT_UNBLOCK_RESET_OPTIONS).containsExactly(null, 6, 12, 24).inOrder()
    }

    @Test
    fun `resolveMinutes prefers a valid custom value over the fallback`() {
        assertThat(StrategyFlowUiDefaults.resolveMinutes(customText = "42", fallbackMinutes = 15)).isEqualTo(42)
    }

    @Test
    fun `resolveMinutes falls back to the fallback when custom text is blank`() {
        assertThat(StrategyFlowUiDefaults.resolveMinutes(customText = "", fallbackMinutes = 30)).isEqualTo(30)
        assertThat(StrategyFlowUiDefaults.resolveMinutes(customText = "   ", fallbackMinutes = 30)).isEqualTo(30)
    }

    @Test
    fun `resolveMinutes falls back to the fallback when custom text is not a positive integer`() {
        assertThat(StrategyFlowUiDefaults.resolveMinutes(customText = "abc", fallbackMinutes = 60)).isEqualTo(60)
        assertThat(StrategyFlowUiDefaults.resolveMinutes(customText = "0", fallbackMinutes = 60)).isEqualTo(60)
        assertThat(StrategyFlowUiDefaults.resolveMinutes(customText = "-5", fallbackMinutes = 60)).isEqualTo(60)
    }

    @Test
    fun `resolveMinutes trims whitespace around a valid custom value`() {
        assertThat(StrategyFlowUiDefaults.resolveMinutes(customText = "  20  ", fallbackMinutes = 15)).isEqualTo(20)
    }
}
