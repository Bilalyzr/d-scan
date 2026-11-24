package com.cleansweep.app.domain.repository

import com.cleansweep.app.domain.model.TrashEntry
import kotlinx.coroutines.flow.Flow

/**
 * The Safe Trash manifest: the record of every soft-deleted item and where it came from.
 *
 * Moving and restoring files is the Safe Trash manager's job (Phase 3). This repository only persists the
 * manifest, and it is always written before a file is touched. All methods are main-safe.
 */
interface TrashRepository {

    /** Newest first. */
    fun observeEntries(): Flow<List<TrashEntry>>

    fun observeEntryCount(): Flow<Int>

    /** Bytes currently held in Safe Trash. */
    fun observeTotalBytes(): Flow<Long>

    suspend fun getById(id: Long): TrashEntry?

    suspend fun getByOriginalPath(originalPath: String): TrashEntry?

    /**
     * Records an item that is about to be moved into the trash and returns it with its id.
     * [TrashEntry.expiresAt] must be set through `TrashPolicy.expiresAt(deletedAt)`.
     */
    suspend fun record(entry: TrashEntry): TrashEntry

    /** Entries whose retention window has ended at [now]. */
    suspend fun getExpired(now: Long): List<TrashEntry>

    /** Removes entries after they were restored or purged. This never touches files on disk. */
    suspend fun removeByIds(ids: Collection<Long>)

    /** Empties the manifest. Only valid after the trash directory itself has been wiped. */
    suspend fun clearManifest()
}
