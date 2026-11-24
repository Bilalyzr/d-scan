package com.cleansweep.app.domain.model

/**
 * One row of the `file_index` table: a scanned file, or a placeholder row for a suggested empty folder.
 *
 * Paths are always canonical absolute paths with `/` separators
 * (see [com.cleansweep.app.domain.util.StoragePaths]), so symlinked aliases such as `/sdcard` and
 * `/storage/emulated/0` can never index the same physical file twice.
 *
 * @property id 0 until the row has been persisted.
 * @property extension Lowercase extension without the dot; empty for files that have none.
 * @property lastModified Last-modified time in epoch millis. Together with [sizeBytes] this decides whether the
 *   cached hashes are still valid on the next scan.
 * @property createdAt Best-effort creation time in epoch millis. Android shared storage does not expose a reliable
 *   creation timestamp, so this may be null or fall back to the inode change time. [lastModified] is the
 *   trustworthy age signal.
 * @property scanTimestamp When a scan last saw this entry, in epoch millis. Drives the stale-entry sweep.
 * @property isDirectory True only for empty-folder placeholder rows. Those carry size 0, no hashes and category
 *   [FileCategory.OTHER]; every query about file content must filter them out.
 * @property isOriginalLocked True when the user explicitly pinned this copy as the one to keep in its duplicate
 *   group (Original Lock, PRD 4.2). A locked copy is never suggested for deletion.
 */
data class IndexedFile(
    val id: Long = 0L,
    val path: String,
    val name: String,
    val extension: String,
    val mimeType: String?,
    val category: FileCategory,
    val sizeBytes: Long,
    val lastModified: Long,
    val createdAt: Long?,
    val partialHash: String? = null,
    val fullHash: String? = null,
    val scanTimestamp: Long = 0L,
    val isDirectory: Boolean = false,
    val isOriginalLocked: Boolean = false,
) {
    init {
        require(sizeBytes >= 0L) { "Negative size for $path" }
        require(
            !isDirectory || (sizeBytes == 0L && partialHash == null && fullHash == null),
        ) { "Directory placeholder rows carry no size and no hashes: $path" }
    }
}

/** A newly computed hash for one indexed file, produced by the 3-stage duplicate pipeline. */
data class FileHashUpdate(
    val fileId: Long,
    val hash: String,
)
