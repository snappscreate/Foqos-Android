package com.foqos.android.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for the soft-unblock allowance state of a single session, mirroring iOS
 * `SoftUnblockSessionState`. One row per active soft-unblock session, keyed by [sessionId].
 */
@Entity(tableName = "soft_unblock_session_state")
data class SoftUnblockSessionStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "maximum_unblock_count") val maximumUnblockCount: Int,
    @ColumnInfo(name = "allowance_reset_interval_in_hours") val allowanceResetIntervalInHours: Int? = null,
    @ColumnInfo(name = "allowance_window_started_at") val allowanceWindowStartedAt: Long,
    @ColumnInfo(name = "next_allowance_reset_at") val nextAllowanceResetAt: Long? = null,
    @ColumnInfo(name = "used_unblock_count") val usedUnblockCount: Int = 0,
)
