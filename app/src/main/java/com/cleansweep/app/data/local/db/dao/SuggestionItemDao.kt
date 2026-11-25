package com.cleansweep.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.cleansweep.app.data.local.db.entity.SuggestionItemEntity
import com.cleansweep.app.data.local.db.projection.SuggestionCategoryTotalRow
import com.cleansweep.app.data.local.db.projection.SuggestionWithFile
import com.cleansweep.app.data.local.db.projection.TierTotalRow
import com.cleansweep.app.domain.model.SuggestionCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface SuggestionItemDao {

    // ---- Read path (Suggestions Hub, dashboard) ----

    /**
     * Active suggestions with their file, highest confidence first. Pass a null [category] for every category
     * and `includeDismissed = true` to also list suggestions the user ignored.
     */
    @Query(
        """
        SELECT s.*,
               f.id                 AS f_id,
               f.path               AS f_path,
               f.name               AS f_name,
               f.extension          AS f_extension,
               f.mime_type          AS f_mime_type,
               f.category           AS f_category,
               f.size_bytes         AS f_size_bytes,
               f.last_modified      AS f_last_modified,
               f.created_at         AS f_created_at,
               f.partial_hash       AS f_partial_hash,
               f.full_hash          AS f_full_hash,
               f.scan_timestamp     AS f_scan_timestamp,
               f.is_directory       AS f_is_directory,
               f.is_original_locked AS f_is_original_locked
        FROM suggestion_items AS s
        INNER JOIN file_index AS f ON f.id = s.file_id
        WHERE (:includeDismissed = 1 OR s.is_dismissed = 0)
          AND (:category IS NULL OR s.category = :category)
        ORDER BY s.confidence_score DESC, f.size_bytes DESC, f.path
        """,
    )
    fun observeWithFile(
        category: SuggestionCategory?,
        includeDismissed: Boolean,
    ): Flow<List<SuggestionWithFile>>

    /**
     * Active suggestions grouped by tier. The cut-offs 80 and 50 repeat [com.cleansweep.app.domain.model.SuggestionTier];
     * SuggestionItemDaoTest fails if the two ever diverge.
     */
    @Query(
        """
        SELECT CASE
                   WHEN s.confidence_score >= 80 THEN 'SAFE_TO_CLEAN'
                   WHEN s.confidence_score >= 50 THEN 'REVIEW_RECOMMENDED'
                   ELSE 'KEEP'
               END AS tier,
               COUNT(*)                       AS item_count,
               COALESCE(SUM(f.size_bytes), 0) AS total_bytes
        FROM suggestion_items AS s
        INNER JOIN file_index AS f ON f.id = s.file_id
        WHERE s.is_dismissed = 0
        GROUP BY tier
        """,
    )
    fun observeTierTotals(): Flow<List<TierTotalRow>>

    @Query(
        """
        SELECT s.category AS category, COUNT(*) AS item_count, COALESCE(SUM(f.size_bytes), 0) AS total_bytes
        FROM suggestion_items AS s
        INNER JOIN file_index AS f ON f.id = s.file_id
        WHERE s.is_dismissed = 0
        GROUP BY s.category
        """,
    )
    fun observeCategoryTotals(): Flow<List<SuggestionCategoryTotalRow>>

    @Query("SELECT COUNT(*) FROM suggestion_items WHERE is_dismissed = 0")
    fun observeActiveCount(): Flow<Int>

    // ---- Write path (scoring engine, user dismissals) ----

    /** Files the user ignored; their next suggestion must be written back as dismissed. */
    @Query("SELECT file_id FROM suggestion_items WHERE is_dismissed = 1")
    suspend fun getDismissedFileIds(): List<Long>

    /** Of [fileIds], the ones whose suggestion the user ignored. Callers chunk. */
    @Query("SELECT file_id FROM suggestion_items WHERE is_dismissed = 1 AND file_id IN (:fileIds)")
    suspend fun getDismissedFileIdsIn(fileIds: Collection<Long>): List<Long>

    /** Of [fileIds], the ones that currently have a suggestion the user has not dismissed. Callers chunk. */
    @Query("SELECT file_id FROM suggestion_items WHERE is_dismissed = 0 AND file_id IN (:fileIds)")
    suspend fun getActiveFileIds(fileIds: Collection<Long>): List<Long>

    /** Callers keep each chunk of [fileIds] within [com.cleansweep.app.data.local.db.MAX_BIND_ARGS_PER_QUERY]. */
    @Query("UPDATE suggestion_items SET is_dismissed = :dismissed WHERE file_id IN (:fileIds)")
    suspend fun setDismissed(fileIds: Collection<Long>, dismissed: Boolean): Int

    @Query("DELETE FROM suggestion_items WHERE file_id IN (:fileIds)")
    suspend fun deleteByFileIds(fileIds: Collection<Long>): Int

    @Query("DELETE FROM suggestion_items")
    suspend fun deleteAll(): Int

    /** Inserts or replaces by `file_id`, which is both the primary key and the foreign key. */
    @Upsert
    suspend fun upsertAll(items: List<SuggestionItemEntity>)
}
