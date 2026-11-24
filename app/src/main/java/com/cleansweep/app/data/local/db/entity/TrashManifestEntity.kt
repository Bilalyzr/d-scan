package com.cleansweep.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `trash_manifest`: items waiting in Safe Trash (PRD schema, unchanged). All times are epoch millis.
 *
 * This table is the only record of where a trashed item came from. Losing it makes the item unrestorable, which
 * is why the database never falls back to destructive migration.
 */
@Entity(
    tableName = "trash_manifest",
    indices = [Index(value = ["expires_at"]), Index(value = ["original_path"])],
)
data class TrashManifestEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "original_path")
    val originalPath: String,
    @ColumnInfo(name = "trash_path")
    val trashPath: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,
)
