package com.foqos.android.service.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * The app's two notification channels, created once at app start. minSdk is already 26
 * (`Build.VERSION_CODES.O`), so notification channels are unconditionally available - no SDK_INT
 * guard needed here.
 */
object NotificationChannels {

    /** Low-importance, ongoing chronometer notification shown while a session is active. */
    const val SESSION_CHANNEL_ID = "session"

    /** Default-importance channel for reminders and other one-off alerts. */
    const val ALERTS_CHANNEL_ID = "alerts"

    fun createAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val sessionChannel = NotificationChannel(
            SESSION_CHANNEL_ID,
            "Active session",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the ongoing focus session timer"
            setShowBadge(false)
        }

        val alertsChannel = NotificationChannel(
            ALERTS_CHANNEL_ID,
            "Alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Reminders and other important updates"
        }

        manager.createNotificationChannels(listOf(sessionChannel, alertsChannel))
    }
}
