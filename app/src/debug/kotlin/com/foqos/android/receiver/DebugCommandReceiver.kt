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
import kotlinx.coroutines.flow.first
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
                    CMD_SEED_ONLY -> seedOnly()
                    CMD_PRINT_PROFILES -> printProfiles()
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
        val profile = seedOnly()
        sessionManager.toggleFromDeeplink(profile.id, fromBackground = false)
    }

    /**
     * Phase 6 addition: seeds a profile (Manual strategy, so a deep-link toggle works without any
     * strategy UI) but does NOT start a session - lets `adb shell am start -a
     * android.intent.action.VIEW -d "foqos://profile/$DEBUG_PROFILE_ID"` be the thing that starts
     * it, exercising the real deep-link path end to end.
     */
    private suspend fun seedOnly(): BlockedProfile {
        val profile = profileRepository.create(
            BlockedProfile(
                id = DEBUG_PROFILE_ID,
                name = "Debug Seed Profile",
                blockedPackages = listOf("com.android.chrome", "com.google.android.deskclock"),
                strategyId = ManualBlockingStrategy.ID,
            ),
        )
        Log.i(TAG, "Seeded debug profile id=${profile.id}")
        return profile
    }

    /** Phase 6 addition: logs every stored profile id/name so a real uuid can be grabbed from logcat. */
    private suspend fun printProfiles() {
        val profiles = profileRepository.observeAll().first()
        if (profiles.isEmpty()) {
            Log.i(TAG, "No profiles stored")
            return
        }
        profiles.forEach { profile -> Log.i(TAG, "profile id=${profile.id} name=${profile.name} strategyId=${profile.strategyId}") }
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
        const val CMD_SEED_ONLY = "seed_only"
        const val CMD_PRINT_PROFILES = "print_profiles"
        const val CMD_STOP = "stop"
        const val DEBUG_PROFILE_ID = "debug-seed-profile"
    }
}
