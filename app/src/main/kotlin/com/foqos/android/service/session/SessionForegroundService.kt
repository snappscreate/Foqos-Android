package com.foqos.android.service.session

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.foqos.android.service.accessibility.ForegroundAppAccessibilityService
import com.foqos.android.service.notification.SessionNotificationManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Foreground service (`foregroundServiceType="specialUse"`) that keeps a session's enforcement
 * alive while the app is backgrounded: shows the ongoing chronometer notification (via
 * [SessionNotificationManager]) and, only when the accessibility service isn't enabled, runs
 * [UsageStatsPoller] as a fallback enforcement path. Started/stopped by
 * [RealSessionServiceController], which [com.foqos.android.domain.session.SessionManager] talks to
 * through the [com.foqos.android.domain.session.SessionServiceController] seam.
 *
 * Kept intentionally thin: every actual decision lives in [SessionForegroundServiceLogic] (poller
 * on/off) or [SessionNotificationManager] (notification content), both plain classes that don't
 * need a real Android [Service] instance to test - Hilt-instantiating this class directly isn't
 * possible from this module's unit tests (no `hilt-android-testing` dependency wired up).
 */
@AndroidEntryPoint
class SessionForegroundService : Service() {

    @Inject
    lateinit var blockingStateHolder: BlockingStateHolder

    @Inject
    lateinit var notificationManager: SessionNotificationManager

    @Inject
    lateinit var usageStatsPoller: UsageStatsPoller

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        blockingStateHolder.state
            .onEach { state -> onStateChanged(state) }
            .launchIn(serviceScope)
    }

    // FOREGROUND_SERVICE_TYPE_SPECIAL_USE is an API 34 constant (compile-time inlined), but
    // ServiceCompat.startForeground itself safely handles pre-34 devices - only ever passing the
    // type through on API levels that understand it. minSdk is 26, so InlinedApi is a false
    // positive for this exact, AndroidX-recommended pattern.
    @Suppress("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = blockingStateHolder.state.value
        ServiceCompat.startForeground(
            this,
            SessionNotificationManager.SESSION_NOTIFICATION_ID,
            buildNotification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        return START_STICKY
    }

    private fun onStateChanged(state: BlockingState?) {
        if (state == null) {
            usageStatsPoller.stop()
            return
        }

        notificationManager.notify(SessionNotificationManager.SESSION_NOTIFICATION_ID, buildNotification(state))

        val accessibilityEnabled = PermissionChecks.isAccessibilityEnabled(this, ForegroundAppAccessibilityService::class.java)
        if (SessionForegroundServiceLogic.shouldRunPoller(accessibilityEnabled)) {
            usageStatsPoller.start(serviceScope)
        } else {
            usageStatsPoller.stop()
        }
    }

    private fun buildNotification(state: BlockingState?) = notificationManager.buildSessionNotification(
        profileName = state?.profileName ?: "Focus session",
        startedAtEpochMillis = state?.sessionStartEpochMillis ?: System.currentTimeMillis(),
        breakActive = state?.breakActive ?: false,
        pauseActive = state?.pauseActive ?: false,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        usageStatsPoller.stop()
        serviceScope.cancel()
        super.onDestroy()
    }
}

/** The pure "should the fallback poller be running" decision, factored out of [SessionForegroundService] for direct testing. */
object SessionForegroundServiceLogic {
    /** The poller only ever runs as a fallback for when the (preferred) accessibility path is unavailable. */
    fun shouldRunPoller(accessibilityServiceEnabled: Boolean): Boolean = !accessibilityServiceEnabled
}
