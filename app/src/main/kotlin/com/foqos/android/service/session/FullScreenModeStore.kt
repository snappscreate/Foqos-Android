package com.foqos.android.service.session

import android.content.Context
import org.json.JSONArray

/**
 * Per-profile full-screen focus configuration. Stored separately from Room so existing
 * profile databases and saved NFC registrations are not migrated or overwritten.
 */
class FullScreenModeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "fullscreen_focus_profiles", Context.MODE_PRIVATE
    )

    fun enabled(profileId: String): Boolean = prefs.getBoolean("${profileId}_enabled", false)

    fun allowedPackages(profileId: String): Set<String> =
        runCatching {
            val json = JSONArray(prefs.getString("${profileId}_allowed", "[]"))
            (0 until json.length()).map { json.getString(it) }.toSet()
        }.getOrDefault(emptySet())

    fun save(profileId: String, enabled: Boolean, allowedPackages: Set<String>) {
        val serialized = JSONArray().apply {
            allowedPackages.sorted().forEach { put(it) }
        }.toString()
        prefs.edit().putBoolean("${profileId}_enabled", enabled)
            .putString("${profileId}_allowed", serialized).commit()
    }

    fun delete(profileId: String) {
        prefs.edit().remove("${profileId}_enabled").remove("${profileId}_allowed").apply()
    }
}
