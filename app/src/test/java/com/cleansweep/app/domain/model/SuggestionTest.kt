package com.cleansweep.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SuggestionTierTest {

    @Test
    fun `tiers follow the cut-offs of PRD 4_4`() {
        assertEquals(SuggestionTier.SAFE_TO_CLEAN, SuggestionTier.fromScore(100))
        assertEquals(SuggestionTier.SAFE_TO_CLEAN, SuggestionTier.fromScore(80))
        assertEquals(SuggestionTier.REVIEW_RECOMMENDED, SuggestionTier.fromScore(79))
        assertEquals(SuggestionTier.REVIEW_RECOMMENDED, SuggestionTier.fromScore(50))
        assertEquals(SuggestionTier.KEEP, SuggestionTier.fromScore(49))
        assertEquals(SuggestionTier.KEEP, SuggestionTier.fromScore(0))
    }
}

class SuggestionDraftTest {

    @Test
    fun `rejects a score outside 0 to 100`() {
        assertThrows(IllegalArgumentException::class.java) {
            SuggestionDraft(1L, SuggestionCategory.ZERO_BYTE, 101, listOf(ReasonCode.ZERO_BYTE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SuggestionDraft(1L, SuggestionCategory.ZERO_BYTE, -1, listOf(ReasonCode.ZERO_BYTE))
        }
    }

    @Test
    fun `rejects a suggestion without a reason so every score stays explainable`() {
        assertThrows(IllegalArgumentException::class.java) {
            SuggestionDraft(1L, SuggestionCategory.ZERO_BYTE, 90, emptyList())
        }
    }

    @Test
    fun `rejects a suggestion for a row that was never persisted`() {
        assertThrows(IllegalArgumentException::class.java) {
            SuggestionDraft(0L, SuggestionCategory.ZERO_BYTE, 90, listOf(ReasonCode.ZERO_BYTE))
        }
    }

    @Test
    fun `the first reason is the primary one`() {
        val draft = SuggestionDraft(
            fileId = 7L,
            category = SuggestionCategory.EXACT_DUPLICATE,
            confidenceScore = 85,
            reasons = listOf(ReasonCode.EXACT_COPY, ReasonCode.IN_DOWNLOADS),
        )
        assertEquals(ReasonCode.EXACT_COPY, draft.primaryReason)
    }
}
