package com.cleansweep.app.domain.scanner.walker

/** What the walker needs to know about one entry of a folder. */
data class FileMetadata(
    val isDirectory: Boolean,
    val isRegularFile: Boolean,
    val isSymbolicLink: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    /**
     * Best effort, and less than it looks: Android reports the inode change time here, which a rename updates.
     * [lastModified] is the age signal everything relies on.
     */
    val createdAt: Long?,
)

fun interface FileMetadataReader {
    /** Null when nothing exists at [path]. Never follows a symbolic link: a link is reported as a link. */
    fun read(path: String): FileMetadata?
}
