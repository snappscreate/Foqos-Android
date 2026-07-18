package com.foqos.android.data.repository

import com.foqos.android.data.db.dao.BlockedProfileDao
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.toDomain
import com.foqos.android.data.model.toEntity
import com.foqos.android.di.IoDispatcher
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** CRUD + ordering + cloning for [BlockedProfile]s, backed by [BlockedProfileDao]. */
@Singleton
class ProfileRepository @Inject constructor(
    private val dao: BlockedProfileDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    fun observeAll(): Flow<List<BlockedProfile>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    fun observeById(id: String): Flow<BlockedProfile?> =
        dao.observeById(id).map { it?.toDomain() }

    suspend fun getById(id: String): BlockedProfile? = withContext(ioDispatcher) {
        dao.getById(id)?.toDomain()
    }

    /** Inserts [profile] as a new profile at the end of the sort order (order is overwritten). */
    suspend fun create(profile: BlockedProfile): BlockedProfile = withContext(ioDispatcher) {
        val nextOrder = nextSortOrder()
        val toSave = profile.copy(sortOrder = nextOrder)
        dao.upsert(toSave.toEntity())
        toSave
    }

    /** Inserts or fully replaces [profile] as-is (sort order is taken from [profile]). */
    suspend fun upsert(profile: BlockedProfile): BlockedProfile = withContext(ioDispatcher) {
        dao.upsert(profile.toEntity())
        profile
    }

    suspend fun delete(id: String) = withContext(ioDispatcher) {
        dao.deleteById(id)
    }

    /** Persists a new relative order for the given profile ids (index in the list = sort order). */
    suspend fun reorder(orderedIds: List<String>) = withContext(ioDispatcher) {
        orderedIds.forEachIndexed { index, id -> dao.updateOrder(id, index) }
    }

    /** Duplicates the profile identified by [profileId] with a new id, "<name> copy" and next sort order. */
    suspend fun clone(profileId: String): BlockedProfile? = withContext(ioDispatcher) {
        val source = dao.getById(profileId)?.toDomain() ?: return@withContext null
        val now = Instant.now()
        val cloned = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} copy",
            sortOrder = nextSortOrder(),
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(cloned.toEntity())
        cloned
    }

    private suspend fun nextSortOrder(): Int = (dao.maxSortOrder() ?: -1) + 1
}
