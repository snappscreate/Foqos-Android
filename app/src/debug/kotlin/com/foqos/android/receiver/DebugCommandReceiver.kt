package com.foqos.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.domain.strategy.ManualBlockingStrategy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DEBUG-ONLY. Only registered via `app/src/debug/AndroidManifest.xml`, so this class and its
 * manifest entry never ship in a release build.
 *
 * There is no in-app UI yet to create a blocking profile, so this lets manual/emulator smoke
 * testing of Phase 5 enforcement seed one via adb and force it active, bypassing all the normal
 * strategy UI:
 *
 * ```
 * adb shell am broadcast -a com.foqos.android.DEBUG_CMD --es cmd seed_and_start \
 *     -n com.foqos.android/.receiver.DebugCommandReceiver
 * adb shell am broadcast -a com.foqos.android.DEBUG_CMD --es cmd stop \
 *     -n com.foqos.android/.receiver.DebugCommandReceiver
 * ```
 */
@AndroidEntryPoint
class DebugCommandReceiver : BroadcastReceiver() {

    @Inject
    lateinit var profileRepository: ProfileRepository

    @Inject
    lateinit var sessionManager: SessionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra(EXTRA_CMD)
        Log.w(TAG, "DEBUG-ONLY receiver got cmd=$cmd - never present in a release build")

        val pendingResult = goAsync()
        scope.launch {
            try {
                when (cmd) {
                    CMD_SEED_AND_START -> seedAndStart()
                    CMD_STOP -> stop()
                    else -> Log.w(TAG, "Unknown debug cmd: $cmd")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Debug cmd '$cmd' failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun seedAndStart() {
        val profile = profileRepository.create(
            BlockedProfile(
                id = DEBUG_PROFILE_ID,
                name = "Debug Seed Profile",
                blockedPackages = listOf("com.android.chrome", "com.google.android.deskclock"),
                strategyId = ManualBlockingStrategy.ID,
            ),
        )
        sessionManager.toggleFromDeeplink(profile.id, fromBackground = false)
    }

    private suspend fun stop() {
        if (sessionManager.activeSession.value != null) {
            sessionManager.stopActiveSession(fromBackground = false)
        }
    }

    companion object {
        private const val TAG = "DebugCommandReceiver"
        const val EXTRA_CMD = "cmd"
        const val CMD_SEED_AND_START = "seed_and_start"
        const val CMD_STOP = "stop"
        const val DEBUG_PROFILE_ID = "debug-seed-profile"
    }
}
