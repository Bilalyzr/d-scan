package com.cleansweep.app.domain.scanner.scoring

import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ConfidenceScorerTest {

    private val scorer = ConfidenceScorer()

    private fun tier(vararg reasons: ReasonCode) = scorer.tierFor(reasons.toList())

    private fun score(vararg reasons: ReasonCode) = scorer.score(reasons.toList())

    // ---- The PRD's tier lists hold ----

    @Test
    fun `every category the PRD lists as safe to clean lands in that tier`() {
        for (certain in ConfidenceScorer.CERTAIN) {
            assertEquals("$certain", SuggestionTier.SAFE_TO_CLEAN, tier(certain))
            assertTrue("$certain", score(certain) >= SuggestionTier.SAFE_TO_CLEAN.minScore)
        }
    }

    @Test
    fun `large files, old installers and blurry photos are only recommended for review`() {
        assertEquals(SuggestionTier.REVIEW_RECOMMENDED, tier(ReasonCode.LARGE_FILE, ReasonCode.STALE_90_DAYS))
        assertEquals(SuggestionTier.REVIEW_RECOMMENDED, tier(ReasonCode.OLD_INSTALLER))
        assertEquals(SuggestionTier.REVIEW_RECOMMENDED, tier(ReasonCode.BLURRY_MEDIA))
    }

    @Test
    fun `a large file in Downloads, untouched for 90 days, is never pre-selected`() {
        val reasons = listOf(ReasonCode.LARGE_FILE, ReasonCode.IN_DOWNLOADS, ReasonCode.STALE_90_DAYS)

        // 35 + 15 + 20 + 15 is 85; the tier's ceiling keeps it in Review Recommended.
        assertEquals(SuggestionTier.REVIEW_RECOMMENDED, scorer.tierFor(reasons))
        assertEquals(SuggestionTier.REVIEW_RECOMMENDED.maxScore, scorer.score(reasons))
    }

    @Test
    fun `anything modified in the last few days is held back from pre-selection`() {
        for (certain in ConfidenceScorer.CERTAIN) {
            val reasons = listOf(certain, ReasonCode.RECENT)
            assertEquals("$certain", SuggestionTier.REVIEW_RECOMMENDED, scorer.tierFor(reasons))
            assertTrue("$certain", scorer.score(reasons) <= SuggestionTier.REVIEW_RECOMMENDED.maxScore)
        }
    }

    // ---- The PRD's weights are used as given ----

    @Test
    fun `the weights are the ones of PRD 4_4`() {
        assertEquals(50, scorer.weightOf(ReasonCode.EXACT_COPY))
        assertEquals(45, scorer.weightOf(ReasonCode.TEMP_OR_LOG))
        assertEquals(45, scorer.weightOf(ReasonCode.ZERO_BYTE))
        assertEquals(45, scorer.weightOf(ReasonCode.INSTALLED_APK))
        assertEquals(20, scorer.weightOf(ReasonCode.IN_DOWNLOADS))
        assertEquals(20, scorer.weightOf(ReasonCode.IN_MESSAGING_SENT))
        assertEquals(15, scorer.weightOf(ReasonCode.STALE_90_DAYS))
        assertEquals(15, scorer.weightOf(ReasonCode.BLURRY_MEDIA))
    }

    @Test
    fun `the base puts the weakest safe signal on the safe threshold and the weakest signal on the review one`() {
        assertEquals(SuggestionTier.SAFE_TO_CLEAN.minScore, ConfidenceScorer.DEFAULT_BASE + scorer.weightOf(ReasonCode.ZERO_BYTE))
        assertEquals(SuggestionTier.REVIEW_RECOMMENDED.minScore, ConfidenceScorer.DEFAULT_BASE + scorer.weightOf(ReasonCode.STALE_90_DAYS))
    }

    @Test
    fun `an exact copy is the most certain junk, and location and age add to both`() {
        assertEquals(85, score(ReasonCode.EXACT_COPY))
        assertEquals(100, score(ReasonCode.EXACT_COPY, ReasonCode.IN_DOWNLOADS))
        assertEquals(80, score(ReasonCode.TEMP_OR_LOG))
        assertEquals(95, score(ReasonCode.TEMP_OR_LOG, ReasonCode.STALE_90_DAYS))
        assertEquals(100, score(ReasonCode.ZERO_BYTE, ReasonCode.IN_MESSAGING_SENT, ReasonCode.STALE_90_DAYS))
    }

    @Test
    fun `review items stay between 50 and 79 however many signals pile up`() {
        assertEquals(50, score(ReasonCode.LARGE_FILE))
        assertEquals(65, score(ReasonCode.LARGE_FILE, ReasonCode.STALE_90_DAYS))
        assertEquals(65, score(ReasonCode.OLD_INSTALLER, ReasonCode.STALE_90_DAYS))
        assertEquals(79, score(ReasonCode.OLD_INSTALLER, ReasonCode.STALE_90_DAYS, ReasonCode.IN_DOWNLOADS))
        assertEquals(60, score(ReasonCode.ZERO_BYTE, ReasonCode.RECENT))
        assertEquals(79, score(ReasonCode.TEMP_OR_LOG, ReasonCode.RECENT, ReasonCode.IN_DOWNLOADS))
    }

    // ---- Invariants over arbitrary signal sets ----

    @Test
    fun `a score always lies inside the score range of its tier`() {
        val random = Random(7)
        repeat(2_000) {
            val reasons = ReasonCode.entries.filter { random.nextInt(4) == 0 }
            if (scorer.categoryOf(reasons.toList()) == null) {
                assertThrows(IllegalArgumentException::class.java) { scorer.score(reasons) }
                return@repeat
            }
            val tier = scorer.tierFor(reasons)
            val score = scorer.score(reasons)
            assertTrue("$reasons scored $score in $tier", score in tier.minScore..tier.maxScore)
        }
    }

    // ---- Vocabulary ----

    @Test
    fun `signals are ordered by weight and, at equal weight, by declaration order`() {
        val ordered = scorer.orderByWeight(
            listOf(ReasonCode.STALE_90_DAYS, ReasonCode.IN_DOWNLOADS, ReasonCode.RECENT, ReasonCode.ZERO_BYTE, ReasonCode.IN_DOWNLOADS),
        )

        assertEquals(listOf(ReasonCode.ZERO_BYTE, ReasonCode.IN_DOWNLOADS, ReasonCode.STALE_90_DAYS, ReasonCode.RECENT), ordered)
    }

    @Test
    fun `the category comes from the first defining signal, and signals that only modify give none`() {
        assertEquals(SuggestionCategory.OBSOLETE_INSTALLER, scorer.categoryOf(listOf(ReasonCode.IN_DOWNLOADS, ReasonCode.OLD_INSTALLER)))
        assertEquals(SuggestionCategory.TEMP_FILE, scorer.categoryOf(listOf(ReasonCode.THUMBNAIL_CACHE)))
        assertEquals(SuggestionCategory.EXACT_DUPLICATE, scorer.categoryOf(listOf(ReasonCode.EXACT_COPY, ReasonCode.INSTALLED_APK)))
        assertNull(scorer.categoryOf(listOf(ReasonCode.IN_DOWNLOADS, ReasonCode.STALE_90_DAYS)))
        assertNull(scorer.categoryOf(emptyList()))
    }

    @Test
    fun `every reason code has a weight, and every defining one a category`() {
        for (reason in ReasonCode.entries) {
            assertTrue(reason in ConfidenceScorer.PRD_WEIGHTS)
        }
        for (certain in ConfidenceScorer.CERTAIN) {
            assertTrue("$certain has no category", certain in ConfidenceScorer.DEFINING)
        }
    }
}
