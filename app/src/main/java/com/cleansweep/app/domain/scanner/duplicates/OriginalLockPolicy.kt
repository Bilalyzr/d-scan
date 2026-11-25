package com.cleansweep.app.domain.scanner.duplicates

import com.cleansweep.app.domain.model.IndexedFile

/**
 * Chooses which copy of a duplicate group is the Original (PRD 4.2).
 *
 * Every group always has exactly one Original selected, whatever the files look like, and the choice is the same
 * every time the group is looked at. The selection never suggests a deletion by itself; it only says which copy
 * the suggestion engine must leave alone.
 */
object OriginalLockPolicy {

    /**
     * The copy to keep. In order of precedence:
     *
     * 1. A copy the user locked. Among several, the following rules decide.
     * 2. A copy outside Downloads and messaging "Sent" folders. Those are where redundant copies land, so a
     *    copy kept somewhere else is the one that was put there on purpose.
     * 3. The oldest copy, by modification time. Android shared storage has no trustworthy creation time.
     * 4. The shortest path, then the alphabetically first path, so that equal files still get a stable answer.
     */
    fun selectOriginal(copies: List<IndexedFile>): IndexedFile {
        require(copies.isNotEmpty()) { "A duplicate group has at least one copy" }
        return copies.minWith(
            compareBy<IndexedFile> { !it.isOriginalLocked }
                .thenBy { LocationHeuristics.isInDownloadsLikeLocation(it.path) }
                .thenBy { it.lastModified }
                .thenBy { it.path.length }
                .thenBy { it.path },
        )
    }

    /** The copies other than the Original, in the order they were given. */
    fun redundantCopies(copies: List<IndexedFile>): List<IndexedFile> {
        val original = selectOriginal(copies)
        return copies.filter { it !== original }
    }
}
