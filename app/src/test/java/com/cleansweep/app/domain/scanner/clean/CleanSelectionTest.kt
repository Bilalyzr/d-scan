package com.cleansweep.app.domain.scanner.clean

import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.file
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What is marked for deletion in the hub, and what stays marked through changes. */
class CleanSelectionTest {

    private fun suggestion(
        id: Long,
        score: Int,
    ): Suggestion {
        val category = if (score >= 80) SuggestionCategory.EXACT_DUPLICATE else SuggestionCategory.LARGE_FORGOTTEN
        val reason = if (score >= 80) ReasonCode.EXACT_COPY else ReasonCode.LARGE_FILE
        return Suggestion(id, category, score, reason, listOf(reason), isDismissed = false, file = file("/p/$id"))
    }

    private val safe = suggestion(1L, score = 100)
    private val anotherSafe = suggestion(2L, score = 90)
    private val review = suggestion(3L, score = 60)
    private val anotherReview = suggestion(4L, score = 50)

    @Test
    fun `safe items are marked from the start and items that want a review are not`() {
        val selection = CleanSelection.EMPTY

        assertTrue(selection.isSelected(safe))
        assertTrue(selection.isSelected(anotherSafe))
        assertFalse(selection.isSelected(review))
        assertFalse(selection.isSelected(anotherReview))
    }

    @Test
    fun `unticking a safe item and ticking one that wants a review`() {
        val unticked = CleanSelection.EMPTY.set(safe, selected = false)

        assertFalse(unticked.isSelected(safe))
        // The others keep their default.
        assertTrue(unticked.isSelected(anotherSafe))
        assertFalse(unticked.isSelected(review))

        val both = unticked.set(review, selected = true)

        assertTrue(both.isSelected(review))
        assertFalse(both.isSelected(safe))
    }

    @Test
    fun `a safe item that appears later starts out marked, and one that was unticked stays so`() {
        val unticked = CleanSelection.EMPTY.set(safe, selected = false)

        val laterSafe = suggestion(99L, score = 95)

        assertTrue(unticked.isSelected(laterSafe))
        assertFalse(unticked.isSelected(safe))
    }

    @Test
    fun `marking many at once leaves the rest as it was`() {
        val selection = CleanSelection.EMPTY
            .set(safe, selected = false)
            .setAll(listOf(safe, review), selected = true)

        assertTrue(selection.isSelected(safe))
        assertTrue(selection.isSelected(review))
        assertTrue("Not given, so still marked by default", selection.isSelected(anotherSafe))
        assertFalse("Not given, so still unmarked", selection.isSelected(anotherReview))

        val cleared = selection.setAll(listOf(safe, review, anotherSafe), selected = false)

        assertTrue(cleared.selectedAmong(listOf(safe, review, anotherSafe)).isEmpty())
        assertFalse(cleared.isSelected(anotherReview))
    }

    @Test
    fun `what is known about files that are gone is dropped`() {
        val selection = CleanSelection.EMPTY
            .set(safe, selected = false)
            .set(review, selected = true)
            .forget(listOf(safe.fileId, review.fileId))

        assertTrue("Back to the default for both", selection.isSelected(safe))
        assertFalse(selection.isSelected(review))
    }

    @Test
    fun `of a list, the marked ones are picked out in the order of the list`() {
        val selection = CleanSelection.EMPTY.set(anotherReview, selected = true)

        val marked = selection.selectedAmong(listOf(review, anotherSafe, anotherReview, safe))

        assertEquals(listOf(anotherSafe, anotherReview, safe), marked)
    }

    @Test
    fun `the tier of a suggestion follows its score`() {
        assertEquals(SuggestionTier.SAFE_TO_CLEAN, safe.tier)
        assertEquals(SuggestionTier.REVIEW_RECOMMENDED, review.tier)
    }
}
