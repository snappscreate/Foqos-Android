package com.foqos.android.ui.activitylab

import android.content.Context

/** Persistent non-secret test configuration. Credentials live in ActivityApiKeyStore. */
internal class ActivityLabSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "activity_lab_settings", Context.MODE_PRIVATE
    )

    data class Settings(
        val activity: String = "腕立て伏せをする",
        val mode: String = "PHOTOS",
        val frontCamera: Boolean = false,
        val intervalMillis: Long = 1000L,
        val durationSeconds: Int = 10,
        val countdownSeconds: Int = 5,
        val geminiModel: String = "gemini-3.5-flash-lite",
        val useRelay: Boolean = false,
        val endpoint: String = "",
    )

    fun load(): Settings = Settings(
        activity = prefs.getString("activity", "腕立て伏せをする").orEmpty(),
        mode = prefs.getString("mode", "PHOTOS").orEmpty().takeIf {
            it in setOf("PHOTOS", "VIDEO", "POSE")
        } ?: "PHOTOS",
        frontCamera = prefs.getBoolean("front", false),
        intervalMillis = prefs.getLong("interval", 1000L)
            .takeIf { it in setOf(500L, 1000L, 2000L, 5000L) } ?: 1000L,
        durationSeconds = prefs.getInt("duration", 10).coerceIn(10, 60),
        countdownSeconds = prefs.getInt("countdown", 5).takeIf { it in setOf(0, 3, 5, 10, 15) } ?: 5,
        geminiModel = prefs.getString("model", "gemini-3.5-flash-lite").orEmpty()
            .takeIf { it in setOf("gemini-3.5-flash", "gemini-3.5-flash-lite") }
            ?: "gemini-3.5-flash-lite",
        useRelay = prefs.getBoolean("relay", false),
        endpoint = prefs.getString("endpoint", "").orEmpty(),
    )

    fun save(value: Settings) {
        prefs.edit()
            .putString("activity", value.activity)
            .putString("mode", value.mode)
            .putBoolean("front", value.frontCamera)
            .putLong("interval", value.intervalMillis)
            .putInt("duration", value.durationSeconds)
            .putInt("countdown", value.countdownSeconds)
            .putString("model", value.geminiModel)
            .putBoolean("relay", value.useRelay)
            .putString("endpoint", value.endpoint)
            .apply()
    }
}
