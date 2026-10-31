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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** CRUD + ordering + cloning for [BlockedProfile]s, backed by [BlockedProfileDao]. */
@Singleton
class ProfileRepository @Inject constructor(
    private val dao: BlockedProfileDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    // Physical items and the profile editor must share one write lock; otherwise a stale
    // editor draft can overwrite a just-registered NFC code.
    private val writeMutex = Mutex()

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

    /**
     * Save the editable profile fields without ever replacing separately managed NFC/QR items.
     * Read+merge+write are serialized with [updatePhysicalUnblockItems].
     */
    suspend fun saveEditorDraft(profile: BlockedProfile): BlockedProfile = withContext(ioDispatcher) {
        writeMutex.withLock {
            val persisted = dao.getById(profile.id)?.toDomain()
            val merged = profile.copy(
                physicalUnblockItems = persisted?.physicalUnblockItems ?: profile.physicalUnblockItems,
            )
            dao.upsert(merged.toEntity())
            merged
        }
    }

    suspend fun updatePhysicalUnblockItems(
        id: String,
        transform: (List<com.foqos.android.data.model.PhysicalUnblockItem>) ->
            List<com.foqos.android.data.model.PhysicalUnblockItem>,
    ): BlockedProfile? = withContext(ioDispatcher) {
        writeMutex.withLock {
            val current = dao.getById(id)?.toDomain() ?: return@withLock null
            val updated = current.copy(
                physicalUnblockItems = transform(current.physicalUnblockItems),
                updatedAt = Instant.now(),
            )
            dao.upsert(updated.toEntity())
            updated
        }
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
