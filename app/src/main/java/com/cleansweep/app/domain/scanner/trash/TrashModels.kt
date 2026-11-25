package com.cleansweep.app.domain.scanner.trash

import com.cleansweep.app.domain.model.TrashEntry

/** Why an indexed file was not moved to Safe Trash. */
enum class TrashRefusal {
    /** The file is not in the index any more: it was deleted, or no scan has seen it yet. */
    NOT_INDEXED,

    /** No suggestion asks for this file, or the user dismissed the one that did. */
    NOT_SUGGESTED,

    WHITELISTED,

    /** The user locked this copy, or it is the Original of its duplicate group by default. */
    PROTECTED_ORIGINAL,

    /**
     * The file is not what the suggestion was based on: another size, another modification time, or a folder
     * that is no longer empty. A new scan looks at it again.
     */
    CHANGED_SINCE_SUGGESTED,

    /** The file is not on storage any more. Its row has left the index. */
    GONE,

    STORAGE_READ_ONLY,

    /** The move failed for another reason, for example because the volume was ejected meanwhile. */
    COULD_NOT_MOVE,
}

data class Refusal(
    val fileId: Long,
    /** Empty when the file is not in the index, because then its path is unknown. */
    val path: String,
    val reason: TrashRefusal,
)

/** The outcome of moving files to Safe Trash. Both lists together cover every file that was asked for. */
data class TrashResult(
    /** The entries that are in Safe Trash now, in the order they were moved there. */
    val trashed: List<TrashEntry>,
    val refused: List<Refusal>,
) {
    /**
     * How much was moved to Safe Trash by this call. It still takes the same space: it is freed only when the
     * trash is emptied.
     */
    val bytesTrashed: Long
        get() = trashed.sumOf { it.sizeBytes }

    /** The ids to hand to `TrashManager.restore` for a one-tap undo. */
    val entryIds: List<Long>
        get() = trashed.map { it.id }

    companion object {
        val NOTHING: TrashResult = TrashResult(emptyList(), emptyList())
    }
}

/** Why a trashed entry could not be put back. Its row stays in the manifest, so it can be tried again. */
enum class RestoreFailure {
    /** The folder it came from cannot be reached or created. */
    STORAGE_UNAVAILABLE,

    STORAGE_READ_ONLY,

    /** The storage refused the move, or every name next to the original one was taken too. */
    COULD_NOT_MOVE,
}

data class RestoreFailureItem(
    val originalPath: String,
    val reason: RestoreFailure,
)

/** The outcome of restoring trashed entries. */
data class RestoreResult(
    /**
     * The paths the entries were put back to. That is not always their original path: when something else has
     * taken that name meanwhile, the entry goes back next to it as `name (restored)`.
     */
    val restoredPaths: List<String>,

    /** Entries whose file was no longer in the trash. Their rows are gone; there was nothing left to restore. */
    val nothingLeft: List<String>,

    val failures: List<RestoreFailureItem>,
)

/** The outcome of deleting trashed entries for good. */
data class PurgeResult(
    val deletedEntries: Int,

    /** Space this call freed. */
    val bytesFreed: Long,

    /** Entries whose file was already gone. Their rows are gone now too. */
    val missingEntries: Int,

    /** Folders that had something in them after all. Nothing in them was touched, and their rows stay. */
    val foldersNoLongerEmpty: Int,

    /** Entries the storage would not let go of. Their rows stay, so they are tried again later. */
    val failedEntries: Int,

    /** Rows that did not describe an entry of this app's trash. They were dropped; nothing on storage was touched. */
    val forgottenRows: Int,
) {
    companion object {
        val NOTHING: PurgeResult = PurgeResult(0, 0L, 0, 0, 0, 0)
    }
}

/** What the check at start-up found. */
data class MaintenanceResult(
    val purge: PurgeResult,

    /** Manifest rows that described a move that never happened, or an entry that has since gone. */
    val forgottenRows: Int,

    /** Set when the check itself failed. The app goes on; the next start-up tries again. */
    val failure: Throwable? = null,
)
