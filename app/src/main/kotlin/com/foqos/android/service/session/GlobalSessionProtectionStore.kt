package com.foqos.android.service.session

import android.content.Context
import com.foqos.android.data.model.BlockedProfile

/** App-wide protection preferences, replacing the former profile-specific switches. */
class GlobalSessionProtectionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "global_session_protection", Context.MODE_PRIVATE
    )

    data class Config(
        val strictMode: Boolean = false,
        val blockAppInstallation: Boolean = false,
        val disableBackgroundStops: Boolean = false,
        val enableEmergencyUnblock: Boolean = true,
    )

    fun isConfigured(): Boolean = prefs.contains("configured")

    fun read(): Config = Config(
        strictMode = prefs.getBoolean("strict", false),
        blockAppInstallation = prefs.getBoolean("install", false),
        disableBackgroundStops = prefs.getBoolean("background_stop", false),
        enableEmergencyUnblock = prefs.getBoolean("emergency", true),
    )

    /** Preserve legacy behavior until the global settings have been explicitly saved. */
    fun readFor(profile: BlockedProfile): Config =
        if (isConfigured()) read() else Config(
            profile.enableStrictMode, profile.enableBlockAppInstallation,
            profile.disableBackgroundStops, profile.enableEmergencyUnblock,
        )

    fun migrate(profiles: List<BlockedProfile>) {
        if (isConfigured() || profiles.isEmpty()) return
        save(Config(
            strictMode = profiles.any { it.enableStrictMode },
            blockAppInstallation = profiles.any { it.enableBlockAppInstallation },
            disableBackgroundStops = profiles.any { it.disableBackgroundStops },
            enableEmergencyUnblock = profiles.any { it.enableEmergencyUnblock },
        ))
    }

    fun save(config: Config) {
        prefs.edit()
            .putBoolean("configured", true)
            .putBoolean("strict", config.strictMode)
            .putBoolean("install", config.blockAppInstallation)
            .putBoolean("background_stop", config.disableBackgroundStops)
            .putBoolean("emergency", config.enableEmergencyUnblock)
            .commit()
    }
}
