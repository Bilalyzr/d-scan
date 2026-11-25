package com.cleansweep.app.domain.repository

import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionCategoryTotal
import com.cleansweep.app.domain.model.SuggestionDraft
import com.cleansweep.app.domain.model.TierTotal
import kotlinx.coroutines.flow.Flow

/**
 * Persisted deletion recommendations. All methods are main-safe.
 *
 * Suggestions are derived data: the scoring engine rebuilds the full set after each scan with [replaceAll].
 * The only user-owned state in the table is the dismissal flag, which every rebuild has to preserve.
 */
interface SuggestionRepository {

    /**
     * Suggestions with their file, highest confidence first. Pass a null [category] for every category.
     * Dismissed suggestions are hidden unless [includeDismissed] is true.
     */
    fun observeSuggestions(
        category: SuggestionCategory? = null,
        includeDismissed: Boolean = false,
    ): Flow<List<Suggestion>>

    /** Active suggestions grouped by tier. Tiers without suggestions are absent from the list. */
    fun observeTierTotals(): Flow<List<TierTotal>>

    /** Active suggestions grouped by category. Categories without suggestions are absent from the list. */
    fun observeCategoryTotals(): Flow<List<SuggestionCategoryTotal>>

    fun observeActiveCount(): Flow<Int>

    /**
     * Replaces the whole suggestion table with the engine's latest output, in one transaction.
     *
     * A file whose previous suggestion was dismissed comes back dismissed, so an ignored file stays ignored for as
     * long as it keeps being a candidate.
     */
    suspend fun replaceAll(suggestions: List<SuggestionDraft>)

    /**
     * Replaces the suggestions of [fileIds] and of no other file, the way [replaceAll] does for everything.
     *
     * For when the user keeps another copy of a group of duplicates, so that the copies that are redundant now
     * are offered at once, without waiting for the next scan. A file whose suggestion was dismissed before comes
     * back dismissed. Every draft must be for one of [fileIds].
     */
    suspend fun replaceForFiles(
        fileIds: Collection<Long>,
        suggestions: List<SuggestionDraft>,
    )

    /** Hides or restores suggestions. Ignoring a suggestion never changes the file on disk. */
    suspend fun setDismissed(fileIds: Collection<Long>, dismissed: Boolean)

    suspend fun deleteForFiles(fileIds: Collection<Long>)

    /** Of [fileIds], the ones that currently have a suggestion the user has not dismissed. */
    suspend fun getActiveFileIds(fileIds: Collection<Long>): Set<Long>

    suspend fun clearSuggestions()
}
