package com.foqos.android.service.notification

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.foqos.android.MainActivity
import com.foqos.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Builds and posts the ongoing session notification shown while a profile is actively blocking. */
@Singleton
class SessionNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Builds the ongoing session notification for [profileName], using a chronometer anchored at
     * [startedAtEpochMillis] so the displayed elapsed time keeps ticking without needing repeated
     * updates. [breakActive]/[pauseActive] only affect the status line, not the chronometer.
     */
    fun buildSessionNotification(
        profileName: String,
        startedAtEpochMillis: Long,
        breakActive: Boolean,
        pauseActive: Boolean,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val statusText = when {
            pauseActive -> "Paused"
            breakActive -> "On a break"
            else -> "Focusing"
        }

        return NotificationCompat.Builder(context, NotificationChannels.SESSION_CHANNEL_ID)
            .setContentTitle(profileName)
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(startedAtEpochMillis)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** Posts [notification] under [notificationId], guarded by the runtime POST_NOTIFICATIONS permission on API 33+. */
    fun notify(notificationId: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    companion object {
        const val SESSION_NOTIFICATION_ID = 1001
    }
}
