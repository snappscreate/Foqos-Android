package com.foqos.android.service.session

import android.content.Context

/** Per-profile automatic access breaks. Stored outside Room to avoid a DB migration. */
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

    fun activatedCycle(profileId: String, sessionId: String, anchor: Long): Long {
        if (prefs.getString("${profileId}_activated_session", null) != sessionId ||
            prefs.getLong("${profileId}_activated_anchor", -1L) != anchor) return -1L
        return prefs.getLong("${profileId}_activated_cycle", -1L)
    }

    fun activate(profileId: String, sessionId: String, anchor: Long, cycle: Long) {
        prefs.edit()
            .putString("${profileId}_activated_session", sessionId)
            .putLong("${profileId}_activated_anchor", anchor)
            .putLong("${profileId}_activated_cycle", cycle)
            .commit()
    }

    fun delete(profileId: String) {
        prefs.edit().remove("${profileId}_wait").remove("${profileId}_access")
            .remove("${profileId}_activated_session").remove("${profileId}_activated_anchor")
            .remove("${profileId}_activated_cycle").apply()
    }
}

/**
 * Time-based calculation avoids relying on an exact alarm arriving on time.
 * A cycle is [blocked for waitMinutes, accessible for accessMinutes], repeating.
 * The anchor is the most recent of session start or completion of a manual/NFC pause.
 */
object PeriodicAccessSchedule {
    fun isAccessible(
        config: PeriodicAccessStore.Config,
        anchorEpochMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!config.enabled || nowEpochMillis < anchorEpochMillis) return false
        val wait = config.waitMinutes.toLong() * 60_000L
        val access = config.accessMinutes.toLong() * 60_000L
        val elapsed = (nowEpochMillis - anchorEpochMillis) % (wait + access)
        return elapsed >= wait
    }

    fun cycleIndex(config: PeriodicAccessStore.Config, anchorEpochMillis: Long, nowEpochMillis: Long): Long {
        if (!config.enabled || nowEpochMillis < anchorEpochMillis) return -1L
        val cycle = (config.waitMinutes.toLong() + config.accessMinutes) * 60_000L
        return (nowEpochMillis - anchorEpochMillis) / cycle
    }

    fun remainingWindowMillis(
        config: PeriodicAccessStore.Config, anchorEpochMillis: Long, nowEpochMillis: Long,
    ): Long {
        if (!isAccessible(config, anchorEpochMillis, nowEpochMillis)) return 0L
        val cycle = (config.waitMinutes.toLong() + config.accessMinutes) * 60_000L
        val position = (nowEpochMillis - anchorEpochMillis) % cycle
        return cycle - position
    }

    /** A window is only usable after its specific cycle has been explicitly activated. */
    fun isActive(
        config: PeriodicAccessStore.Config,
        anchorEpochMillis: Long,
        activatedCycle: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean =
        activatedCycle >= 0 &&
            activatedCycle == cycleIndex(config, anchorEpochMillis, nowEpochMillis) &&
            isAccessible(config, anchorEpochMillis, nowEpochMillis)

    fun nextBoundary(
        config: PeriodicAccessStore.Config,
        anchorEpochMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Long? {
        if (!config.enabled) return null
        val wait = config.waitMinutes.toLong() * 60_000L
        val access = config.accessMinutes.toLong() * 60_000L
        val cycle = wait + access
        val elapsed = (nowEpochMillis - anchorEpochMillis).coerceAtLeast(0L)
        val cycles = elapsed / cycle
        val position = elapsed % cycle
        return anchorEpochMillis + cycles * cycle +
            if (position < wait) wait else cycle
    }
}
