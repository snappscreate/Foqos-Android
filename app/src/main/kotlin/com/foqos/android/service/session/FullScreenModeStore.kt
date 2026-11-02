package com.foqos.android.service.session

import android.content.Context
import org.json.JSONArray
import java.net.URI

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

    /** Restrict web access to exact approved hosts and their subdomains. */
    fun allowedUrls(profileId: String): Set<String> =
        runCatching {
            val json = JSONArray(prefs.getString("${profileId}_urls", "[]"))
            (0 until json.length()).map { json.getString(it) }.toSet()
        }.getOrDefault(emptySet())

    /**
     * A short grace period is needed while the browser opens the requested URL, before its
     * address bar reports the destination. It is not a permanent browser allowance.
     */
    fun grantBrowserStartup(profileId: String, packageName: String, durationMillis: Long = 6_000L) {
        prefs.edit().putString("${profileId}_browser", packageName)
            .putLong("${profileId}_browser_until", System.currentTimeMillis() + durationMillis)
            .apply()
    }

    fun browserStartupUntil(profileId: String, packageName: String): Long =
        if (prefs.getString("${profileId}_browser", null) == packageName)
            prefs.getLong("${profileId}_browser_until", 0L) else 0L

    fun defaultBrowserAllowance(profileId: String, packageName: String): Boolean =
        allowedUrls(profileId).isNotEmpty() && packageName.isNotBlank()

    fun save(profileId: String, enabled: Boolean, allowedPackages: Set<String>) {
        val serialized = JSONArray().apply {
            allowedPackages.sorted().forEach { put(it) }
        }.toString()
        prefs.edit().putBoolean("${profileId}_enabled", enabled)
            .putString("${profileId}_allowed", serialized).commit()
    }

    fun saveUrls(profileId: String, urls: Set<String>) {
        val normalized = urls.mapNotNull { normalizeUrl(it) }.toSet()
        prefs.edit().putString("${profileId}_urls", JSONArray().apply {
            normalized.sorted().forEach { put(it) }
        }.toString()).commit()
    }

    companion object {
        fun normalizeUrl(input: String): String? = runCatching {
            val candidate = input.trim().let {
                if (it.startsWith("https://", true) || it.startsWith("http://", true)) it
                else "https://$it"
            }
            val parsed = URI(candidate)
            val host = parsed.host?.lowercase()?.removePrefix("www.") ?: return@runCatching null
            if (!host.contains('.') || host.any { it.isWhitespace() }) return@runCatching null
            "https://$host"
        }.getOrNull()
    }

    fun delete(profileId: String) {
        prefs.edit().remove("${profileId}_enabled").remove("${profileId}_allowed").remove("${profileId}_urls").apply()
    }
}
