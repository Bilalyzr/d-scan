package com.cleansweep.app.domain.scanner.trash

import com.cleansweep.app.domain.model.TrashEntry
import kotlinx.coroutines.flow.Flow

/**
 * Safe Trash as the screens see it: what it holds, and the three things a user can do about it.
 *
 * [TrashManager] is the implementation. The screens see only this interface, so that a ViewModel can be tested
 * against a fake and never moves a file.
 */
interface SafeTrash {
    /** Every entry in Safe Trash, newest first. */
    fun observeEntries(): Flow<List<TrashEntry>>

    /** Space that emptying Safe Trash would free. */
    fun observeTotalBytes(): Flow<Long>

    /** Moves indexed files to Safe Trash, after checking each one. */
    suspend fun trash(fileIds: Collection<Long>): TrashResult

    /** Puts trashed entries back where they came from. */
    suspend fun restore(entryIds: Collection<Long>): RestoreResult

    /**
     * Deletes every entry for good, whatever its age. This is the only call that cannot be undone, so the UI
     * must have the user's explicit confirmation first.
     */
    suspend fun emptyTrashPermanently(): PurgeResult
}
