package com.foqos.android.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.foqos.android.data.db.entity.BlockedProfileSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedProfileSessionDao {

    @Upsert
    suspend fun upsert(session: BlockedProfileSessionEntity)

    @Query("SELECT * FROM blocked_profile_sessions WHERE id = :id")
    suspend fun getById(id: String): BlockedProfileSessionEntity?

    /** The most recently started session that has not yet ended, if any. */
    @Query("SELECT * FROM blocked_profile_sessions WHERE end_time IS NULL ORDER BY start_time DESC LIMIT 1")
    suspend fun activeSessionOnce(): BlockedProfileSessionEntity?

    /** The most recently started session that has not yet ended, if any, as a live stream. */
    @Query("SELECT * FROM blocked_profile_sessions WHERE end_time IS NULL ORDER BY start_time DESC LIMIT 1")
    fun observeActiveSession(): Flow<BlockedProfileSessionEntity?>

    @Query("SELECT * FROM blocked_profile_sessions WHERE profile_id = :profileId ORDER BY start_time DESC")
    fun sessionsForProfile(profileId: String): Flow<List<BlockedProfileSessionEntity>>

    @Query("SELECT * FROM blocked_profile_sessions WHERE end_time IS NOT NULL ORDER BY end_time DESC LIMIT :limit")
    suspend fun recentInactiveSessions(limit: Int = 50): List<BlockedProfileSessionEntity>

    /** All sessions whose start time falls within [from, to), ascending - used for insights. */
    @Query("SELECT * FROM blocked_profile_sessions WHERE start_time >= :from AND start_time < :to ORDER BY start_time ASC")
    suspend fun sessionsBetween(from: Long, to: Long): List<BlockedProfileSessionEntity>

    @Query("DELETE FROM blocked_profile_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM blocked_profile_sessions WHERE profile_id = :profileId")
    suspend fun deleteAllForProfile(profileId: String)
}
