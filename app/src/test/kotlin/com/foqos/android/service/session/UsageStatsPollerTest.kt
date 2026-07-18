package com.foqos.android.service.session

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.service.accessibility.AccessibilityBlockPolicy
import com.foqos.android.service.overlay.BlockOverlayActivity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * [UsageStatsPoller] is the fallback enforcement path for when the accessibility service isn't
 * enabled - this drives one poll cycle against a Robolectric-shadowed [UsageStatsManager] fed a
 * synthetic `MOVE_TO_FOREGROUND` event, and asserts it results in [BlockOverlayActivity] being
 * launched via the same [AccessibilityBlockPolicy] the accessibility path uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageStatsPollerTest {

    @Test
    fun `poller detects a blocked foreground package and launches the block overlay`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        Shadows.shadowOf(usageStatsManager)
            .addEvent("com.instagram.android", now - 200, UsageEvents.Event.MOVE_TO_FOREGROUND)

        val blockingStateHolder = BlockingStateHolder()
        blockingStateHolder.setState(
            BlockingState(
                profileId = "profile-1",
                sessionId = "session-1",
                profileName = "Test profile",
                blockedPackages = setOf("com.instagram.android"),
                sessionStartEpochMillis = now,
            ),
        )

        val poller = UsageStatsPoller(context, blockingStateHolder, AccessibilityBlockPolicy())
        poller.start(this)
        runCurrent()

        val started = Shadows.shadowOf(context as Application).nextStartedActivity
        assertThat(started).isNotNull()
        assertThat(started.component?.className).isEqualTo(BlockOverlayActivity::class.java.name)
        assertThat(started.getStringExtra(BlockOverlayActivity.EXTRA_PACKAGE_NAME)).isEqualTo("com.instagram.android")

        poller.stop()
    }

    @Test
    fun `poller does not launch an overlay when nothing is blocked`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        Shadows.shadowOf(usageStatsManager)
            .addEvent("com.spotify.music", now - 200, UsageEvents.Event.MOVE_TO_FOREGROUND)

        val blockingStateHolder = BlockingStateHolder()
        blockingStateHolder.setState(
            BlockingState(
                profileId = "profile-1",
                sessionId = "session-1",
                profileName = "Test profile",
                blockedPackages = setOf("com.instagram.android"),
                sessionStartEpochMillis = now,
            ),
        )

        val poller = UsageStatsPoller(context, blockingStateHolder, AccessibilityBlockPolicy())
        poller.start(this)
        runCurrent()

        assertThat(Shadows.shadowOf(context as Application).nextStartedActivity).isNull()

        poller.stop()
    }
}
