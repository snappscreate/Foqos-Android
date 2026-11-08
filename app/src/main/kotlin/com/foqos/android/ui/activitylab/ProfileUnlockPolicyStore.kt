package com.foqos.android.ui.activitylab

import android.content.Context
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.StrategyPauseTimerData
import com.foqos.android.domain.strategy.StrategyRegistry

/** Separate STOP (ends session) and PAUSE (temporarily suspends blocking) policies. */
enum class UnlockMethod { MANUAL, NFC, QR, ACTIVITY }

data class ProfileUnlockPolicy(
    val stop: UnlockMethod = UnlockMethod.MANUAL,
    val pause: UnlockMethod = UnlockMethod.MANUAL,
    val pauseMinutes: Int = 20,
    val stopCredentialId: String? = null,
    val pauseCredentialId: String? = null,
)

class ProfileUnlockPolicyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("profile_unlock_policy_v1", Context.MODE_PRIVATE)

    fun configured(profileId: String): Boolean = prefs.getBoolean("enabled_$profileId", false)

    fun read(profileId: String): ProfileUnlockPolicy = ProfileUnlockPolicy(
        stop = parse(prefs.getString("stop_$profileId", null), UnlockMethod.MANUAL),
        pause = parse(prefs.getString("pause_$profileId", null), UnlockMethod.MANUAL),
        pauseMinutes = prefs.getInt("duration_$profileId", 20).coerceIn(1, 180),
        stopCredentialId = prefs.getString("stop_credential_$profileId", null),
        pauseCredentialId = prefs.getString("pause_credential_$profileId", null),
    )

    /** Preserve old profiles' behavior until the new settings are explicitly saved. */
    fun forProfile(profile: BlockedProfile): ProfileUnlockPolicy {
        if (configured(profile.id)) return read(profile.id)
        val strategy = StrategyRegistry.byId(profile.strategyId)
        val stop = when {
            strategy.usesNfc -> UnlockMethod.NFC
            strategy.usesQr -> UnlockMethod.QR
            else -> UnlockMethod.MANUAL
        }
        val pause = when {
            strategy.hasPauseMode && strategy.usesNfc -> UnlockMethod.NFC
            strategy.hasPauseMode && strategy.usesQr -> UnlockMethod.QR
            else -> UnlockMethod.MANUAL
        }
        val duration = if (strategy.hasPauseMode) {
            StrategyPauseTimerData.fromJsonOrNull(profile.strategyDataJson).pauseDurationInMinutes
        } else profile.breakTimeInMinutes
        return ProfileUnlockPolicy(stop, pause, duration.coerceIn(1, 180))
    }

    fun save(profileId: String, policy: ProfileUnlockPolicy) {
        require(policy.stop != UnlockMethod.ACTIVITY)
        check(prefs.edit()
            .putBoolean("enabled_$profileId", true)
            .putString("stop_$profileId", policy.stop.name)
            .putString("pause_$profileId", policy.pause.name)
            .putInt("duration_$profileId", policy.pauseMinutes.coerceIn(1, 180))
            .apply { if (policy.stopCredentialId == null) remove("stop_credential_$profileId") else putString("stop_credential_$profileId", policy.stopCredentialId) }
            .apply { if (policy.pauseCredentialId == null) remove("pause_credential_$profileId") else putString("pause_credential_$profileId", policy.pauseCredentialId) }
            .commit()) { "解除条件の保存に失敗" }
    }

    fun delete(profileId: String) {
        prefs.edit().remove("enabled_$profileId").remove("stop_$profileId")
            .remove("pause_$profileId").remove("duration_$profileId")
            .remove("stop_credential_$profileId").remove("pause_credential_$profileId").apply()
    }

    private fun parse(value: String?, default: UnlockMethod) =
        UnlockMethod.entries.firstOrNull { it.name == value } ?: default
}
