package com.cleansweep.app.ui.clean

import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.domain.scanner.trash.Refusal
import com.cleansweep.app.domain.scanner.trash.RestoreResult
import com.cleansweep.app.domain.scanner.trash.SafeTrash
import com.cleansweep.app.domain.scanner.trash.TrashRefusal
import com.cleansweep.app.domain.model.TrashEntry
import com.cleansweep.app.domain.scanner.clean.CleanSelectionStore

/**
 * What a set of marked suggestions adds up to. The same numbers are on the bar above the list and in the
 * confirmation sheet, so they are worked out once, here.
 */
data class CleanSummary(
    val itemCount: Int,
    val bytes: Long,
    /** Items the app is sure about, and items that were meant to be looked at first. */
    val safeCount: Int,
    val reviewCount: Int,
    /** Of each kind, how many. Only kinds that are in the set. */
    val byCategory: Map<SuggestionCategory, Int>,
) {
    /** True when a look-first item is among them. The sheet then asks for an extra acknowledgement (PRD section 10). */
    val needsReview: Boolean
        get() = reviewCount > 0

    companion object {
        val EMPTY: CleanSummary = CleanSummary(0, 0L, 0, 0, emptyMap())

        fun of(
            suggestions: Collection<Suggestion>,
        ): CleanSummary {
            val counts = LinkedHashMap<SuggestionCategory, Int>()
            var safe = 0
            var bytes = 0L
            for (suggestion in suggestions) {
                counts[suggestion.category] = (counts[suggestion.category] ?: 0) + 1
                bytes += suggestion.file.sizeBytes
                if (suggestion.tier == SuggestionTier.SAFE_TO_CLEAN) safe++
            }
            return CleanSummary(
                itemCount = suggestions.size,
                bytes = bytes,
                safeCount = safe,
                reviewCount = suggestions.size - safe,
                byCategory = counts,
            )
        }
    }
}

/** What came of moving the marked files to Safe Trash. */
data class CleanOutcome(
    val moved: List<TrashEntry>,
    val refused: List<Refusal>,
) {
    val isEmpty: Boolean
        get() = moved.isEmpty() && refused.isEmpty()

    val bytes: Long
        get() = moved.sumOf { it.sizeBytes }

    /** The ids to give to [Cleaner.undo]. */
    val undoIds: List<Long>
        get() = moved.map { it.id }
}

/**
 * Moves marked files to Safe Trash for a screen, and undoes it. One place for what both the Suggestions Hub and a
 * group of duplicates do, so that neither forgets to clear what was marked.
 */
class Cleaner(
    private val trash: SafeTrash,
    private val selection: CleanSelectionStore,
) {

    /** Moves the files with [fileIds] to Safe Trash. Whatever happens to them, they are no longer marked. */
    suspend fun clean(
        fileIds: Collection<Long>,
    ): CleanOutcome {
        val ids = fileIds.distinct()
        val result = trash.trash(ids)
        selection.forget(ids)
        return CleanOutcome(moved = result.trashed, refused = result.refused)
    }

    /** Puts the files of an outcome back where they came from. */
    suspend fun undo(
        outcome: CleanOutcome,
    ): RestoreResult = trash.restore(outcome.undoIds)
}

/** How many were refused for each reason, in the order of the reasons, so that a list of them reads the same every time. */
fun List<Refusal>.countedByReason(): List<Pair<TrashRefusal, Int>> =
    TrashRefusal.entries.mapNotNull { reason -> count { it.reason == reason }.takeIf { it > 0 }?.let { reason to it } }
