package com.foqos.android.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a blocking profile, mirroring iOS `BlockedProfiles`. Lists and nested objects
 * (blocked packages, domains, physical unblock items, schedule, strategy data) are persisted as
 * JSON TEXT columns and (de)serialized in [com.foqos.android.data.model] mappers, not via Room
 * TypeConverters. Timestamps are stored as epoch milliseconds.
 */
@Entity(tableName = "blocked_profiles")
data class BlockedProfileEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    @ColumnInfo(name = "blocked_packages_json") val blockedPackagesJson: String,
    @ColumnInfo(name = "allow_mode_enabled") val allowModeEnabled: Boolean = false,
    @ColumnInfo(name = "domains_json") val domainsJson: String? = null,
    @ColumnInfo(name = "allow_mode_domains") val allowModeDomains: Boolean = false,
    @ColumnInfo(name = "strategy_id") val strategyId: String? = null,
    @ColumnInfo(name = "strategy_data_json") val strategyDataJson: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "reminder_time_in_seconds") val reminderTimeInSeconds: Long? = null,
    @ColumnInfo(name = "custom_reminder_message") val customReminderMessage: String? = null,
    @ColumnInfo(name = "enable_breaks") val enableBreaks: Boolean = false,
    @ColumnInfo(name = "break_time_in_minutes") val breakTimeInMinutes: Int = 15,
    @ColumnInfo(name = "allow_multiple_breaks") val allowMultipleBreaks: Boolean = false,
    @ColumnInfo(name = "enable_strict_mode") val enableStrictMode: Boolean = false,
    @ColumnInfo(name = "enable_block_app_installation") val enableBlockAppInstallation: Boolean = false,
    @ColumnInfo(name = "physical_unblock_items_json") val physicalUnblockItemsJson: String? = null,
    @ColumnInfo(name = "schedule_json") val scheduleJson: String? = null,
    @ColumnInfo(name = "disable_background_stops") val disableBackgroundStops: Boolean = false,
    @ColumnInfo(name = "enable_emergency_unblock") val enableEmergencyUnblock: Boolean = true,
)
