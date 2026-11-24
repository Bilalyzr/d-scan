package com.cleansweep.app.domain.repository

import com.cleansweep.app.domain.model.CategoryTotal
import com.cleansweep.app.domain.model.DuplicateGroup
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.FileHashUpdate
import com.cleansweep.app.domain.model.IndexedFile
import kotlinx.coroutines.flow.Flow

/**
 * The incremental file index and the persistence half of the 3-stage duplicate pipeline.
 *
 * All methods are main-safe (Room moves disk work off the main thread). Paths are a contract rather than something
 * this repository enforces: every path handed in must already be canonical (see
 * `com.cleansweep.app.domain.util.StoragePaths.canonicalize`), and they are stored as given. The hashing itself
 * lives in the scanning engine; this repository only stores the results.
 */
interface FileIndexRepository {

    // ---- Incremental indexing ----

    /**
     * Persists files observed by a scan and returns them with their ids.
     *
     * For each path the cached row decides what is kept: when size and last-modified time are unchanged the cached
     * hashes stay valid and are reused, so unchanged files are never hashed again. A new or changed file starts
     * with empty hashes. An explicit Original Lock always survives.
     *
     * @param observed Files as the walker saw them, with canonical paths: a symlinked alias such as `/sdcard/...`
     *   has to be resolved before it gets here. Id 0 and no hashes.
     * @param scanTimestamp Start of the current scan, in epoch millis.
     */
    suspend fun applyScanBatch(observed: List<IndexedFile>, scanTimestamp: Long): List<IndexedFile>

    /**
     * Removes files below [root] that the current scan did not see again. Only [categories] are swept, so a
     * selective scan leaves the rest of the index alone. Empty-folder placeholders are never touched here.
     *
     * @param scanStartedAt Start of the current scan, in epoch millis. Must be the same value passed to
     *   [applyScanBatch].
     * @return the number of rows removed.
     */
    suspend fun deleteStaleFiles(
        scanStartedAt: Long,
        root: String,
        categories: Collection<FileCategory>,
    ): Int

    /**
     * Refreshes the placeholder rows for empty folders and returns them with their ids. Only a full scan calls
     * this, because deciding that a folder is empty needs every file in the tree to be indexed.
     *
     * Placeholders keep their row id while the folder stays empty, so a suggestion the user dismissed stays
     * dismissed across scans.
     *
     * @param analysisTimestamp Time of this analysis, in epoch millis.
     */
    suspend fun replaceEmptyFolderPlaceholders(
        directories: List<IndexedFile>,
        analysisTimestamp: Long,
    ): List<IndexedFile>

    suspend fun getById(id: Long): IndexedFile?

    suspend fun getByPath(path: String): IndexedFile?

    fun observeById(id: Long): Flow<IndexedFile?>

    // ---- 3-stage exact duplicate pipeline ----

    /** Stage 1: files whose byte size collides with at least one other file. */
    suspend fun getSizeCollisionCandidates(minSizeBytes: Long): List<IndexedFile>

    /** Stores the Stage 2 partial hashes (first 4 KB + last 4 KB) of the given files. */
    suspend fun updatePartialHashes(updates: List<FileHashUpdate>)

    /** Stage 2: files that share a byte size and a partial hash with at least one other file. */
    suspend fun getPartialHashCollisionCandidates(): List<IndexedFile>

    /** Stores the Stage 3 full SHA-256 hashes of the given files. */
    suspend fun updateFullHashes(updates: List<FileHashUpdate>)

    /** Stage 3: every group of two or more byte-identical files, each ordered oldest-first. */
    suspend fun getDuplicateGroups(): List<DuplicateGroup>

    fun observeDuplicateGroups(): Flow<List<DuplicateGroup>>

    // ---- Original Lock ----

    /**
     * Pins [fileId] as a copy to keep, or clears the pin. Pinning is additive: a group can hold several locked
     * copies, and the caller decides whether unlocking one would leave the group without an Original.
     */
    suspend fun setOriginalLocked(fileId: Long, locked: Boolean)

    // ---- Dashboard ----

    fun observeFileCount(): Flow<Int>

    fun observeCategoryTotals(): Flow<List<CategoryTotal>>

    /** Removes the whole index, suggestions included. Whitelist rules and the trash manifest are untouched. */
    suspend fun clearIndex()
}
