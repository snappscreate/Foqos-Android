package com.foqos.android

import android.app.Application
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.service.diagnostics.CrashLogger
import com.foqos.android.service.notification.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point for Foqos. Bootstraps Hilt's dependency graph, creates the app's
 * notification channels, and rebuilds in-memory session state (active session, blocking state,
 * foreground service) in case this is a fresh process with a session still active in the database
 * (e.g. after the app was killed by the OS while blocking was ongoing).
 */
@HiltAndroidApp
class FoqosApplication : Application() {

    @Inject
    lateinit var sessionManager: SessionManager

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Installed first, before anything else, so a startup-time crash is captured too - see
        // CrashLogger's kdoc for why this exists (field crash-loops were previously only
        // diagnosable via adb logcat on the affected device).
        CrashLogger.install(this)
        NotificationChannels.createAll(this)
        applicationScope.launch {
            sessionManager.rebuildStateAfterProcessRestart()
        }
    }
}
