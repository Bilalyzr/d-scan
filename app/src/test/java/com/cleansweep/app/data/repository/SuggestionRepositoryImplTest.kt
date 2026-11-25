package com.cleansweep.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.data.local.db.CleanSweepDatabase
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionDraft
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.file
import com.cleansweep.app.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private fun draft(fileId: Long, score: Int, category: SuggestionCategory = SuggestionCategory.EXACT_DUPLICATE) =
    SuggestionDraft(
        fileId = fileId,
        category = category,
        confidenceScore = score,
        reasons = listOf(ReasonCode.EXACT_COPY, ReasonCode.IN_DOWNLOADS),
    )

@RunWith(AndroidJUnit4::class)
class SuggestionRepositoryImplTest {

    private lateinit var database: CleanSweepDatabase
    private lateinit var fileIndexRepository: FileIndexRepositoryImpl
    private lateinit var repository: SuggestionRepositoryImpl

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        fileIndexRepository = FileIndexRepositoryImpl(
            database,
            database.fileIndexDao(),
            database.suggestionItemDao(),
        )
        repository = SuggestionRepositoryImpl(database, database.suggestionItemDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun indexedFile(path: String, sizeBytes: Long = 10L) =
        fileIndexRepository.applyScanBatch(listOf(file(path, sizeBytes = sizeBytes)), scanTimestamp = 1_000L).single()

    private suspend fun fileIds(includeDismissed: Boolean) =
        repository.observeSuggestions(includeDismissed = includeDismissed).first().map { it.fileId }

    @Test
    fun `a suggestion the user dismissed stays dismissed when the engine emits it again`() = runTest {
        val a = indexedFile("/r/a.bin")
        repository.replaceAll(listOf(draft(a.id, score = 90)))
        repository.setDismissed(listOf(a.id), true)
        assertEquals(0, repository.observeActiveCount().first())

        repository.replaceAll(listOf(draft(a.id, score = 92)))

        val restored = repository.observeSuggestions(includeDismissed = true).first().single()
        assertTrue(restored.isDismissed)
        assertEquals(92, restored.confidenceScore)
        assertEquals(0, repository.observeActiveCount().first())
    }

    @Test
    fun `suggestions the engine no longer emits are dropped`() = runTest {
        val a = indexedFile("/r/a.bin")
        val b = indexedFile("/r/b.bin")
        repository.replaceAll(listOf(draft(a.id, 90), draft(b.id, 70)))

        repository.replaceAll(listOf(draft(b.id, 70)))

        assertEquals(listOf(b.id), fileIds(includeDismissed = true))
    }

    @Test
    fun `replacing everything with nothing clears the table`() = runTest {
        val a = indexedFile("/r/a.bin")
        repository.replaceAll(listOf(draft(a.id, 90)))

        repository.replaceAll(emptyList())

        assertEquals(0, repository.observeActiveCount().first())
    }

    @Test
    fun `a dismissed file is suggested again once it stopped being a candidate in between`() = runTest {
        val a = indexedFile("/r/a.bin")
        repository.replaceAll(listOf(draft(a.id, 90)))
        repository.setDismissed(listOf(a.id), true)

        // The file changed, so the engine no longer emits it; the old dismissal goes with the old suggestion.
        repository.replaceAll(emptyList())
        repository.replaceAll(listOf(draft(a.id, 90)))

        assertFalse(repository.observeSuggestions(includeDismissed = true).first().single().isDismissed)
    }

    @Test
    fun `dismissals handle more files than SQLite allows parameters in one statement`() = runTest {
        val files = fileIndexRepository.applyScanBatch((1..1_200).map { file("/r/f-$it.bin") }, scanTimestamp = 1_000L)
        repository.replaceAll(files.map { draft(it.id, 90) })

        repository.setDismissed(files.map { it.id }, dismissed = true)

        assertEquals(0, repository.observeActiveCount().first())
        assertEquals(1_200, repository.observeSuggestions(includeDismissed = true).first().count { it.isDismissed })
    }

    @Test
    fun `suggestions come back with their file, every reason and the tier derived from the score`() = runTest {
        val a = indexedFile("/r/a.bin", sizeBytes = 2_000L)
        repository.replaceAll(listOf(draft(a.id, score = 85)))

        val suggestion = repository.observeSuggestions().first().single()

        assertEquals(a.id, suggestion.fileId)
        assertEquals("/r/a.bin", suggestion.file.path)
        assertEquals(2_000L, suggestion.file.sizeBytes)
        assertEquals(ReasonCode.EXACT_COPY, suggestion.primaryReason)
        assertEquals(listOf(ReasonCode.EXACT_COPY, ReasonCode.IN_DOWNLOADS), suggestion.reasons)
        assertEquals(SuggestionTier.SAFE_TO_CLEAN, suggestion.tier)
        assertFalse(suggestion.isDismissed)
    }

    @Test
    fun `suggestions can be observed for one category only`() = runTest {
        val a = indexedFile("/r/a.bin")
        val b = indexedFile("/r/old.bin")
        repository.replaceAll(listOf(draft(a.id, 90), draft(b.id, 70, SuggestionCategory.LARGE_FORGOTTEN)))

        val largeOnly = repository.observeSuggestions(category = SuggestionCategory.LARGE_FORGOTTEN).first()

        assertEquals(listOf(b.id), largeOnly.map { it.fileId })
    }

    @Test
    fun `tier and category totals are exposed in domain form`() = runTest {
        val a = indexedFile("/r/a.bin", sizeBytes = 100L)
        val b = indexedFile("/r/old.bin", sizeBytes = 900L)
        repository.replaceAll(listOf(draft(a.id, 90), draft(b.id, 70, SuggestionCategory.LARGE_FORGOTTEN)))

        val tiers = repository.observeTierTotals().first().associateBy { it.tier }
        assertEquals(1, tiers.getValue(SuggestionTier.SAFE_TO_CLEAN).itemCount)
        assertEquals(100L, tiers.getValue(SuggestionTier.SAFE_TO_CLEAN).totalBytes)
        assertEquals(900L, tiers.getValue(SuggestionTier.REVIEW_RECOMMENDED).totalBytes)

        val categories = repository.observeCategoryTotals().first().associateBy { it.category }
        assertEquals(2, categories.size)
        assertEquals(900L, categories.getValue(SuggestionCategory.LARGE_FORGOTTEN).totalBytes)
        assertEquals(2, repository.observeActiveCount().first())
    }

    @Test
    fun `suggestions can be removed for specific files or all at once`() = runTest {
        val a = indexedFile("/r/a.bin")
        val b = indexedFile("/r/b.bin")
        repository.replaceAll(listOf(draft(a.id, 90), draft(b.id, 80)))

        repository.deleteForFiles(listOf(a.id))
        assertEquals(listOf(b.id), fileIds(includeDismissed = true))

        repository.clearSuggestions()
        assertEquals(emptyList<Long>(), fileIds(includeDismissed = true))
    }

    @Test
    fun `deleting an indexed file takes its suggestion with it`() = runTest {
        val a = indexedFile("/r/a.bin")
        val b = indexedFile("/r/b.bin")
        repository.replaceAll(listOf(draft(a.id, 90), draft(b.id, 80)))

        fileIndexRepository.clearIndex()

        assertEquals(emptyList<Long>(), fileIds(includeDismissed = true))
    }

    @Test
    fun `the suggestions of some files can be replaced on their own, and the rest stay as they are`() = runTest {
        val a = indexedFile("/r/a.bin")
        val b = indexedFile("/r/b.bin")
        val c = indexedFile("/r/c.bin")
        repository.replaceAll(listOf(draft(a.id, score = 90), draft(b.id, score = 70), draft(c.id, score = 60)))
        repository.setDismissed(listOf(b.id), true)

        // A group of duplicates changed: a is kept from now on and loses its suggestion, b takes its place.
        repository.replaceForFiles(listOf(a.id, b.id), listOf(draft(b.id, score = 95)))

        val all = repository.observeSuggestions(includeDismissed = true).first()
        assertEquals(listOf(b.id, c.id), all.map { it.fileId })
        assertEquals(95, all.first { it.fileId == b.id }.confidenceScore)
        assertTrue("b was ignored before, and stays so", all.first { it.fileId == b.id }.isDismissed)
        assertEquals("Only c is offered", listOf(c.id), fileIds(includeDismissed = false))
    }

    @Test
    fun `a draft for a file outside the given ones is refused, and nothing is written`() = runTest {
        val a = indexedFile("/r/a.bin")
        val b = indexedFile("/r/b.bin")
        repository.replaceAll(listOf(draft(a.id, score = 90)))

        val failure = runCatching { repository.replaceForFiles(listOf(a.id), listOf(draft(b.id, score = 80))) }.exceptionOrNull()

        assertTrue("Refused: $failure", failure is IllegalArgumentException)
        assertEquals(listOf(a.id), fileIds(includeDismissed = true))
        assertEquals("b was never given a suggestion", 0, database.suggestionItemDao().getActiveFileIds(listOf(b.id)).size)
    }
}
