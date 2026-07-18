package com.foqos.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.foqos.android.data.db.entity.BlockedProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedProfileDao {

    @Query("SELECT * FROM blocked_profiles ORDER BY sort_order ASC")
    fun observeAll(): Flow<List<BlockedProfileEntity>>

    @Query("SELECT * FROM blocked_profiles WHERE id = :id")
    fun observeById(id: String): Flow<BlockedProfileEntity?>

    @Query("SELECT * FROM blocked_profiles WHERE id = :id")
    suspend fun getById(id: String): BlockedProfileEntity?

    @Upsert
    suspend fun upsert(profile: BlockedProfileEntity)

    @Delete
    suspend fun delete(profile: BlockedProfileEntity)

    @Query("DELETE FROM blocked_profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE blocked_profiles SET sort_order = :sortOrder WHERE id = :id")
    suspend fun updateOrder(id: String, sortOrder: Int)

    @Query("SELECT MAX(sort_order) FROM blocked_profiles")
    suspend fun maxSortOrder(): Int?
}
