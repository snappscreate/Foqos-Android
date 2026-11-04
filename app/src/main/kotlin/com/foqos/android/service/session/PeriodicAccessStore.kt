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

    fun delete(profileId: String) {
        prefs.edit().remove("${profileId}_wait").remove("${profileId}_access").apply()
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
