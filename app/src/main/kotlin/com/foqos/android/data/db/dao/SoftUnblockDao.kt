package com.foqos.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.foqos.android.data.db.entity.SoftUnblockGrantEntity
import com.foqos.android.data.db.entity.SoftUnblockSessionStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SoftUnblockDao {

    @Query("SELECT * FROM soft_unblock_grants WHERE session_id = :sessionId ORDER BY created_at ASC")
    fun grantsForSession(sessionId: String): Flow<List<SoftUnblockGrantEntity>>

    @Query(
        "SELECT * FROM soft_unblock_grants WHERE session_id = :sessionId AND expires_at > :at ORDER BY created_at ASC",
    )
    fun activeGrantsAt(sessionId: String, at: Long): Flow<List<SoftUnblockGrantEntity>>

    @Query(
        "SELECT * FROM soft_unblock_grants WHERE session_id = :sessionId AND expires_at > :at ORDER BY created_at ASC",
    )
    suspend fun activeGrantsAtOnce(sessionId: String, at: Long): List<SoftUnblockGrantEntity>

    @Insert
    suspend fun insertGrant(grant: SoftUnblockGrantEntity)

    @Query("DELETE FROM soft_unblock_grants WHERE expires_at <= :at")
    suspend fun deleteExpiredGrants(at: Long)

    @Query("DELETE FROM soft_unblock_grants WHERE session_id = :sessionId")
    suspend fun deleteGrantsForSession(sessionId: String)

    @Query("SELECT * FROM soft_unblock_session_state WHERE session_id = :sessionId")
    suspend fun getSessionState(sessionId: String): SoftUnblockSessionStateEntity?

    @Upsert
    suspend fun upsertSessionState(state: SoftUnblockSessionStateEntity)

    @Query("DELETE FROM soft_unblock_session_state WHERE session_id = :sessionId")
    suspend fun deleteSessionState(sessionId: String)

    /** Removes all grants and allowance state for [sessionId] (called when a session ends). */
    @Transaction
    suspend fun clearForSession(sessionId: String) {
        deleteGrantsForSession(sessionId)
        deleteSessionState(sessionId)
    }
}
