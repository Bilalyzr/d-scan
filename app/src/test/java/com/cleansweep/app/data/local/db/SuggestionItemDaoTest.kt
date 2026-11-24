package com.cleansweep.app.data.local.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.data.local.db.dao.FileIndexDao
import com.cleansweep.app.data.local.db.dao.SuggestionItemDao
import com.cleansweep.app.data.local.db.entity.SuggestionItemEntity
import com.cleansweep.app.data.mapper.toDomain
import com.cleansweep.app.data.mapper.toEntity
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
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

@RunWith(AndroidJUnit4::class)
class SuggestionItemDaoTest {

    private lateinit var database: CleanSweepDatabase
    private lateinit var fileIndexDao: FileIndexDao
    private lateinit var dao: SuggestionItemDao

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        fileIndexDao = database.fileIndexDao()
        dao = database.suggestionItemDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** Indexes a file of [sizeBytes] and adds a suggestion with [score]. Returns the file id. */
    private suspend fun suggestedFile(
        path: String,
        score: Int,
        sizeBytes: Long = 10L,
        category: SuggestionCategory = SuggestionCategory.EXACT_DUPLICATE,
        dismissed: Boolean = false,
        reasons: List<ReasonCode> = listOf(ReasonCode.EXACT_COPY, ReasonCode.IN_DOWNLOADS),
    ): Long {
        val fileId = fileIndexDao.upsertAll(listOf(file(path, sizeBytes = sizeBytes).toEntity())).single()
        dao.upsertAll(
            listOf(
                SuggestionItemEntity(
                    fileId = fileId,
                    category = category,
                    confidenceScore = score,
                    reasonCode = reasons.first(),
                    reasonCodes = reasons.joinToString(","),
                    isDismissed = dismissed,
                ),
            ),
        )
        return fileId
    }

    private suspend fun suggestionFileIds(includeDismissed: Boolean): List<Long> =
        dao.observeWithFile(category = null, includeDismissed = includeDismissed).first().map { it.suggestion.fileId }

    @Test
    fun `removing a file from the index cascades to its suggestion`() = runTest {
        suggestedFile("/r/a.bin", score = 90)
        val kept = suggestedFile("/r/b.bin", score = 60)

        fileIndexDao.deleteByPath("/r/a.bin")

        assertEquals(1, dao.observeActiveCount().first())
        assertEquals(listOf(kept), suggestionFileIds(includeDismissed = true))
    }

    @Test
    fun `suggestions come back with their file, highest confidence first, without dismissed ones`() = runTest {
        val high = suggestedFile("/r/high.bin", score = 95, sizeBytes = 20L)
        val low = suggestedFile("/r/low.bin", score = 60, sizeBytes = 10L)
        val ignored = suggestedFile("/r/ignored.bin", score = 99, sizeBytes = 30L, dismissed = true)

        val active = dao.observeWithFile(category = null, includeDismissed = false).first()

        assertEquals(listOf(high, low), active.map { it.suggestion.fileId })
        assertEquals("/r/high.bin", active.first().file.path)
        assertEquals(20L, active.first().file.sizeBytes)
        assertFalse(active.first().file.isOriginalLocked)

        assertEquals(listOf(ignored, high, low), suggestionFileIds(includeDismissed = true))
    }

    @Test
    fun `suggestions can be filtered by category`() = runTest {
        val duplicate = suggestedFile("/r/dup.bin", score = 90, category = SuggestionCategory.EXACT_DUPLICATE)
        suggestedFile("/r/old.bin", score = 70, category = SuggestionCategory.LARGE_FORGOTTEN)

        val duplicatesOnly = dao.observeWithFile(SuggestionCategory.EXACT_DUPLICATE, includeDismissed = false).first()

        assertEquals(listOf(duplicate), duplicatesOnly.map { it.suggestion.fileId })
        assertEquals(SuggestionCategory.EXACT_DUPLICATE, duplicatesOnly.single().suggestion.category)
    }

    @Test
    fun `tier totals use the same cut-offs as SuggestionTier and skip dismissed suggestions`() = runTest {
        val scores = listOf(0, 49, 50, 79, 80, 100)
        scores.forEachIndexed { index, score ->
            suggestedFile("/r/score-$score.bin", score = score, sizeBytes = 1L + index)
        }
        suggestedFile("/r/dismissed.bin", score = 100, sizeBytes = 1_000L, dismissed = true)

        val totals = dao.observeTierTotals().first().associateBy { it.tier }

        // Expected values are derived from the enum, which pins the SQL literals to SuggestionTier.
        val expectedByTier = scores.withIndex().groupBy { SuggestionTier.fromScore(it.value) }
        assertEquals(expectedByTier.keys, totals.keys)
        expectedByTier.forEach { (tier, items) ->
            assertEquals("item count for $tier", items.size, totals.getValue(tier).itemCount)
            assertEquals("bytes for $tier", items.sumOf { 1L + it.index }, totals.getValue(tier).totalBytes)
        }
        assertEquals(scores.size, totals.values.sumOf { it.itemCount })
        assertEquals(scores.indices.sumOf { 1L + it }, totals.values.sumOf { it.totalBytes })
    }

    @Test
    fun `category totals count active suggestions per category`() = runTest {
        suggestedFile("/r/a.bin", score = 90, sizeBytes = 10L)
        suggestedFile("/r/b.bin", score = 85, sizeBytes = 15L)
        suggestedFile("/r/c.bin", score = 70, sizeBytes = 100L, category = SuggestionCategory.LARGE_FORGOTTEN)
        suggestedFile("/r/d.bin", score = 95, sizeBytes = 500L, dismissed = true)

        val totals = dao.observeCategoryTotals().first().associateBy { it.category }

        assertEquals(setOf(SuggestionCategory.EXACT_DUPLICATE, SuggestionCategory.LARGE_FORGOTTEN), totals.keys)
        assertEquals(2, totals.getValue(SuggestionCategory.EXACT_DUPLICATE).itemCount)
        assertEquals(25L, totals.getValue(SuggestionCategory.EXACT_DUPLICATE).totalBytes)
        assertEquals(100L, totals.getValue(SuggestionCategory.LARGE_FORGOTTEN).totalBytes)
    }

    @Test
    fun `dismissed file ids round-trip`() = runTest {
        val first = suggestedFile("/r/a.bin", score = 90)
        val second = suggestedFile("/r/b.bin", score = 80)

        assertEquals(1, dao.setDismissed(listOf(first), true))
        assertEquals(listOf(first), dao.getDismissedFileIds())
        assertEquals(listOf(second), suggestionFileIds(includeDismissed = false))

        assertEquals(1, dao.setDismissed(listOf(first), false))
        assertEquals(emptyList<Long>(), dao.getDismissedFileIds())

        assertEquals(1, dao.deleteByFileIds(listOf(second)))
        assertEquals(1, dao.observeActiveCount().first())
    }

    @Test
    fun `totals over an empty table are empty rather than null`() = runTest {
        assertTrue(dao.observeTierTotals().first().isEmpty())
        assertTrue(dao.observeCategoryTotals().first().isEmpty())
        assertEquals(0, dao.observeActiveCount().first())
    }

    @Test
    fun `a domain suggestion exposes every reason behind its score`() = runTest {
        val fileId = suggestedFile(
            "/r/a.bin",
            score = 85,
            reasons = listOf(ReasonCode.EXACT_COPY, ReasonCode.IN_DOWNLOADS, ReasonCode.STALE_90_DAYS),
        )

        val suggestion = dao.observeWithFile(category = null, includeDismissed = false).first().single().toDomain()

        assertEquals(fileId, suggestion.fileId)
        assertEquals(ReasonCode.EXACT_COPY, suggestion.primaryReason)
        assertEquals(
            listOf(ReasonCode.EXACT_COPY, ReasonCode.IN_DOWNLOADS, ReasonCode.STALE_90_DAYS),
            suggestion.reasons,
        )
        assertEquals(SuggestionTier.SAFE_TO_CLEAN, suggestion.tier)
        assertEquals("/r/a.bin", suggestion.file.path)
    }
}
