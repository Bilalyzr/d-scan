package com.cleansweep.app.domain.scanner.clean

import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionTier

/**
 * Which suggestions are marked for deletion in the Suggestions Hub (PRD 4.4 and screen 4).
 *
 * "Safe to Clean" items are ticked from the start and "Review Recommended" items are not; that is the PRD's rule.
 * So this records the exceptions rather than the whole set: which safe items were unticked, and which others were
 * ticked. Whatever the list holds, the rule holds with it. A safe item that turns up later, after another scan,
 * starts out ticked, and one that was unticked stays unticked.
 *
 * Immutable: every change gives a new value, so a screen can show one while the next is being made.
 */
data class CleanSelection(
    private val untickedSafe: Set<Long> = emptySet(),
    private val tickedOthers: Set<Long> = emptySet(),
) {
    fun isSelected(
        suggestion: Suggestion,
    ): Boolean = if (suggestion.tier == SuggestionTier.SAFE_TO_CLEAN) {
        suggestion.fileId !in untickedSafe
    } else {
        suggestion.fileId in tickedOthers
    }

    fun set(
        suggestion: Suggestion,
        selected: Boolean,
    ): CleanSelection {
        val safe = suggestion.tier == SuggestionTier.SAFE_TO_CLEAN
        return when {
            selected && safe -> copy(untickedSafe = untickedSafe - suggestion.fileId)
            selected -> copy(tickedOthers = tickedOthers + suggestion.fileId)
            safe -> copy(untickedSafe = untickedSafe + suggestion.fileId)
            else -> copy(tickedOthers = tickedOthers - suggestion.fileId)
        }
    }

    /** Ticks or unticks every one of [suggestions], and leaves the others as they are. */
    fun setAll(
        suggestions: Iterable<Suggestion>,
        selected: Boolean,
    ): CleanSelection = suggestions.fold(this) { selection, suggestion -> selection.set(suggestion, selected) }

    /** Of [suggestions], the ones that are ticked. */
    fun selectedAmong(
        suggestions: Iterable<Suggestion>,
    ): List<Suggestion> = suggestions.filter(::isSelected)

    /** Drops what is known about files that are gone from the list, such as after they were moved to the trash. */
    fun forget(
        fileIds: Collection<Long>,
    ): CleanSelection {
        val ids = fileIds.toSet()
        return copy(untickedSafe = untickedSafe - ids, tickedOthers = tickedOthers - ids)
    }

    companion object {
        val EMPTY: CleanSelection = CleanSelection()
    }
}
