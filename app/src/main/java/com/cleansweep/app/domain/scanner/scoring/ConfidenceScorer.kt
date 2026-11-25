package com.cleansweep.app.domain.scanner.scoring

import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionTier

/**
 * Turns the signals behind a candidate deletion into a confidence score from 0 to 100 (PRD 4.4).
 *
 * ## How the score is built
 *
 * A flagged item starts at [DEFAULT_BASE] and every signal adds its weight:
 *
 * | Signal | Weight |
 * | --- | --- |
 * | Exact byte-for-byte copy | +50 |
 * | Temp, log, backup, thumbnail cache, 0-byte, installed APK, empty folder | +45 |
 * | In Downloads, or in a messaging "Sent" folder | +20 |
 * | Untouched for 90 days, old installer, large file, blurry photo | +15 |
 * | Modified within the last few days | -20 |
 *
 * The PRD gives the weights and two thresholds without saying what an item with no signal scores. The base is
 * derived from those numbers: the weakest signal the PRD lists as "safe to clean" (+45) lands exactly on the
 * Safe threshold (80), and the weakest signal (+15) lands exactly on the Review threshold (50). With a base of
 * zero the PRD's own safe categories (45) would fall below 50 and never be suggested at all.
 *
 * ## Why the tier is decided first
 *
 * Weights alone cannot be trusted to place an item in the right tier. A large video in Downloads that has sat
 * untouched for 90 days scores 35 + 15 + 15 + 20 = 85. On the score alone it would land in Safe to Clean and be
 * pre-selected for deletion, which the PRD rules out for large files. So the tier follows from what the item
 * *is*, and the score is then kept inside the tier's score range:
 *
 * - **Safe to Clean** (80 to 100): the signals include one of the certain ones, and the item is not recent.
 * - **Review Recommended** (50 to 79): everything else that is suggested at all.
 */
class ConfidenceScorer(
    private val base: Int = DEFAULT_BASE,
    private val weights: Map<ReasonCode, Int> = PRD_WEIGHTS,
) {
    init {
        val missing = ReasonCode.entries - weights.keys
        require(missing.isEmpty()) { "No weight given for: $missing" }
    }

    fun weightOf(reason: ReasonCode): Int = weights.getValue(reason)

    /** Highest weight first. Signals of equal weight keep the order in which they were declared. */
    fun orderByWeight(reasons: Iterable<ReasonCode>): List<ReasonCode> =
        reasons.distinct().sortedWith(compareByDescending<ReasonCode> { weightOf(it) }.thenBy { it.ordinal })

    /** The category of the strongest defining signal, or null when the signals only modify. */
    fun categoryOf(reasons: List<ReasonCode>): SuggestionCategory? =
        reasons.firstNotNullOfOrNull { DEFINING[it] }

    fun tierFor(reasons: List<ReasonCode>): SuggestionTier =
        if (ReasonCode.RECENT !in reasons && reasons.any { it in CERTAIN }) {
            SuggestionTier.SAFE_TO_CLEAN
        } else {
            SuggestionTier.REVIEW_RECOMMENDED
        }

    /**
     * The confidence that deleting the item is safe, kept inside the score range of [tierFor] the item's tier.
     * [reasons] must hold at least one defining signal.
     */
    fun score(reasons: List<ReasonCode>): Int {
        require(categoryOf(reasons) != null) { "A suggestion needs a defining signal, got $reasons" }
        val tier = tierFor(reasons)
        val raw = base + reasons.sumOf { weightOf(it) }
        return raw.coerceIn(tier.minScore, tier.maxScore)
    }

    companion object {
        const val DEFAULT_BASE: Int = 35

        /** The weights of PRD 4.4, plus a weight for the signals the PRD lists without giving one. */
        val PRD_WEIGHTS: Map<ReasonCode, Int> = mapOf(
            ReasonCode.EXACT_COPY to 50,
            ReasonCode.TEMP_OR_LOG to 45,
            ReasonCode.THUMBNAIL_CACHE to 45,
            ReasonCode.ZERO_BYTE to 45,
            ReasonCode.INSTALLED_APK to 45,
            ReasonCode.EMPTY_FOLDER to 45,
            ReasonCode.IN_DOWNLOADS to 20,
            ReasonCode.IN_MESSAGING_SENT to 20,
            ReasonCode.STALE_90_DAYS to 15,
            ReasonCode.BLURRY_MEDIA to 15,
            ReasonCode.OLD_INSTALLER to 15,
            ReasonCode.LARGE_FILE to 15,
            ReasonCode.RECENT to -20,
        )

        /** Signals that make an item safe to clean by themselves, when the item is not recent. */
        val CERTAIN: Set<ReasonCode> = setOf(
            ReasonCode.EXACT_COPY,
            ReasonCode.TEMP_OR_LOG,
            ReasonCode.THUMBNAIL_CACHE,
            ReasonCode.ZERO_BYTE,
            ReasonCode.INSTALLED_APK,
            ReasonCode.EMPTY_FOLDER,
        )

        /** Which suggestion category each defining signal puts an item in. Signals missing here only modify. */
        val DEFINING: Map<ReasonCode, SuggestionCategory> = mapOf(
            ReasonCode.EXACT_COPY to SuggestionCategory.EXACT_DUPLICATE,
            ReasonCode.TEMP_OR_LOG to SuggestionCategory.TEMP_FILE,
            ReasonCode.THUMBNAIL_CACHE to SuggestionCategory.TEMP_FILE,
            ReasonCode.ZERO_BYTE to SuggestionCategory.ZERO_BYTE,
            ReasonCode.INSTALLED_APK to SuggestionCategory.OBSOLETE_INSTALLER,
            ReasonCode.OLD_INSTALLER to SuggestionCategory.OBSOLETE_INSTALLER,
            ReasonCode.EMPTY_FOLDER to SuggestionCategory.EMPTY_FOLDER,
            ReasonCode.LARGE_FILE to SuggestionCategory.LARGE_FORGOTTEN,
        )
    }
}
