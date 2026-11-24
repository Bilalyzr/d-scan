package com.cleansweep.app.domain.model

/**
 * A suggestion as the scoring engine emits it, before it is persisted.
 *
 * [reasons] is ordered by weight; the first entry becomes the primary reason shown on the suggestion card.
 */
data class SuggestionDraft(
    val fileId: Long,
    val category: SuggestionCategory,
    val confidenceScore: Int,
    val reasons: List<ReasonCode>,
) {
    init {
        require(fileId > 0L) { "A suggestion must reference a persisted file_index row" }
        require(confidenceScore in 0..100) { "confidenceScore must be within 0..100, was $confidenceScore" }
        require(reasons.isNotEmpty()) { "A suggestion needs at least one reason code to be explainable" }
    }

    val primaryReason: ReasonCode
        get() = reasons.first()
}

/** A persisted suggestion joined with the file (or empty-folder placeholder) it refers to. */
data class Suggestion(
    val fileId: Long,
    val category: SuggestionCategory,
    val confidenceScore: Int,
    val primaryReason: ReasonCode,
    val reasons: List<ReasonCode>,
    val isDismissed: Boolean,
    val file: IndexedFile,
) {
    val tier: SuggestionTier
        get() = SuggestionTier.fromScore(confidenceScore)
}
