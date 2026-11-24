package com.cleansweep.app.domain.model

/**
 * How confident the suggestion engine is that deleting an item is safe (PRD 4.4).
 *
 * The tier decides how the UI pre-selects an item: [SAFE_TO_CLEAN] is pre-selected for one-tap review,
 * [REVIEW_RECOMMENDED] is never pre-selected, and [KEEP] is not surfaced as a suggestion at all.
 */
enum class SuggestionTier(
    val minScore: Int,
    val maxScore: Int,
) {
    SAFE_TO_CLEAN(80, 100),
    REVIEW_RECOMMENDED(50, 79),
    KEEP(0, 49),
    ;

    companion object {
        // These cut-offs are repeated as literals in SuggestionItemDao's tier totals query;
        // SuggestionItemDaoTest pins both to the same values.
        fun fromScore(score: Int): SuggestionTier = when {
            score >= SAFE_TO_CLEAN.minScore -> SAFE_TO_CLEAN
            score >= REVIEW_RECOMMENDED.minScore -> REVIEW_RECOMMENDED
            else -> KEEP
        }
    }
}
