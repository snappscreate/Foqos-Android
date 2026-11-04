package com.foqos.android.service.session

import android.content.Context

/** Per-profile scheduled break settings; independent of manual/NFC pauses. */
class PeriodicAccessStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "periodic_access_profiles", Context.MODE_PRIVATE
    )

    data class Config(
        val waitMinutes: Int = 0,
        val accessMinutes: Int = 0,
    ) {
        val enabled: Boolean get() = waitMinutes > 0 && accessMinutes > 0
    }

    fun read(profileId: String): Config = Config(
        prefs.getInt("${profileId}_wait", 0),
        prefs.getInt("${profileId}_access", 0),
    )

    fun save(profileId: String, config: Config) {
        prefs.edit()
            .putInt("${profileId}_wait", config.waitMinutes.coerceIn(0, 1440))
            .putInt("${profileId}_access", config.accessMinutes.coerceIn(0, 1440))
            .commit()
    }

    /** A grant belongs to one session and one block-start anchor only. */
    fun activatedAt(profileId: String, sessionId: String, anchor: Long): Long {
        if (prefs.getString("${profileId}_activated_session", null) != sessionId ||
            prefs.getLong("${profileId}_activated_anchor", -1L) != anchor) return 0L
        return prefs.getLong("${profileId}_activated_at", 0L)
    }

    fun activate(profileId: String, sessionId: String, anchor: Long, nowMillis: Long) {
        prefs.edit()
            .putString("${profileId}_activated_session", sessionId)
            .putLong("${profileId}_activated_anchor", anchor)
            .putLong("${profileId}_activated_at", nowMillis)
            .commit()
    }

    fun delete(profileId: String) {
        prefs.edit().remove("${profileId}_wait").remove("${profileId}_access")
            .remove("${profileId}_activated_session").remove("${profileId}_activated_anchor")
            .remove("${profileId}_activated_at").apply()
    }
}

/** An earned break waits for a click, lasts its full duration, then starts a new wait. */
object PeriodicAccessSchedule {
    private fun waitMs(config: PeriodicAccessStore.Config) = config.waitMinutes.toLong() * 60_000L
    private fun accessMs(config: PeriodicAccessStore.Config) = config.accessMinutes.toLong() * 60_000L

    fun isActive(
        config: PeriodicAccessStore.Config,
        anchorEpochMillis: Long,
        activatedAtMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean =
        config.enabled && activatedAtMillis >= anchorEpochMillis && activatedAtMillis > 0L &&
            nowEpochMillis >= activatedAtMillis &&
            nowEpochMillis < activatedAtMillis + accessMs(config)

    fun isAvailable(
        config: PeriodicAccessStore.Config,
        anchorEpochMillis: Long,
        activatedAtMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!config.enabled || nowEpochMillis < anchorEpochMillis ||
            isActive(config, anchorEpochMillis, activatedAtMillis, nowEpochMillis)) return false
        val lastEnd = if (activatedAtMillis >= anchorEpochMillis && activatedAtMillis > 0L)
            activatedAtMillis + accessMs(config) else anchorEpochMillis
        return nowEpochMillis >= lastEnd + waitMs(config)
    }

    fun remainingMillis(
        config: PeriodicAccessStore.Config,
        anchorEpochMillis: Long,
        activatedAtMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Long =
        if (isActive(config, anchorEpochMillis, activatedAtMillis, nowEpochMillis))
            (activatedAtMillis + accessMs(config) - nowEpochMillis).coerceAtLeast(0L) else 0L

    /** No further alarm is required once a new break becomes available for manual activation. */
    fun nextBoundary(
        config: PeriodicAccessStore.Config,
        anchorEpochMillis: Long,
        activatedAtMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Long? {
        if (!config.enabled) return null
        if (isActive(config, anchorEpochMillis, activatedAtMillis, nowEpochMillis))
            return activatedAtMillis + accessMs(config)
        val previousEnd = if (activatedAtMillis >= anchorEpochMillis && activatedAtMillis > 0L)
            activatedAtMillis + accessMs(config) else anchorEpochMillis
        val availableAt = previousEnd + waitMs(config)
        return availableAt.takeIf { it > nowEpochMillis }
    }
}
