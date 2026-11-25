package com.cleansweep.app.domain.model

/** Constants for the 30-day Safe Trash (PRD 4.5). All times are epoch millis. */
object TrashPolicy {
    const val RETENTION_DAYS: Int = 30

    private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

    /** When an item deleted at [deletedAt] is due for permanent purge. */
    fun expiresAt(deletedAt: Long): Long = deletedAt + RETENTION_DAYS * DAY_MILLIS

    fun isExpired(expiresAt: Long, now: Long): Boolean = now >= expiresAt

    /** Whole days left before the purge, rounded up; 0 once the item has expired. */
    fun daysRemaining(expiresAt: Long, now: Long): Int =
        (((expiresAt - now).coerceAtLeast(0L) + DAY_MILLIS - 1) / DAY_MILLIS).toInt()
}

/**
 * One row of the `trash_manifest` table: an item that was soft-deleted into Safe Trash.
 *
 * The manifest is written before a file is moved, so a crash can never leave an untracked file in the trash.
 *
 * @property originalPath Canonical path the item was moved from; also where a restore puts it back.
 * @property trashPath Where the item rests while trashed: its own folder, under the hidden name that
     *   `TrashNaming` gives it. See [com.cleansweep.app.domain.scanner.trash.TrashNaming].
 * @property deletedAt When the item was moved to the trash, in epoch millis.
 * @property expiresAt When the item is purged permanently: [deletedAt] + [TrashPolicy.RETENTION_DAYS].
 */
data class TrashEntry(
    val id: Long = 0L,
    val originalPath: String,
    val trashPath: String,
    val sizeBytes: Long,
    val deletedAt: Long,
    val expiresAt: Long,
)
