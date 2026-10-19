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

    fun clear() {
        prefs.edit().clear().apply()
    }
}
