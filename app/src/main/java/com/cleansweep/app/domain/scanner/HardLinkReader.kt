package com.cleansweep.app.domain.scanner

/**
 * Counts the directory entries that refer to the same stored content as the file at a path.
 *
 * With a hard link, deleting one name frees nothing and can take an app's file away with it, so a file with
 * more than one link is never suggested. Android's shared storage does not let apps create hard links, which is
 * why this is a guard rather than a stored column.
 */
fun interface HardLinkReader {
    /** The link count, or 1 when it cannot be read. */
    fun linkCount(path: String): Int

    companion object {
        val ASSUME_SINGLE_LINK: HardLinkReader = HardLinkReader { 1 }
    }
}
