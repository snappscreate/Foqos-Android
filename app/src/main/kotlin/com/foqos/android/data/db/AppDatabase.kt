package com.foqos.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.foqos.android.data.db.dao.BlockedProfileDao
import com.foqos.android.data.db.dao.BlockedProfileSessionDao
import com.foqos.android.data.db.dao.SoftUnblockDao
import com.foqos.android.data.db.entity.BlockedProfileEntity
import com.foqos.android.data.db.entity.BlockedProfileSessionEntity
import com.foqos.android.data.db.entity.SoftUnblockGrantEntity
import com.foqos.android.data.db.entity.SoftUnblockSessionStateEntity

@Database(
    entities = [
        BlockedProfileEntity::class,
        BlockedProfileSessionEntity::class,
        SoftUnblockGrantEntity::class,
        SoftUnblockSessionStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedProfileDao(): BlockedProfileDao
    abstract fun blockedProfileSessionDao(): BlockedProfileSessionDao
    abstract fun softUnblockDao(): SoftUnblockDao
}
