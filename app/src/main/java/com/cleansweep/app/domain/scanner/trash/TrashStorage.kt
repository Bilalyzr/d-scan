package com.cleansweep.app.domain.scanner.trash

/** What an entry on storage looks like right now. */
data class TrashStamp(
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

/** How a move of an entry on storage ended. */
sealed interface MoveResult {
    /** The entry is at the target path now, and no longer where it was. */
    data object Moved : MoveResult

    data object SourceMissing : MoveResult

    data object TargetExists : MoveResult

    /** The storage can be read but not written: a read-only SD card, or a folder Android keeps apps from changing. */
    data object ReadOnly : MoveResult

    /** Writing failed for another reason, for example because the volume was ejected meanwhile. */
    data class Failed(
        val cause: Exception,
    ) : MoveResult
}

/**
 * How deleting an entry from storage ended.
 *
 * Each delete only ever touches the kind of entry it is for: a method that deletes files never removes a folder,
 * and one that removes folders never removes a file. [DeleteResult.Missing] also covers an entry of the other
 * kind, since nothing that the method may delete is at the path.
 */
sealed interface DeleteResult {
    data object Deleted : DeleteResult

    data object Missing : DeleteResult

    /** A folder that has something in it. Nothing in it was touched. */
    data object NotEmpty : DeleteResult

    data object ReadOnly : DeleteResult

    data class Failed(
        val cause: Exception,
    ) : DeleteResult
}

/**
 * The storage operations Safe Trash needs. Every method blocks: callers run them on the I/O dispatcher.
 *
 * An interface, so that the trash rules can be tested against storage that is read-only or has vanished.
 */
interface TrashStorage {
    /** Null when nothing exists at [path]. Never follows a symbolic link. */
    fun stamp(path: String): TrashStamp?

    /** Makes sure the folder at [path] exists, and the folders above it. False when that cannot be done. */
    fun ensureDirectory(path: String): Boolean

    /** True only when [path] is a folder with nothing in it. */
    fun isEmptyDirectory(path: String): Boolean

    /**
     * Moves an entry to [to], which must not exist, in one step. The entry is never copied: a move that the
     * storage cannot carry out atomically fails instead.
     */
    fun move(
        from: String,
        to: String,
    ): MoveResult

    /** Deletes the file at [path]. A folder is never deleted by this, not even an empty one. */
    fun deleteFile(path: String): DeleteResult

    /** Deletes the folder at [path] when it is empty, and nothing when it is not. */
    fun deleteEmptyDirectory(path: String): DeleteResult
}
