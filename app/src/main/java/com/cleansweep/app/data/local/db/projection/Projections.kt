package com.cleansweep.app.data.local.db.projection

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.cleansweep.app.data.local.db.entity.FileIndexEntity
import com.cleansweep.app.data.local.db.entity.SuggestionItemEntity
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionTier

/** The slice of a `file_index` row the incremental scanner needs to judge cached hashes. */
data class FileCacheEntry(
    val id: Long,
    val path: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "last_modified")
    val lastModified: Long,
    @ColumnInfo(name = "partial_hash")
    val partialHash: String?,
    @ColumnInfo(name = "full_hash")
    val fullHash: String?,
    @ColumnInfo(name = "is_directory")
    val isDirectory: Boolean,
    @ColumnInfo(name = "is_original_locked")
    val isOriginalLocked: Boolean,
)

/** A suggestion joined with the `file_index` row it points at. Join aliases carry the `f_` prefix. */
data class SuggestionWithFile(
    @Embedded
    val suggestion: SuggestionItemEntity,
    @Embedded(prefix = "f_")
    val file: FileIndexEntity,
)

data class CategoryTotalRow(
    val category: FileCategory,
    @ColumnInfo(name = "file_count")
    val fileCount: Int,
    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long,
)

data class SuggestionCategoryTotalRow(
    val category: SuggestionCategory,
    @ColumnInfo(name = "item_count")
    val itemCount: Int,
    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long,
)

/** `tier` is produced by a CASE expression in SuggestionItemDao and must stay in step with [SuggestionTier]. */
data class TierTotalRow(
    val tier: SuggestionTier,
    @ColumnInfo(name = "item_count")
    val itemCount: Int,
    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long,
)
