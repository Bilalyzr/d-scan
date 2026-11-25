package com.cleansweep.app.data.repository

import androidx.room.withTransaction
import com.cleansweep.app.data.local.db.CleanSweepDatabase
import com.cleansweep.app.data.local.db.MAX_BIND_ARGS_PER_QUERY
import com.cleansweep.app.data.local.db.dao.FileIndexDao
import com.cleansweep.app.data.local.db.dao.SuggestionItemDao
import com.cleansweep.app.data.local.db.entity.FileIndexEntity
import com.cleansweep.app.data.local.db.projection.FileCacheEntry
import com.cleansweep.app.data.mapper.toDomain
import com.cleansweep.app.data.mapper.toEntity
import com.cleansweep.app.domain.model.CategoryTotal
import com.cleansweep.app.domain.model.DuplicateGroup
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.FileHashUpdate
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.util.StoragePaths
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileIndexRepositoryImpl @Inject constructor(
    private val database: CleanSweepDatabase,
    private val fileIndexDao: FileIndexDao,
    private val suggestionItemDao: SuggestionItemDao,
) : FileIndexRepository {

    override suspend fun applyScanBatch(
        observed: List<IndexedFile>,
        scanTimestamp: Long,
    ): List<IndexedFile> {
        val files = observed.distinctBy { it.path }
        if (files.isEmpty()) {
            return emptyList()
        }
        return database.withTransaction {
            val cached = cachedEntriesByPath(files.map { it.path })

            // A path that switched between file and folder keeps its row, but its old suggestion no longer applies.
            val replacedIds = files.mapNotNull { file ->
                val previous = cached[file.path]
                if (previous != null && previous.isDirectory != file.isDirectory) previous.id else null
            }
            if (replacedIds.isNotEmpty()) {
                replacedIds.chunked(MAX_BIND_ARGS_PER_QUERY).forEach { suggestionItemDao.deleteByFileIds(it) }
            }

            val entities = files.map { it.toUpsertEntity(cached[it.path], scanTimestamp) }
            val ids = fileIndexDao.upsertAll(entities)
            entities.mapIndexed { index, entity ->
                entity.toDomain().copy(id = persistedId(entity.id, ids[index]))
            }
        }
    }

    override suspend fun deleteStaleFiles(
        scanStartedAt: Long,
        root: String,
        categories: Collection<FileCategory>,
    ): Int {
        if (categories.isEmpty()) {
            return 0
        }
        val trimmedRoot = root.trimEnd('/')
        return fileIndexDao.deleteStaleFiles(
            scanStartedAt = scanStartedAt,
            root = trimmedRoot,
            descendantPrefix = StoragePaths.descendantPrefix(trimmedRoot),
            categories = categories,
        )
    }

    override suspend fun replaceEmptyFolderPlaceholders(
        directories: List<IndexedFile>,
        analysisTimestamp: Long,
    ): List<IndexedFile> {
        require(directories.all { it.isDirectory }) { "Only directory rows may be passed" }
        val requested = directories.distinctBy { it.path }
        return database.withTransaction {
            val cached = cachedEntriesByPath(requested.map { it.path })

            // A path holds a single row. If a file used to sit where an empty folder is now, that row goes first;
            // its suggestions cascade away with it.
            requested.mapNotNull { cached[it.path]?.takeIf { !it.isDirectory } }
                .forEach { fileIndexDao.deleteByPath(it.path) }

            // A placeholder that already exists keeps its row id, so a suggestion the user dismissed survives.
            val refreshed = requested.map { folder ->
                folder.copy(
                    id = cached[folder.path]?.takeIf { it.isDirectory }?.id ?: 0L,
                    scanTimestamp = analysisTimestamp,
                    isOriginalLocked = false,
                )
            }
            val ids = fileIndexDao.upsertAll(refreshed.map(IndexedFile::toEntity))
            fileIndexDao.deleteStaleDirectoryPlaceholders(analysisTimestamp)
            refreshed.mapIndexed { index, folder ->
                folder.copy(id = persistedId(folder.id, ids[index]))
            }
        }
    }

    override suspend fun getById(id: Long): IndexedFile? = fileIndexDao.getById(id)?.toDomain()

    override suspend fun getByPath(path: String): IndexedFile? = fileIndexDao.getByPath(path)?.toDomain()

    override suspend fun getByIds(ids: Collection<Long>): List<IndexedFile> =
        ids.distinct().chunked(MAX_BIND_ARGS_PER_QUERY).flatMap { fileIndexDao.getByIds(it) }.map { it.toDomain() }

    override fun observeById(id: Long): Flow<IndexedFile?> = fileIndexDao.observeById(id).map { it?.toDomain() }

    override suspend fun getSizeCollisionCandidates(minSizeBytes: Long): List<IndexedFile> =
        fileIndexDao.getSizeCollisionCandidates(minSizeBytes).map(FileIndexEntity::toDomain)

    override suspend fun updatePartialHashes(updates: List<FileHashUpdate>) {
        if (updates.isEmpty()) {
            return
        }
        database.withTransaction {
            updates.forEach { fileIndexDao.updatePartialHash(it.fileId, it.hash) }
        }
    }

    override suspend fun getPartialHashCollisionCandidates(): List<IndexedFile> =
        fileIndexDao.getPartialHashCollisionCandidates().map(FileIndexEntity::toDomain)

    override suspend fun updateFullHashes(updates: List<FileHashUpdate>) {
        if (updates.isEmpty()) {
            return
        }
        database.withTransaction {
            updates.forEach { fileIndexDao.updateFullHash(it.fileId, it.hash) }
        }
    }

    override suspend fun getDuplicateGroups(): List<DuplicateGroup> =
        fileIndexDao.getDuplicateFiles().toDuplicateGroups()

    override fun observeDuplicateGroups(): Flow<List<DuplicateGroup>> =
        fileIndexDao.observeDuplicateFiles().map { it.toDuplicateGroups() }

    override suspend fun getCopiesWithHash(fullHash: String): List<IndexedFile> =
        fileIndexDao.getCopiesWithHash(fullHash).map(FileIndexEntity::toDomain)

    override suspend fun setOriginalLocked(fileId: Long, locked: Boolean) {
        fileIndexDao.setOriginalLocked(fileId, locked)
    }

    override fun observeFileCount(): Flow<Int> = fileIndexDao.observeFileCount()

    override fun observeLastScanTimestamp(): Flow<Long?> = fileIndexDao.observeLastScanTimestamp()

    override fun observeCategoryTotals(): Flow<List<CategoryTotal>> =
        fileIndexDao.observeCategoryTotals().map { rows ->
            rows.map { CategoryTotal(it.category, it.fileCount, it.totalBytes) }
        }

    override suspend fun getJunkCandidates(
        largeFileThresholdBytes: Long,
        junkExtensions: Collection<String>,
    ): List<IndexedFile> =
        fileIndexDao.getJunkCandidates(largeFileThresholdBytes, junkExtensions).map(FileIndexEntity::toDomain)

    override suspend fun removeFiles(paths: Collection<String>) {
        database.withTransaction {
            paths.distinct().chunked(MAX_BIND_ARGS_PER_QUERY).forEach { fileIndexDao.deleteByPaths(it) }
        }
    }

    override suspend fun clearIndex() {
        fileIndexDao.deleteAll()
    }

    /**
     * Builds the row to persist for a file the scan just saw.
     *
     * - Not indexed before: a fresh row without hashes.
     * - Same size and last-modified time: the cached hashes are still valid and are reused. This is what lets a
     *   repeat scan skip hashing unchanged files.
     * - Size or last-modified time changed: the content changed, so both hashes are dropped and the file re-enters
     *   the duplicate pipeline from Stage 1.
     */
    private fun IndexedFile.toUpsertEntity(cached: FileCacheEntry?, scanTimestamp: Long): FileIndexEntity =
        if (cached == null) {
            copy(
                id = 0L,
                partialHash = null,
                fullHash = null,
                scanTimestamp = scanTimestamp,
                isOriginalLocked = false,
            ).toEntity()
        } else {
            val unchanged = cached.sizeBytes == sizeBytes && cached.lastModified == lastModified
            copy(
                id = cached.id,
                partialHash = if (unchanged) cached.partialHash else null,
                fullHash = if (unchanged) cached.fullHash else null,
                scanTimestamp = scanTimestamp,
                isOriginalLocked = cached.isOriginalLocked,
            ).toEntity()
        }

    /**
     * The id a row has after `upsertAll`. Room reports the new row id for an inserted row and -1 for an updated
     * one. A row that already existed carries its id on the entity (new rows carry 0), so that is the only case
     * in which the value returned by Room can be used.
     */
    private fun persistedId(entityId: Long, upsertResult: Long): Long =
        if (entityId != 0L) entityId else upsertResult

    /** Looks up the cached rows for [paths], chunked to stay under SQLite's parameter limit. */
    private suspend fun cachedEntriesByPath(paths: Collection<String>): Map<String, FileCacheEntry> {
        val cached = HashMap<String, FileCacheEntry>()
        paths.distinct().chunked(MAX_BIND_ARGS_PER_QUERY).forEach { chunk ->
            fileIndexDao.getCacheEntries(chunk).forEach { cached[it.path] = it }
        }
        return cached
    }

    private fun List<FileIndexEntity>.toDuplicateGroups(): List<DuplicateGroup> =
        mapNotNull { entity -> entity.fullHash?.let { hash -> hash to entity } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (hash, entities) ->
                if (entities.size < 2) {
                    null
                } else {
                    DuplicateGroup(hash, entities.map(FileIndexEntity::toDomain))
                }
            }
}
