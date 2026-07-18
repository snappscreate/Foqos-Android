package com.foqos.android.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a single blocking session, mirroring iOS `BlockedProfileSession`. Cascades on
 * profile deletion. Indexed on [profileId] (per-profile history queries) and [endTime] (active
 * session lookup: `end_time IS NULL`).
 */
@Entity(
    tableName = "blocked_profile_sessions",
    foreignKeys = [
        ForeignKey(
            entity = BlockedProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["profile_id"]),
        Index(value = ["end_time"]),
    ],
)
data class BlockedProfileSessionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    /** The scanned code or strategy id used to start this session. */
    val tag: String,
    @ColumnInfo(name = "start_time") val startTime: Long,
    @ColumnInfo(name = "end_time") val endTime: Long? = null,
    @ColumnInfo(name = "break_start_time") val breakStartTime: Long? = null,
    @ColumnInfo(name = "break_end_time") val breakEndTime: Long? = null,
    @ColumnInfo(name = "used_break_duration_in_seconds") val usedBreakDurationInSeconds: Long = 0,
    @ColumnInfo(name = "pause_start_time") val pauseStartTime: Long? = null,
    @ColumnInfo(name = "pause_end_time") val pauseEndTime: Long? = null,
    @ColumnInfo(name = "force_started") val forceStarted: Boolean = false,
)
