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

    /** Hides or restores suggestions. Ignoring a suggestion never changes the file on disk. */
    suspend fun setDismissed(fileIds: Collection<Long>, dismissed: Boolean)

    suspend fun deleteForFiles(fileIds: Collection<Long>)

    suspend fun clearSuggestions()
}
