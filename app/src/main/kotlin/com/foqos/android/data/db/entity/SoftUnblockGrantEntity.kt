package com.foqos.android.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a single soft-unblock grant (a temporary allow-window for one package within an
 * active soft-unblock session), mirroring iOS `SoftUnblockGrant`.
 */
@Entity(
    tableName = "soft_unblock_grants",
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["profile_id"]),
        Index(value = ["expires_at"]),
    ],
)
data class SoftUnblockGrantEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
)
