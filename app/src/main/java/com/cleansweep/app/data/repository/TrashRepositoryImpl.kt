package com.cleansweep.app.data.repository

import androidx.room.withTransaction
import com.cleansweep.app.data.local.db.CleanSweepDatabase
import com.cleansweep.app.data.local.db.MAX_BIND_ARGS_PER_QUERY
import com.cleansweep.app.data.local.db.dao.TrashManifestDao
import com.cleansweep.app.data.mapper.toDomain
import com.cleansweep.app.data.mapper.toEntity
import com.cleansweep.app.domain.model.TrashEntry
import com.cleansweep.app.domain.repository.TrashRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrashRepositoryImpl @Inject constructor(
    private val database: CleanSweepDatabase,
    private val trashManifestDao: TrashManifestDao,
) : TrashRepository {

    override fun observeEntries(): Flow<List<TrashEntry>> =
        trashManifestDao.observeAll().map { entries -> entries.map { it.toDomain() } }

    override fun observeEntryCount(): Flow<Int> = trashManifestDao.observeCount()

    override fun observeTotalBytes(): Flow<Long> = trashManifestDao.observeTotalBytes()

    override suspend fun getAll(): List<TrashEntry> = trashManifestDao.getAll().map { it.toDomain() }

    override suspend fun getByIds(ids: Collection<Long>): List<TrashEntry> =
        ids.distinct().chunked(MAX_BIND_ARGS_PER_QUERY).flatMap { trashManifestDao.getByIds(it) }.map { it.toDomain() }

    override suspend fun getById(id: Long): TrashEntry? = trashManifestDao.getById(id)?.toDomain()

    override suspend fun count(): Int = trashManifestDao.count()

    override suspend fun getByOriginalPath(originalPath: String): TrashEntry? =
        trashManifestDao.getByOriginalPath(originalPath)?.toDomain()

    override suspend fun record(entry: TrashEntry): TrashEntry {
        require(entry.id == 0L) { "New trash entries must not carry an id" }
        val id = trashManifestDao.insert(entry.toEntity())
        return entry.copy(id = id)
    }

    override suspend fun getExpired(now: Long): List<TrashEntry> = trashManifestDao.getExpired(now).map { it.toDomain() }

    override suspend fun removeByIds(ids: Collection<Long>) {
        if (ids.isEmpty()) {
            return
        }
        database.withTransaction {
            ids.distinct().chunked(MAX_BIND_ARGS_PER_QUERY).forEach { trashManifestDao.deleteByIds(it) }
        }
    }

    override suspend fun clearManifest() {
        trashManifestDao.deleteAll()
    }
}
