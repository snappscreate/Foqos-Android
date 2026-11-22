package com.foqos.android.service.session

import android.content.Context

/**
 * Counts *eligible foreground use* rather than wall time, per blocking session.
 * Only UsageStatsPoller writes; UI only reads. Pauses, breaks and location suspensions
 * are excluded, and a new session has a distinct counter.
 */
class FocusUsageCounterStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "focus_usage_counters_v1", Context.MODE_PRIVATE
    )

    fun elapsedMillis(sessionId: String): Long = prefs.getLong("elapsed_$sessionId", 0L)

    fun add(sessionId: String, deltaMillis: Long) {
        if (deltaMillis <= 0L) return
        val current = elapsedMillis(sessionId)
        prefs.edit().putLong("elapsed_$sessionId", current + deltaMillis).apply()
    }

    fun clear(sessionId: String) {
        prefs.edit().remove("elapsed_$sessionId").apply()
    }
}

/** Stateless calculation kept separately testable from Android foreground sampling. */
object FocusUsageEligibility {
    fun counts(
        state: BlockingState,
        foregroundPackage: String?,
        screenInteractive: Boolean,
    ): Boolean {
        if (state.pauseActive || state.breakActive || state.periodicAccessActive ||
            state.locationSuspended) return false
        return if (state.fullScreenMode) screenInteractive
            else foregroundPackage != null && foregroundPackage in state.blockedPackages
    }
}
