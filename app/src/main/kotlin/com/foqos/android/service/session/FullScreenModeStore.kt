package com.foqos.android.service.session

import android.content.Context
import com.foqos.android.ui.block.FocusShortcut
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

    /** Legacy selections were raw package IDs; newer selections are typed destinations. */
    fun featuredShortcuts(profileId: String): Set<String> = runCatching {
        val json = JSONArray(prefs.getString("${profileId}_featured", "[]"))
        (0 until json.length()).map { value ->
            val raw = json.getString(value)
            if (raw.startsWith("app:") || raw.startsWith("url:") || raw == FocusShortcut.PHONE)
                raw else FocusShortcut.app(raw)
        }.toSet()
    }.getOrDefault(emptySet()).filterTo(mutableSetOf()) {
        FocusShortcut.valid(it, allowedPackages(profileId), allowedUrls(profileId))
    }

    fun featuredPackages(profileId: String): Set<String> =
        featuredShortcuts(profileId).filter { it.startsWith(FocusShortcut.APP_PREFIX) }
            .mapTo(mutableSetOf()) { it.removePrefix(FocusShortcut.APP_PREFIX) }

    fun saveFeaturedShortcuts(profileId: String, ids: Set<String>) {
        val packages = allowedPackages(profileId)
        val urls = allowedUrls(profileId)
        val accepted = ids.filter { FocusShortcut.valid(it, packages, urls) }.sorted()
        prefs.edit().putString("${profileId}_featured",
            JSONArray().apply { accepted.forEach { put(it) } }.toString()).commit()
    }

    fun saveFeaturedPackages(profileId: String, packages: Set<String>) =
        saveFeaturedShortcuts(profileId, packages.mapTo(mutableSetOf(), FocusShortcut::app))

    /** Restrict web access to exact approved hosts and their subdomains. */
    fun allowedUrls(profileId: String): Set<String> =
        runCatching {
            val json = JSONArray(prefs.getString("${profileId}_urls", "[]"))
            (0 until json.length()).map { json.getString(it) }.toSet()
        }.getOrDefault(emptySet())

    /** Shared bookmark library managed from the Foqos home screen. */
    fun globalBookmarks(): Map<String, String> = runCatching {
        val json = org.json.JSONObject(prefs.getString("global_bookmarks", "{}") ?: "{}")
        json.keys().asSequence().associateWith { key -> json.getString(key) }
    }.getOrDefault(emptyMap())

    fun saveGlobalBookmark(title: String, address: String): Boolean {
        val url = BookmarkNormalizer.normalizeBookmarkUrl(address) ?: return false
        val label = title.trim().take(80)
        if (label.isBlank()) return false
        val updated = globalBookmarks().toMutableMap()
        updated[url] = label
        return prefs.edit().putString("global_bookmarks",
            org.json.JSONObject(updated).toString()).commit()
    }

    /** Move previously saved profile bookmarks into the shared home library once. */
    fun migrateProfileBookmarks(profileIds: Collection<String>): Boolean {
        if (prefs.getBoolean("global_bookmarks_migrated", false)) return false
        val merged = globalBookmarks().toMutableMap()
        profileIds.forEach { id ->
            bookmarks(id).forEach { (url, title) -> merged.putIfAbsent(url, title) }
        }
        val editor = prefs.edit().putString("global_bookmarks",
            org.json.JSONObject(merged).toString())
            .putBoolean("global_bookmarks_migrated", true)
        profileIds.forEach { editor.remove("${it}_bookmarks") }
        return editor.commit()
    }

    fun removeGlobalBookmark(address: String) {
        val updated = globalBookmarks().toMutableMap()
        updated.remove(address)
        prefs.edit().putString("global_bookmarks",
            org.json.JSONObject(updated).toString()).commit()
    }

    /** Named destinations. A bookmark is not an independent permission grant. */
    fun bookmarks(profileId: String): Map<String, String> = runCatching {
        val json = org.json.JSONObject(prefs.getString("${profileId}_bookmarks", "{}") ?: "{}")
        json.keys().asSequence().associateWith { key -> json.getString(key) }
    }.getOrDefault(emptyMap())

    fun saveBookmark(profileId: String, title: String, address: String): Boolean {
        val normalized = BookmarkNormalizer.normalizeBookmarkUrl(address) ?: return false
        val label = title.trim().take(80)
        if (label.isEmpty()) return false
        val updated = bookmarks(profileId).toMutableMap()
        updated[normalized] = label
        return prefs.edit().putString("${profileId}_bookmarks",
            org.json.JSONObject(updated).toString()).commit()
    }

    fun removeBookmark(profileId: String, address: String) {
        val updated = bookmarks(profileId).toMutableMap()
        updated.remove(address)
        prefs.edit().putString("${profileId}_bookmarks",
            org.json.JSONObject(updated).toString()).commit()
        saveFeaturedShortcuts(profileId, featuredShortcuts(profileId) - FocusShortcut.url(address))
    }

    fun approvedBookmarkUrls(profileId: String): Map<String, String> =
        (bookmarks(profileId) + globalBookmarks()).filterKeys { address ->
            normalizeUrl(address) in allowedUrls(profileId)
        }

    private object BookmarkNormalizer {
        fun normalizeBookmarkUrl(input: String): String? = runCatching {
            val candidate = input.trim().let {
                if (it.startsWith("https://", true) || it.startsWith("http://", true)) it
                else "https://$it"
            }
            val uri = URI(candidate)
            val host = uri.host?.lowercase() ?: return@runCatching null
            if (uri.scheme?.lowercase() != "https" || !host.contains('.') ||
                uri.userInfo != null || uri.port != -1) return@runCatching null
            "https://$host" + (uri.rawPath ?: "") +
                (uri.rawQuery?.let { "?$it" } ?: "") +
                (uri.rawFragment?.let { "#$it" } ?: "")
        }.getOrNull()
    }

    /**
     * A short grace period is needed while the browser opens the requested URL, before its
     * address bar reports the destination. It is not a permanent browser allowance.
     */
    fun grantBrowserStartup(profileId: String, packageName: String, durationMillis: Long = 5_000L) {
        prefs.edit().putString("${profileId}_browser", packageName)
            .putLong("${profileId}_browser_until", System.currentTimeMillis() + durationMillis)
            .apply()
    }

    /** Browser package used for the last approved website launch, not an intent chooser. */
    fun launchedBrowserPackage(profileId: String): String? =
        prefs.getString("${profileId}_browser", null)

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
        saveFeaturedShortcuts(profileId, featuredShortcuts(profileId))
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
        prefs.edit().remove("${profileId}_enabled").remove("${profileId}_allowed").remove("${profileId}_featured").remove("${profileId}_urls").remove("${profileId}_browser").remove("${profileId}_browser_until").remove("${profileId}_bookmarks").apply()
    }
}
