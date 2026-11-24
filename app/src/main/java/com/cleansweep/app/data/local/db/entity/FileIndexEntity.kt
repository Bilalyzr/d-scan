package com.cleansweep.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cleansweep.app.domain.model.FileCategory

/**
 * `file_index`: every scanned file plus placeholder rows for suggested empty folders.
 *
 * Columns are the PRD section 6 schema plus four additions:
 * - `category`        lets the dashboard and selective scans aggregate in SQL instead of re-deriving per row.
 * - `created_at`      best-effort creation time for the side-by-side metadata comparison (PRD 4.5).
 * - `is_directory`    marks empty-folder placeholder rows so content queries can exclude them.
 * - `is_original_locked` records an explicit Original Lock chosen by the user.
 *
 * Timestamps are epoch millis. Hash columns hold `algorithm:hex` values and are null until computed.
 */
@Entity(
    tableName = "file_index",
    indices = [
        Index(value = ["path"], unique = true),
        Index(value = ["size_bytes"]),
        Index(value = ["full_hash"]),
        Index(value = ["scan_timestamp"]),
    ],
)
data class FileIndexEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val path: String,
    val name: String,
    val extension: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String?,
    val category: FileCategory,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "last_modified")
    val lastModified: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long?,
    @ColumnInfo(name = "partial_hash")
    val partialHash: String?,
    @ColumnInfo(name = "full_hash")
    val fullHash: String?,
    @ColumnInfo(name = "scan_timestamp")
    val scanTimestamp: Long,
    @ColumnInfo(name = "is_directory")
    val isDirectory: Boolean,
    @ColumnInfo(name = "is_original_locked")
    val isOriginalLocked: Boolean,
)
