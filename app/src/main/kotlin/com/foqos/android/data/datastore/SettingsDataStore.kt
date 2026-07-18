package com.foqos.android.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** App-wide user settings and emergency-unblock allowance state, backed by Preferences DataStore. */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val dataStore: DataStore<Preferences>
        get() = context.settingsPreferencesDataStore

    val themeColorIndex: Flow<Int> =
        dataStore.data.map { it[Keys.THEME_COLOR_INDEX] ?: DEFAULT_THEME_COLOR_INDEX }

    val darkModeOverride: Flow<String> =
        dataStore.data.map { it[Keys.DARK_MODE_OVERRIDE] ?: DEFAULT_DARK_MODE_OVERRIDE }

    val introSeen: Flow<Boolean> =
        dataStore.data.map { it[Keys.INTRO_SEEN] ?: false }

    val emergencyUnblocksRemaining: Flow<Int> =
        dataStore.data.map { it[Keys.EMERGENCY_UNBLOCKS_REMAINING] ?: DEFAULT_EMERGENCY_UNBLOCKS_REMAINING }

    val emergencyResetIntervalWeeks: Flow<Int> =
        dataStore.data.map { it[Keys.EMERGENCY_RESET_INTERVAL_WEEKS] ?: DEFAULT_EMERGENCY_RESET_INTERVAL_WEEKS }

    val emergencyLastResetEpochMillis: Flow<Long> =
        dataStore.data.map { it[Keys.EMERGENCY_LAST_RESET_EPOCH_MILLIS] ?: 0L }

    val pinnedWidgetProfileId: Flow<String?> =
        dataStore.data.map { it[Keys.PINNED_WIDGET_PROFILE_ID] }

    val chartType: Flow<String> =
        dataStore.data.map { it[Keys.CHART_TYPE] ?: DEFAULT_CHART_TYPE }

    suspend fun setThemeColorIndex(index: Int) {
        dataStore.edit { it[Keys.THEME_COLOR_INDEX] = index }
    }

    suspend fun setDarkModeOverride(mode: String) {
        dataStore.edit { it[Keys.DARK_MODE_OVERRIDE] = mode }
    }

    suspend fun setIntroSeen(seen: Boolean) {
        dataStore.edit { it[Keys.INTRO_SEEN] = seen }
    }

    suspend fun setEmergencyUnblocksRemaining(count: Int) {
        dataStore.edit { it[Keys.EMERGENCY_UNBLOCKS_REMAINING] = count }
    }

    suspend fun setEmergencyResetIntervalWeeks(weeks: Int) {
        dataStore.edit { it[Keys.EMERGENCY_RESET_INTERVAL_WEEKS] = weeks }
    }

    suspend fun setEmergencyLastResetEpochMillis(epochMillis: Long) {
        dataStore.edit { it[Keys.EMERGENCY_LAST_RESET_EPOCH_MILLIS] = epochMillis }
    }

    suspend fun setPinnedWidgetProfileId(profileId: String?) {
        dataStore.edit {
            if (profileId == null) {
                it.remove(Keys.PINNED_WIDGET_PROFILE_ID)
            } else {
                it[Keys.PINNED_WIDGET_PROFILE_ID] = profileId
            }
        }
    }

    suspend fun setChartType(type: String) {
        dataStore.edit { it[Keys.CHART_TYPE] = type }
    }

    companion object {
        const val DEFAULT_THEME_COLOR_INDEX = 0
        const val DEFAULT_DARK_MODE_OVERRIDE = "system"
        const val DEFAULT_EMERGENCY_UNBLOCKS_REMAINING = 3
        const val DEFAULT_EMERGENCY_RESET_INTERVAL_WEEKS = 4
        const val DEFAULT_CHART_TYPE = "weekly"
    }

    private object Keys {
        val THEME_COLOR_INDEX = intPreferencesKey("theme_color_index")
        val DARK_MODE_OVERRIDE = stringPreferencesKey("dark_mode_override")
        val INTRO_SEEN = booleanPreferencesKey("intro_seen")
        val EMERGENCY_UNBLOCKS_REMAINING = intPreferencesKey("emergency_unblocks_remaining")
        val EMERGENCY_RESET_INTERVAL_WEEKS = intPreferencesKey("emergency_reset_interval_weeks")
        val EMERGENCY_LAST_RESET_EPOCH_MILLIS = longPreferencesKey("emergency_last_reset_epoch_millis")
        val PINNED_WIDGET_PROFILE_ID = stringPreferencesKey("pinned_widget_profile_id")
        val CHART_TYPE = stringPreferencesKey("chart_type")
    }
}
