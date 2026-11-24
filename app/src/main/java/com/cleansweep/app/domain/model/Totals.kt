package com.cleansweep.app.domain.model

/** How many indexed files and how many bytes fall into one [FileCategory]. */
data class CategoryTotal(
    val category: FileCategory,
    val fileCount: Int,
    val totalBytes: Long,
)

/** How many active suggestions and how many bytes fall into one [SuggestionTier]. */
data class TierTotal(
    val tier: SuggestionTier,
    val itemCount: Int,
    val totalBytes: Long,
)

/** How many active suggestions and how many bytes fall into one [SuggestionCategory]. */
data class SuggestionCategoryTotal(
    val category: SuggestionCategory,
    val itemCount: Int,
    val totalBytes: Long,
)
