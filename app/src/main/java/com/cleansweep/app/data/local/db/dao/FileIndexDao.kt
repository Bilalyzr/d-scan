package com.cleansweep.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.cleansweep.app.data.local.db.entity.FileIndexEntity
import com.cleansweep.app.data.local.db.projection.CategoryTotalRow
import com.cleansweep.app.data.local.db.projection.FileCacheEntry
import com.cleansweep.app.domain.model.FileCategory
import kotlinx.coroutines.flow.Flow

/** Stage 3 of the duplicate pipeline, shared by the one-shot and the observable DAO variant. */
private const val DUPLICATE_FILES_QUERY = """
SELECT f.* FROM file_index AS f
INNER JOIN (
    SELECT full_hash FROM file_index
    WHERE is_directory = 0 AND full_hash IS NOT NULL
    GROUP BY full_hash
    HAVING COUNT(*) > 1
) AS collision ON collision.full_hash = f.full_hash
WHERE f.is_directory = 0 AND f.full_hash IS NOT NULL
ORDER BY f.full_hash, f.last_modified, f.path
"""

@Dao
interface FileIndexDao {

    // ---- Incremental write path (scanner) ----

    /** Callers keep each chunk of [paths] within [com.cleansweep.app.data.local.db.MAX_BIND_ARGS_PER_QUERY]. */
    @Query(
        """
        SELECT id, path, size_bytes, last_modified, partial_hash, full_hash, is_directory, is_original_locked
        FROM file_index
        WHERE path IN (:paths)
        """,
    )
    suspend fun getCacheEntries(paths: Collection<String>): List<FileCacheEntry>

    /**
     * Inserts new rows and updates existing ones by primary key. Callers must set `id` from the cached row when a
     * path is already indexed: upserting by path alone would trip the unique index on `path`.
     * Returns, in order, the new row id for each inserted row and -1 for each row that was updated.
     */
    @Upsert
    suspend fun upsertAll(entries: List<FileIndexEntity>): List<Long>

    /** Directory placeholder rows are never hash candidates; the `is_directory` guard keeps it that way. */
    @Query("UPDATE file_index SET partial_hash = :partialHash WHERE id = :id AND is_directory = 0")
    suspend fun updatePartialHash(id: Long, partialHash: String): Int

    @Query("UPDATE file_index SET full_hash = :fullHash WHERE id = :id AND is_directory = 0")
    suspend fun updateFullHash(id: Long, fullHash: String): Int

    /**
     * Removes files below [root] that were not seen since [scanStartedAt]. Only [categories] are touched, so a
     * selective scan (e.g. images only) leaves the rest of the index intact.
     * [descendantPrefix] comes from `StoragePaths.descendantPrefix(root)`.
     */
    @Query(
        """
        DELETE FROM file_index
        WHERE scan_timestamp < :scanStartedAt
          AND is_directory = 0
          AND category IN (:categories)
          AND (path = :root OR substr(path, 1, length(:descendantPrefix)) = :descendantPrefix)
        """,
    )
    suspend fun deleteStaleFiles(
        scanStartedAt: Long,
        root: String,
        descendantPrefix: String,
        categories: Collection<FileCategory>,
    ): Int

    /** Removes empty-folder placeholder rows that were not refreshed by the analysis at [analysisTimestamp]. */
    @Query("DELETE FROM file_index WHERE is_directory = 1 AND scan_timestamp < :analysisTimestamp")
    suspend fun deleteStaleDirectoryPlaceholders(analysisTimestamp: Long): Int

    // ---- Whitelist enforcement ----

    /** Deleting a row cascades to its suggestions. */
    @Query("DELETE FROM file_index WHERE path = :path")
    suspend fun deleteByPath(path: String): Int

    @Query(
        """
        DELETE FROM file_index
        WHERE path = :path OR substr(path, 1, length(:descendantPrefix)) = :descendantPrefix
        """,
    )
    suspend fun deleteByPathAndDescendants(path: String, descendantPrefix: String): Int

    // ---- Original Lock ----

    @Query("UPDATE file_index SET is_original_locked = :locked WHERE id = :id")
    suspend fun setOriginalLocked(id: Long, locked: Boolean): Int

    // ---- Lookups ----

    @Query("SELECT * FROM file_index WHERE id = :id")
    suspend fun getById(id: Long): FileIndexEntity?

    @Query("SELECT * FROM file_index WHERE id = :id")
    fun observeById(id: Long): Flow<FileIndexEntity?>

    @Query("SELECT * FROM file_index WHERE path = :path")
    suspend fun getByPath(path: String): FileIndexEntity?

    // ---- 3-stage exact duplicate pipeline ----

    /**
     * Stage 1: files whose byte size is shared with at least one other file. Everything else cannot be an exact
     * duplicate and is never hashed.
     */
    @Query(
        """
        SELECT f.* FROM file_index AS f
        INNER JOIN (
            SELECT size_bytes FROM file_index
            WHERE is_directory = 0 AND size_bytes >= :minSizeBytes
            GROUP BY size_bytes
            HAVING COUNT(*) > 1
        ) AS collision ON collision.size_bytes = f.size_bytes
        WHERE f.is_directory = 0
        ORDER BY f.size_bytes DESC, f.path
        """,
    )
    suspend fun getSizeCollisionCandidates(minSizeBytes: Long): List<FileIndexEntity>

    /** Stage 2: files that share both a byte size and a partial hash with at least one other file. */
    @Query(
        """
        SELECT f.* FROM file_index AS f
        INNER JOIN (
            SELECT size_bytes, partial_hash FROM file_index
            WHERE is_directory = 0 AND partial_hash IS NOT NULL
            GROUP BY size_bytes, partial_hash
            HAVING COUNT(*) > 1
        ) AS collision
            ON collision.size_bytes = f.size_bytes AND collision.partial_hash = f.partial_hash
        WHERE f.is_directory = 0 AND f.partial_hash IS NOT NULL
        ORDER BY f.size_bytes DESC, f.partial_hash, f.path
        """,
    )
    suspend fun getPartialHashCollisionCandidates(): List<FileIndexEntity>

    /** Stage 3: files confirmed byte-for-byte identical by their full hash. */
    @Query(DUPLICATE_FILES_QUERY)
    suspend fun getDuplicateFiles(): List<FileIndexEntity>

    @Query(DUPLICATE_FILES_QUERY)
    fun observeDuplicateFiles(): Flow<List<FileIndexEntity>>

    // ---- Dashboard aggregates ----

    @Query("SELECT COUNT(*) FROM file_index WHERE is_directory = 0")
    fun observeFileCount(): Flow<Int>

    @Query(
        """
        SELECT category, COUNT(*) AS file_count, COALESCE(SUM(size_bytes), 0) AS total_bytes
        FROM file_index
        WHERE is_directory = 0
        GROUP BY category
        """,
    )
    fun observeCategoryTotals(): Flow<List<CategoryTotalRow>>

    @Query("DELETE FROM file_index")
    suspend fun deleteAll(): Int
}
