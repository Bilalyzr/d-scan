package com.cleansweep.app.domain.scanner

import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.scanner.trash.TrashNaming
import com.cleansweep.app.domain.scanner.walker.EmptyFolderRules
import com.cleansweep.app.domain.scanner.walker.FileNames
import com.cleansweep.app.domain.util.StoragePaths

/** Why an item is kept out of the suggestions although a detector reported it. */
enum class Rejection {
    /** The user pinned this copy as the one to keep. */
    LOCKED_ORIGINAL,

    /**
     * The file, or a folder on the way to it, is hidden. Hidden entries belong to apps and to the system
     * (`.nomedia`, `.trashed-*`, a download in progress), not to the user.
     */
    HIDDEN_ENTRY,

    /** An empty folder with a name the system or its apps use for their own folders. */
    SYSTEM_FOLDER,

    /** Other directory entries refer to the same stored content, so deleting this one frees no space. */
    HARD_LINKED,

    /** The entry lies in Safe Trash already. The walker leaves such entries alone; this is the second lock. */
    IN_TRASH,
}

/**
 * The last check before an item is suggested. Detectors answer "what is this?"; the guard answers "may this
 * particular entry be offered to the user at all?".
 */
class SuggestionGuard(
    private val hardLinks: HardLinkReader = HardLinkReader.ASSUME_SINGLE_LINK,
) {
    /**
     * Why the item may not be suggested, or null when it may. [linkCount] is the number of directory entries
     * that refer to the file's content; it is a parameter so that the rule can be judged without storage.
     */
    fun rejectionFor(
        file: IndexedFile,
        category: SuggestionCategory,
        linkCount: Int = 1,
    ): Rejection? = when {
        file.isOriginalLocked -> Rejection.LOCKED_ORIGINAL

        TrashNaming.isTrashedName(StoragePaths.nameOf(file.path)) -> Rejection.IN_TRASH

        // Hidden content is offered only as temporary junk. A hidden `.log` or a thumbnail cache is litter
        // whoever put it there; a hidden duplicate, large file or 0-byte file (think of `.nomedia`) is not.
        FileNames.isHiddenPath(file.path) && category != SuggestionCategory.TEMP_FILE -> Rejection.HIDDEN_ENTRY

        category == SuggestionCategory.EMPTY_FOLDER && !EmptyFolderRules.hasSuggestableName(file.path) ->
            Rejection.SYSTEM_FOLDER

        linkCount > 1 -> Rejection.HARD_LINKED

        else -> null
    }

    /**
     * True when the item may be suggested. Reads the link count from storage, a single lookup, so callers run
     * it off the main thread like the rest of the analysis.
     */
    fun isEligible(
        file: IndexedFile,
        category: SuggestionCategory,
    ): Boolean = rejectionFor(file, category, hardLinks.linkCount(file.path)) == null
}
