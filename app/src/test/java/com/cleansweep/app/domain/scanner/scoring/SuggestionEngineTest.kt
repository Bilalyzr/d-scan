package com.cleansweep.app.domain.scanner.scoring

import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.domain.model.UserPreferences
import com.cleansweep.app.domain.scanner.junk.DetectionContext
import com.cleansweep.app.domain.scanner.junk.Finding
import com.cleansweep.app.domain.scanner.junk.TempFileDetector
import com.cleansweep.app.domain.scanner.junk.ZeroByteDetector
import com.cleansweep.app.file
import com.cleansweep.app.folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY: Long = 24L * 60 * 60 * 1000
private const val NOW: Long = 1_800_000_000_000L

class SuggestionEngineTest {

    private val engine = SuggestionEngine()
    private val oldContext = DetectionContext(NOW, UserPreferences())

    private fun finding(
        id: Long,
        path: String,
        vararg reasons: ReasonCode,
        category: SuggestionCategory = SuggestionCategory.TEMP_FILE,
        modifiedDaysAgo: Long = 200,
        sizeBytes: Long = 100L,
    ) = Finding(
        file(path, sizeBytes = sizeBytes, lastModified = NOW - modifiedDaysAgo * DAY).copy(id = id),
        category,
        reasons.toList(),
    )

    @Test
    fun `a file reported by several detectors becomes one suggestion that carries every signal`() {
        val findings = listOf(
            finding(11, "/r/Download/app.apk", ReasonCode.EXACT_COPY, category = SuggestionCategory.EXACT_DUPLICATE),
            finding(11, "/r/Download/app.apk", ReasonCode.INSTALLED_APK, category = SuggestionCategory.OBSOLETE_INSTALLER),
        )

        val draft = engine.build(findings, oldContext).single()

        assertEquals(11L, draft.fileId)
        // The strongest defining signal decides the category; the others stay on as explanation.
        assertEquals(SuggestionCategory.EXACT_DUPLICATE, draft.category)
        assertEquals(listOf(ReasonCode.EXACT_COPY, ReasonCode.INSTALLED_APK, ReasonCode.IN_DOWNLOADS, ReasonCode.STALE_90_DAYS), draft.reasons)
        assertEquals(100, draft.confidenceScore)
    }

    @Test
    fun `where a file lies and how long it sat untouched are added to every finding`() {
        val oldLog = file("/r/Documents/notes.log", lastModified = NOW - 200 * DAY).copy(id = 1)
        val sentCopy = file("/r/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Sent/v.mp4", lastModified = NOW - 200 * DAY).copy(id = 2)
        val freshTemp = file("/r/Download/fresh.tmp", lastModified = NOW - DAY).copy(id = 3)
        val findings = TempFileDetector().detect(listOf(oldLog, freshTemp), oldContext) +
            listOf(Finding(sentCopy, SuggestionCategory.EXACT_DUPLICATE, listOf(ReasonCode.EXACT_COPY)))

        val byId = engine.build(findings, oldContext).associateBy { it.fileId }

        assertEquals(listOf(ReasonCode.TEMP_OR_LOG, ReasonCode.STALE_90_DAYS), byId.getValue(1L).reasons)
        assertEquals(listOf(ReasonCode.EXACT_COPY, ReasonCode.IN_MESSAGING_SENT, ReasonCode.STALE_90_DAYS), byId.getValue(2L).reasons)
        assertEquals(listOf(ReasonCode.TEMP_OR_LOG, ReasonCode.IN_DOWNLOADS, ReasonCode.RECENT), byId.getValue(3L).reasons)
    }

    @Test
    fun `an item that changed lately is only recommended for review`() {
        val fresh = file("/r/empty.dat", sizeBytes = 0, lastModified = NOW - 2 * DAY).copy(id = 5)

        val draft = engine.build(ZeroByteDetector().detect(listOf(fresh), oldContext), oldContext).single()

        assertEquals(SuggestionCategory.ZERO_BYTE, draft.category)
        assertEquals(listOf(ReasonCode.ZERO_BYTE, ReasonCode.RECENT), draft.reasons)
        assertEquals(SuggestionTier.REVIEW_RECOMMENDED, SuggestionTier.fromScore(draft.confidenceScore))
    }

    @Test
    fun `a large old file is never scored into the safe tier`() {
        val draft = engine.build(
            listOf(finding(6, "/r/Download/holiday.mp4", ReasonCode.LARGE_FILE, category = SuggestionCategory.LARGE_FORGOTTEN)),
            oldContext,
        ).single()

        assertEquals(SuggestionCategory.LARGE_FORGOTTEN, draft.category)
        assertEquals(SuggestionTier.REVIEW_RECOMMENDED.maxScore, draft.confidenceScore)
    }

    @Test
    fun `findings that only modify are dropped, because there is nothing to explain`() {
        val stray = Finding(file("/r/Download/a.pdf").copy(id = 9), SuggestionCategory.LARGE_FORGOTTEN, listOf(ReasonCode.IN_DOWNLOADS))

        assertTrue(engine.build(listOf(stray), oldContext).isEmpty())
        assertTrue(engine.build(emptyList(), oldContext).isEmpty())
    }

    @Test
    fun `the output is in a stable order and names each file once`() {
        val findings = listOf(
            finding(30, "/r/c.tmp", ReasonCode.TEMP_OR_LOG),
            finding(10, "/r/a.tmp", ReasonCode.TEMP_OR_LOG),
            finding(20, "/r/empty", ReasonCode.EMPTY_FOLDER, category = SuggestionCategory.EMPTY_FOLDER),
            finding(10, "/r/a.tmp", ReasonCode.TEMP_OR_LOG),
        )

        val drafts = engine.build(findings, oldContext)

        // By category first (temporary files before empty folders), then by file id.
        assertEquals(listOf(10L, 30L, 20L), drafts.map { it.fileId })
        assertEquals(listOf(ReasonCode.EMPTY_FOLDER, ReasonCode.STALE_90_DAYS), drafts.last { it.fileId == 20L }.reasons)
    }

    @Test
    fun `an empty folder is a certain find, and so is an installed app's installer`() {
        val drafts = engine.build(
            listOf(
                finding(1, "/r/a/b", ReasonCode.EMPTY_FOLDER, category = SuggestionCategory.EMPTY_FOLDER),
                finding(2, "/r/app.apk", ReasonCode.INSTALLED_APK, category = SuggestionCategory.OBSOLETE_INSTALLER, modifiedDaysAgo = 1),
            ),
            oldContext,
        )

        assertTrue(drafts.all { SuggestionTier.fromScore(it.confidenceScore) == SuggestionTier.SAFE_TO_CLEAN })
    }

    @Test
    fun `folder placeholders go through the engine like any other entry`() {
        val placeholder = folder("/r/Download/Old Project").copy(id = 4)

        val draft = engine.build(listOf(Finding(placeholder, SuggestionCategory.EMPTY_FOLDER, listOf(ReasonCode.EMPTY_FOLDER))), oldContext).single()

        assertEquals(SuggestionCategory.EMPTY_FOLDER, draft.category)
        assertEquals(listOf(ReasonCode.EMPTY_FOLDER, ReasonCode.IN_DOWNLOADS, ReasonCode.STALE_90_DAYS), draft.reasons)
        assertEquals(100, draft.confidenceScore)
    }
}
