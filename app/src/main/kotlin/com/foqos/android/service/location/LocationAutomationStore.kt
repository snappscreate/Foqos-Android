package com.foqos.android.service.location

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject

/** Profile-scoped geofences, migrated from the old one-rule preferences. */
class LocationAutomationStore(context: Context) {
    private val prefs = context.getSharedPreferences("location_automation", Context.MODE_PRIVATE)

    private fun ids(): Set<String> = runCatching {
        val json = JSONArray(prefs.getString("rule_profile_ids", "[]"))
        (0 until json.length()).map { json.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    private fun migrateIfNeeded() {
        if (prefs.contains("rule_profile_ids")) return
        val oldId = prefs.getString("profileId", null)
        val editor = prefs.edit()
        if (oldId != null) {
            val obj = JSONObject().put("latitude", java.lang.Double.longBitsToDouble(prefs.getLong("latitude", 0L)))
                .put("longitude", java.lang.Double.longBitsToDouble(prefs.getLong("longitude", 0L)))
                .put("radius", prefs.getFloat("radius", 200f).toDouble())
            editor.putString("rule_$oldId", obj.toString())
            if (prefs.contains("last_inside"))
                editor.putBoolean("last_inside_$oldId", prefs.getBoolean("last_inside", false))
            if (prefs.contains("debug_manual_inside"))
                editor.putBoolean("debug_manual_inside_$oldId", prefs.getBoolean("debug_manual_inside", false))
        }
        editor.putString("rule_profile_ids", JSONArray().apply { if (oldId != null) put(oldId) }.toString()).commit()
    }

    fun readAll(): List<LocationRule> {
        migrateIfNeeded()
        return ids().mapNotNull(::read)
    }

    fun read(profileId: String): LocationRule? {
        migrateIfNeeded()
        val raw = prefs.getString("rule_$profileId", null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            LocationRule(profileId, obj.getDouble("latitude"), obj.getDouble("longitude"),
                obj.getDouble("radius").toFloat())
        }.getOrNull()
    }

    /** Compatibility for old one-profile callers; new code should always pass an ID. */
    fun read(): LocationRule? = readAll().firstOrNull()

    fun save(rule: LocationRule) {
        migrateIfNeeded()
        val obj = JSONObject().put("latitude", rule.latitude).put("longitude", rule.longitude)
            .put("radius", rule.radiusMeters.toDouble())
        val all = ids() + rule.profileId
        prefs.edit().putString("rule_${rule.profileId}", obj.toString())
            .putString("rule_profile_ids", JSONArray(all.sorted()).toString()).commit()
    }

    /** Disabling location automation preserves the saved area without stopping the profile. */
    fun enabled(profileId: String): Boolean =
        read(profileId) != null && prefs.getBoolean("enabled_$profileId", true)

    fun setEnabled(profileId: String, enabled: Boolean) {
        require(read(profileId) != null)
        check(prefs.edit().putBoolean("enabled_$profileId", enabled).commit())
    }

    /** A deliberate complete stop must survive geofence EXIT/ENTER and process restarts. */
    fun manuallyStopped(profileId: String): Boolean =
        prefs.getBoolean("manually_stopped_$profileId", false)

    fun setManuallyStopped(profileId: String, stopped: Boolean) {
        prefs.edit().putBoolean("manually_stopped_$profileId", stopped).commit()
    }

    fun manualInside(profileId: String): Boolean? =
        if (prefs.contains("debug_manual_inside_$profileId"))
            prefs.getBoolean("debug_manual_inside_$profileId", false) else null

    fun setManualInside(profileId: String, inside: Boolean) {
        prefs.edit().putBoolean("debug_manual_inside_$profileId", inside).commit()
    }

    fun clearManualInside(profileId: String) {
        prefs.edit().remove("debug_manual_inside_$profileId").commit()
    }

    fun lastInside(profileId: String): Boolean? =
        if (prefs.contains("last_inside_$profileId"))
            prefs.getBoolean("last_inside_$profileId", false) else null

    fun setLastInside(profileId: String, inside: Boolean) {
        prefs.edit().putBoolean("last_inside_$profileId", inside).commit()
    }

    // Legacy controls are tied to the default rule, never broadcast to all profiles.
    fun manualInside(): Boolean? = read()?.let { manualInside(it.profileId) }
    fun setManualInside(inside: Boolean) { read()?.let { setManualInside(it.profileId, inside) } }
    fun clearManualInside() { read()?.let { clearManualInside(it.profileId) } }
    fun lastInside(): Boolean? = read()?.let { lastInside(it.profileId) }
    fun setLastInside(inside: Boolean) { read()?.let { setLastInside(it.profileId, inside) } }

    fun observeState(profileId: String): Flow<Pair<LocationRule?, Boolean?>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(read(profileId) to (manualInside(profileId) ?: lastInside(profileId)))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read(profileId) to (manualInside(profileId) ?: lastInside(profileId)))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun observeState(): Flow<Pair<LocationRule?, Boolean?>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val rule = read()
            trySend(rule to rule?.let { manualInside(it.profileId) ?: lastInside(it.profileId) })
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val rule = read()
        trySend(rule to rule?.let { manualInside(it.profileId) ?: lastInside(it.profileId) })
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun observeAllStates(): Flow<Map<String, Pair<LocationRule, Boolean?>>> = callbackFlow {
        val snapshot = {
            readAll().filter { enabled(it.profileId) }.associate { rule ->
                rule.profileId to (rule to (manualInside(rule.profileId) ?: lastInside(rule.profileId)))
            }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(snapshot())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(snapshot())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun clear(profileId: String) {
        migrateIfNeeded()
        prefs.edit().remove("rule_$profileId").remove("enabled_$profileId").remove("last_inside_$profileId")
            .remove("debug_manual_inside_$profileId").remove("manually_stopped_$profileId")
            .putString("rule_profile_ids", JSONArray((ids() - profileId).sorted()).toString()).commit()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }
}
