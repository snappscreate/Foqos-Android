package com.foqos.android.ui.activitylab

import android.content.Context
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.data.model.StrategyPauseTimerData
import com.foqos.android.domain.strategy.StrategyRegistry

/** Separate STOP (ends session) and PAUSE (temporarily suspends blocking) policies. */
enum class UnlockMethod { MANUAL, NFC, QR, ACTIVITY }

data class ProfileUnlockPolicy(
    val stop: UnlockMethod = UnlockMethod.MANUAL,
    val pause: UnlockMethod = UnlockMethod.MANUAL,
    val pauseMinutes: Int = 20,
    val retryCooldownMinutes: Int = 0,
    val stopCredentialId: String? = null,
    val pauseCredentialId: String? = null,
    val pauseMethods: Set<UnlockMethod>? = null,
    val pauseNfcCredentialId: String? = null,
    val pauseQrCredentialId: String? = null,
) {
    fun enabledPauseMethods(): Set<UnlockMethod> =
        pauseMethods ?: if (pause == UnlockMethod.MANUAL) emptySet() else setOf(pause)
    fun pauseCredentialFor(method: UnlockMethod): String? = when (method) {
        UnlockMethod.NFC -> pauseNfcCredentialId ?: if (pause == method) pauseCredentialId else null
        UnlockMethod.QR -> pauseQrCredentialId ?: if (pause == method) pauseCredentialId else null
        else -> null
    }
}

class ProfileUnlockPolicyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("profile_unlock_policy_v1", Context.MODE_PRIVATE)
    private val context = context.applicationContext

    fun configured(profileId: String): Boolean = prefs.getBoolean("enabled_$profileId", false)

    fun read(profileId: String): ProfileUnlockPolicy = ProfileUnlockPolicy(
        stop = parse(prefs.getString("stop_$profileId", null), UnlockMethod.MANUAL),
        pause = parse(prefs.getString("pause_$profileId", null), UnlockMethod.MANUAL),
        pauseMinutes = prefs.getInt("duration_$profileId", 20).coerceIn(1, 180),
        retryCooldownMinutes = prefs.getInt("cooldown_$profileId", 0).coerceIn(0, 10080),
        stopCredentialId = prefs.getString("stop_credential_$profileId", null),
        pauseCredentialId = prefs.getString("pause_credential_$profileId", null),
        pauseMethods = prefs.getString("pause_methods_$profileId", null)?.split(",")
            ?.filter(String::isNotBlank)?.mapNotNull { name ->
                UnlockMethod.entries.firstOrNull { it.name == name && it != UnlockMethod.MANUAL }
            }?.toSet(),
        pauseNfcCredentialId = prefs.getString("pause_nfc_credential_$profileId", null),
        pauseQrCredentialId = prefs.getString("pause_qr_credential_$profileId", null),
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
            .apply { if (policy.pauseMethods == null) remove("pause_methods_$profileId")
                else putString("pause_methods_$profileId", policy.pauseMethods.joinToString(",") { it.name }) }
            .apply { if (policy.pauseNfcCredentialId == null) remove("pause_nfc_credential_$profileId")
                else putString("pause_nfc_credential_$profileId", policy.pauseNfcCredentialId) }
            .apply { if (policy.pauseQrCredentialId == null) remove("pause_qr_credential_$profileId")
                else putString("pause_qr_credential_$profileId", policy.pauseQrCredentialId) }
            .putInt("duration_$profileId", policy.pauseMinutes.coerceIn(1, 180))
            .putInt("cooldown_$profileId", policy.retryCooldownMinutes.coerceIn(0, 10080))
            .apply { if (policy.stopCredentialId == null) remove("stop_credential_$profileId") else putString("stop_credential_$profileId", policy.stopCredentialId) }
            .apply { if (policy.pauseCredentialId == null) remove("pause_credential_$profileId") else putString("pause_credential_$profileId", policy.pauseCredentialId) }
            .commit()) { "解除条件の保存に失敗" }
    }

    /** Validates each selected credential against the profile actually using it. */
    fun isReady(profile: BlockedProfile): Boolean {
        if (!configured(profile.id)) return true // Legacy strategy compatibility.
        val policy = read(profile.id)
        fun valid(method: UnlockMethod, id: String?): Boolean {
            return when (method) {
                UnlockMethod.MANUAL -> true
                UnlockMethod.NFC, UnlockMethod.QR -> {
                    val type = if (method == UnlockMethod.NFC) PhysicalUnblockType.NFC
                        else PhysicalUnblockType.QR_CODE
                    id != null && profile.physicalUnblockItems.any {
                        it.id == id && it.type == type && it.codeValue.isNotBlank()
                    }
                }
                UnlockMethod.ACTIVITY -> ActivityUnlockStore(context).let { activity ->
                    val registered = activity.conditions(profile.id).map { it.id }.toSet()
                    val sets = ActivityChallengeSetStore(context).sets(profile.id)
                    if (sets.isNotEmpty()) sets.all { set ->
                        set.conditionIds.isNotEmpty() && set.conditionIds.all { it in registered }
                    } else if (ActivityChallengeSetStore(context).hasMainSetConfig(profile.id)) false
                    else activity.mainConditionId(profile.id)?.let { it in registered } ?: false
                }
            }
        }
        if (!valid(policy.stop, policy.stopCredentialId)) return false
        return policy.enabledPauseMethods().all { method ->
            when (method) {
                UnlockMethod.ACTIVITY -> {
                    val registered = ActivityUnlockStore(context).conditions(profile.id)
                        .map { it.id }.toSet()
                    val groups = ActivityChallengeSetStore(context).allSets(profile.id)
                    if (groups.isEmpty() && policy.pauseMethods == null) {
                        // Profiles created before set-based AI conditions may still have
                        // a single saved main movement until their first edit-and-save.
                        ActivityUnlockStore(context).mainConditionId(profile.id) in registered
                    } else groups.isNotEmpty() && groups.all { set ->
                        set.conditionIds.isNotEmpty() && set.conditionIds.all { it in registered }
                    }
                }
                else -> valid(method, policy.pauseCredentialFor(method))
            }
        }
    }

    /** The lock begins when a successful timed pause starts, not after it expires. */
    fun pauseRetryRemainingMillis(profileId: String, nowMillis: Long = System.currentTimeMillis()): Long {
        val since = prefs.getLong("last_pause_at_$profileId", 0L)
        if (since <= 0L) return 0L
        val interval = read(profileId).retryCooldownMinutes * 60_000L
        return (since + interval - nowMillis).coerceAtLeast(0L)
    }

    fun markPauseAuthorized(profileId: String, nowMillis: Long = System.currentTimeMillis()) {
        check(prefs.edit().putLong("last_pause_at_$profileId", nowMillis).commit()) {
            "一時休止の待機時間を保存できなかった"
        }
    }

    fun delete(profileId: String) {
        prefs.edit().remove("enabled_$profileId").remove("stop_$profileId")
            .remove("pause_$profileId").remove("duration_$profileId")
            .remove("cooldown_$profileId").remove("last_pause_at_$profileId")
            .remove("stop_credential_$profileId").remove("pause_credential_$profileId")
            .remove("pause_methods_$profileId").remove("pause_nfc_credential_$profileId")
            .remove("pause_qr_credential_$profileId").apply()
    }

    private fun parse(value: String?, default: UnlockMethod) =
        UnlockMethod.entries.firstOrNull { it.name == value } ?: default
}
