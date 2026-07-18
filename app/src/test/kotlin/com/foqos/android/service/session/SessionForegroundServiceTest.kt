package com.foqos.android.service.session

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.service.notification.NotificationChannels
import com.foqos.android.service.notification.SessionNotificationManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * [SessionForegroundService] can't be instantiated directly in this module's unit tests (it's
 * `@AndroidEntryPoint` and there is no `hilt-android-testing` dependency wired up to fake a Hilt
 * component) - so this covers the two pieces of its logic that matter and *are* independently
 * testable: [SessionForegroundServiceLogic]'s poller decision, and that
 * [SessionNotificationManager] (which the service delegates all notification building/posting to)
 * actually posts a foreground-style notification that reflects break/pause status.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionForegroundServiceTest {

    @Test
    fun `poller runs only when accessibility service is disabled`() {
        assertThat(SessionForegroundServiceLogic.shouldRunPoller(accessibilityServiceEnabled = false)).isTrue()
        assertThat(SessionForegroundServiceLogic.shouldRunPoller(accessibilityServiceEnabled = true)).isFalse()
    }

    @Test
    fun `session notification is posted under the session id and reflects break status`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NotificationChannels.createAll(context)
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val notificationManager = SessionNotificationManager(context)
        val notification = notificationManager.buildSessionNotification(
            profileName = "Deep Work",
            startedAtEpochMillis = 1_000L,
            breakActive = true,
            pauseActive = false,
        )
        notificationManager.notify(SessionNotificationManager.SESSION_NOTIFICATION_ID, notification)

        val systemManager = context.getSystemService(NotificationManager::class.java)
        val posted = Shadows.shadowOf(systemManager).getNotification(SessionNotificationManager.SESSION_NOTIFICATION_ID)

        assertThat(posted).isNotNull()
        assertThat(posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Deep Work")
        assertThat(posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("On a break")
        assertThat(posted.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
    }

    @Test
    fun `session notification reflects focusing status when not on break or paused`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NotificationChannels.createAll(context)
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val notificationManager = SessionNotificationManager(context)
        val notification = notificationManager.buildSessionNotification(
            profileName = "Deep Work",
            startedAtEpochMillis = 1_000L,
            breakActive = false,
            pauseActive = false,
        )

        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("Focusing")
    }
}
