package com.foqos.android.service.location

import android.content.Context

/** One locally stored geofence, kept outside profile schema to avoid a Room migration. */
class LocationAutomationStore(context: Context) {
    private val prefs = context.getSharedPreferences("location_automation", Context.MODE_PRIVATE)

    fun read(): LocationRule? {
        val id = prefs.getString("profileId", null) ?: return null
        return runCatching {
            LocationRule(
                id,
                java.lang.Double.longBitsToDouble(prefs.getLong("latitude", 0L)),
                java.lang.Double.longBitsToDouble(prefs.getLong("longitude", 0L)),
                prefs.getFloat("radius", 200f),
            )
        }.getOrNull()
    }

    fun save(rule: LocationRule) {
        prefs.edit()
            .putString("profileId", rule.profileId)
            .putLong("latitude", java.lang.Double.doubleToRawLongBits(rule.latitude))
            .putLong("longitude", java.lang.Double.doubleToRawLongBits(rule.longitude))
            .putFloat("radius", rule.radiusMeters)
            .apply()
    }

    /**
     * Debug override is persisted across process restarts. Only a genuine ENTER/EXIT
     * transition clears it, not app launch, screen resume, or geofence re-registration.
     */
    fun manualInside(): Boolean? =
        if (prefs.contains("debug_manual_inside")) prefs.getBoolean("debug_manual_inside", false) else null

    fun setManualInside(inside: Boolean) {
        prefs.edit().putBoolean("debug_manual_inside", inside).commit()
    }

    fun clearManualInside() {
        prefs.edit().remove("debug_manual_inside").commit()
    }

    /** Last effective transition, used by the home UI without polling GPS. */
    fun lastInside(): Boolean? =
        if (prefs.contains("last_inside")) prefs.getBoolean("last_inside", false) else null

    fun setLastInside(inside: Boolean) {
        prefs.edit().putBoolean("last_inside", inside).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
