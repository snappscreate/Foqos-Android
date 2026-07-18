package com.foqos.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.foqos.android.domain.session.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the "Stop" action button on the ongoing session notification (see
 * [com.foqos.android.service.notification.SessionNotificationManager.buildSessionNotification]).
 * Not exported - only ever triggered by a [android.app.PendingIntent] this app created itself,
 * same pattern as [AlarmReceiver].
 *
 * Calls [SessionManager.stopActiveSession] with `fromBackground = true` (via [handleStopAction]),
 * so a profile with `disableBackgroundStops` set refuses the stop (emits an error on
 * [SessionManager.errors] instead) and the notification is simply left as-is - there is no
 * dedicated UI for a background broadcast to show that error in, so nothing further updates the
 * notification. This is a documented v1 simplification: a user who taps Stop on a
 * `disableBackgroundStops` profile's notification will see nothing happen and needs to open the
 * app to actually stop it.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sessionManager: SessionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (!shouldHandle(intent)) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                handleStopAction(sessionManager)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP_SESSION = "com.foqos.android.action.STOP_SESSION_FROM_NOTIFICATION"

        /** Pure action-string check, split out purely so it's trivially unit-testable. */
        fun shouldHandle(intent: Intent): Boolean = intent.action == ACTION_STOP_SESSION
    }
}

/**
 * The actual work [NotificationActionReceiver] performs once dispatched, pulled out as a plain
 * suspend function (no `BroadcastReceiver`/`goAsync` involved) so it's directly unit-testable
 * against a real [SessionManager] without Hilt test infrastructure (this codebase has none) or
 * `@AndroidEntryPoint`'s bytecode-injected `inject(this)` call - which runs at the top of every
 * `onReceive` and would otherwise silently overwrite a test-assigned `sessionManager` field with
 * whatever the real `FoqosApplication` Hilt graph happens to provide.
 *
 * Goes through [SessionManager.toggleFromDeeplink] rather than `stopActiveSession`: the latter
 * routes scan-to-stop strategies into a UI request that nothing collects while the app is in the
 * background, so the notification button would silently do nothing for NFC/QR profiles. Background
 * stops force-stop via the Manual strategy instead (still refused when the profile sets
 * `disableBackgroundStops`), mirroring iOS `stopSessionFromBackground`.
 */
internal suspend fun handleStopAction(sessionManager: SessionManager) {
    val session = sessionManager.activeSession.value ?: return
    sessionManager.toggleFromDeeplink(session.profileId, fromBackground = true)
}
