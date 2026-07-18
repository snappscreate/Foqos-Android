package com.foqos.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.service.alarm.FoqosAlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-arms every profile's weekly schedule alarms and rebuilds in-memory session state after the
 * device reboots, the app is updated, or (on some OEM skins) exits doze via "quickboot". Alarms
 * scheduled through [android.app.AlarmManager] do not survive a reboot, so this is required for
 * [com.foqos.android.service.alarm.AlarmActivityType.SCHEDULE_START]/`SCHEDULE_END` to keep firing.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var alarmScheduler: FoqosAlarmScheduler

    @Inject
    lateinit var profileRepository: ProfileRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                profileRepository.observeAll().first().forEach { profile ->
                    if (profile.schedule?.isConfigured == true) {
                        alarmScheduler.scheduleWeeklySchedule(profile)
                    }
                }
                sessionManager.rebuildStateAfterProcessRestart()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
