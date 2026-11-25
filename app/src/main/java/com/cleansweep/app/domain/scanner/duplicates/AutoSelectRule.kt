package com.cleansweep.app.domain.scanner.duplicates

import com.cleansweep.app.domain.model.IndexedFile

/**
 * A rule that marks the copies of a group of identical files for deletion all at once (PRD 4.5): all but the
 * one the rule keeps. Used on one group from its screen, and on every group from the Suggestions Hub.
 */
enum class AutoSelectRule {
    /** Keep the copy that was modified earliest, which is usually the one that was put there on purpose. */
    KEEP_OLDEST,

    /** Keep the copy that was modified last, as after an app has re-saved a file. */
    KEEP_NEWEST,

    /** Keep the copy with the shortest path: the one closest to the top of the storage. */
    KEEP_SHORTEST_PATH,

    /** Mark nothing. */
    UNSELECT_ALL,
    ;

    /**
     * The copy that stays when this rule is applied to [copies], or null for [UNSELECT_ALL]. Copies that compare
     * equal are told apart the way [OriginalLockPolicy] tells them apart, by the shortest path and then by name,
     * so the same group always gives the same answer.
     */
    fun keeperOf(
        copies: List<IndexedFile>,
    ): IndexedFile? = when (this) {
        KEEP_OLDEST -> copies.minWith(oldestFirst)
        KEEP_NEWEST -> copies.minWith(
            compareByDescending<IndexedFile> { it.lastModified }.thenBy { it.path.length }.thenBy { it.path },
        )
        KEEP_SHORTEST_PATH -> copies.minWith(compareBy<IndexedFile>({ it.path.length }, { it.path }))
        UNSELECT_ALL -> null
    }

    /** Oldest first; copies of the same age by the shortest path, then by name. */
    private val oldestFirst: Comparator<IndexedFile> =
        compareBy<IndexedFile>({ it.lastModified }, { it.path.length }, { it.path })
}
