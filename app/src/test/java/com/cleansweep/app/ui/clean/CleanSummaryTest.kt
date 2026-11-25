package com.cleansweep.app.ui.clean

import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.scanner.trash.Refusal
import com.cleansweep.app.domain.scanner.trash.TrashRefusal
import com.cleansweep.app.file
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a set of marked suggestions adds up to, and how refusals are summed up. */
class CleanSummaryTest {

    private fun suggestion(
        id: Long,
        score: Int,
        category: SuggestionCategory = SuggestionCategory.EXACT_DUPLICATE,
        bytes: Long = 100L,
    ): Suggestion {
        val reason = when (category) {
            SuggestionCategory.TEMP_FILE -> ReasonCode.TEMP_OR_LOG
            SuggestionCategory.EMPTY_FOLDER -> ReasonCode.EMPTY_FOLDER
            SuggestionCategory.LARGE_FORGOTTEN -> ReasonCode.LARGE_FILE
            else -> ReasonCode.EXACT_COPY
        }
        return Suggestion(
            id,
            category,
            score,
            reason,
            listOf(reason),
            isDismissed = false,
            file = file("/p/$id", sizeBytes = bytes, category = FileCategory.OTHER),
        )
    }

    @Test
    fun `the total is every item, with the ones that want a review counted apart`() {
        val summary = CleanSummary.of(
            listOf(
                suggestion(1L, score = 100, bytes = 500L),
                suggestion(2L, score = 100, category = SuggestionCategory.TEMP_FILE, bytes = 10L),
                suggestion(3L, score = 100, category = SuggestionCategory.TEMP_FILE, bytes = 20L),
                suggestion(4L, score = 60, category = SuggestionCategory.LARGE_FORGOTTEN, bytes = 700L),
            ),
        )

        assertEquals(4, summary.itemCount)
        assertEquals(1_230L, summary.bytes)
        assertEquals(3, summary.safeCount)
        assertEquals(1, summary.reviewCount)
        assertTrue(summary.needsReview)
        assertEquals(
            mapOf(SuggestionCategory.EXACT_DUPLICATE to 1, SuggestionCategory.TEMP_FILE to 2, SuggestionCategory.LARGE_FORGOTTEN to 1),
            summary.byCategory,
        )
        assertEquals(listOf(SuggestionCategory.EXACT_DUPLICATE, SuggestionCategory.TEMP_FILE, SuggestionCategory.LARGE_FORGOTTEN), summary.byCategory.keys.toList())
    }

    @Test
    fun `only safe items need no extra acknowledgement`() {
        val safe = CleanSummary.of(listOf(suggestion(1L, score = 80), suggestion(2L, score = 100)))

        assertEquals(2, safe.safeCount)
        assertEquals(0, safe.reviewCount)
        assertFalse(safe.needsReview)
    }

    @Test
    fun `nothing marked adds up to nothing`() {
        assertEquals(CleanSummary.EMPTY, CleanSummary.of(emptyList()))
        assertFalse(CleanSummary.EMPTY.needsReview)
    }

    @Test
    fun `refusals are counted per reason, in a fixed order`() {
        val refusals = listOf(
            Refusal(1L, "/p/1", TrashRefusal.STORAGE_READ_ONLY),
            Refusal(2L, "/p/2", TrashRefusal.CHANGED_SINCE_SUGGESTED),
            Refusal(3L, "/p/3", TrashRefusal.STORAGE_READ_ONLY),
            Refusal(4L, "/p/4", TrashRefusal.PROTECTED_ORIGINAL),
        )

        assertEquals(
            listOf(
                TrashRefusal.PROTECTED_ORIGINAL to 1,
                TrashRefusal.CHANGED_SINCE_SUGGESTED to 1,
                TrashRefusal.STORAGE_READ_ONLY to 2,
            ),
            refusals.countedByReason(),
        )
        assertTrue(emptyList<Refusal>().countedByReason().isEmpty())
    }
}
