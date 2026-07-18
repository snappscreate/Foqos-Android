package com.foqos.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.service.alarm.AlarmActivityType
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires when one of [com.foqos.android.service.alarm.FoqosAlarmScheduler]'s one-shot alarms goes
 * off. Not exported - it is only ever triggered by [android.app.PendingIntent]s this app created
 * itself. Parses the alarm's identifying extras and hands off to [SessionManager.onAlarm].
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sessionManager: SessionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val typeName = intent.getStringExtra(EXTRA_TYPE) ?: return
        val type = runCatching { AlarmActivityType.valueOf(typeName) }.getOrNull() ?: return
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: return

        val extras = buildMap {
            intent.getStringExtra(EXTRA_SESSION_ID)?.let { put("sessionId", it) }
            intent.getStringExtra(EXTRA_GRANT_ID)?.let { put("grantId", it) }
            intent.getStringExtra(EXTRA_PACKAGE_NAME)?.let { put("packageName", it) }
        }

        val pendingResult = goAsync()
        scope.launch {
            try {
                sessionManager.onAlarm(type, profileId, extras, Instant.now())
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TYPE = "com.foqos.android.extra.ALARM_TYPE"
        const val EXTRA_PROFILE_ID = "com.foqos.android.extra.PROFILE_ID"
        const val EXTRA_SESSION_ID = "com.foqos.android.extra.SESSION_ID"
        const val EXTRA_GRANT_ID = "com.foqos.android.extra.GRANT_ID"
        const val EXTRA_PACKAGE_NAME = "com.foqos.android.extra.PACKAGE_NAME"
        const val EXTRA_KEY = "com.foqos.android.extra.ALARM_KEY"
    }
}
