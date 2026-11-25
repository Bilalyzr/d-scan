package com.cleansweep.app.domain.scanner.duplicates

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.scanner.ScanHarness
import com.cleansweep.app.domain.scanner.paths
import com.cleansweep.app.domain.scanner.SuggestionGuard
import com.cleansweep.app.domain.scanner.trash.TrashHarness
import com.cleansweep.app.domain.scanner.trash.TrashRefusal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Keeping another copy of a group of identical files, over real files and a real database: what is offered
 * follows at once, with the reasons a scan would give.
 */
@RunWith(AndroidJUnit4::class)
class DuplicateGroupEditorTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var scans: ScanHarness
    private lateinit var editor: DuplicateGroupEditor

    @Before
    fun setUp() {
        scans = ScanHarness(temporaryFolder.root)
        editor = DuplicateGroupEditor(
            fileIndex = scans.fileIndex,
            suggestions = scans.suggestions,
            whitelist = scans.whitelist,
            installedApks = scans.installedApks,
            guard = SuggestionGuard(),
            settings = scans.settings,
            clock = scans.clock,
            ioDispatcher = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        scans.close()
    }

    /** Content large enough for the duplicate pipeline to look at: copies under 1 KB are never hashed. */
    private val content = ByteArray(2_048) { index -> (index % 251).toByte() }

    /** Two identical files, the older one in Download. Returns their paths, download first. */
    private fun identicalPair(
        name: String = "report.pdf",
        content: ByteArray = this.content,
    ): Pair<String, String> {
        scans.write("Download/$name", content, modifiedDaysAgo = 300)
        scans.write("Documents/$name", content, modifiedDaysAgo = 100)
        return scans.at("Download/$name") to scans.at("Documents/$name")
    }

    private suspend fun suggestionFor(
        path: String,
    ): Suggestion? = scans.suggestions.observeSuggestions(includeDismissed = true).first()
        .firstOrNull { it.file.path == path }

    @Test
    fun `keeping another copy offers the copy that was kept until then, without a new scan`() = runBlocking {
        val (download, documents) = identicalPair()
        scans.scan()
        // By the usual rule the copy outside Download is kept, and only the other is offered.
        assertNull(suggestionFor(documents))
        assertEquals(download, scans.storedSuggestions().single().file.path)
        val downloadCopy = requireNotNull(scans.indexed(download))

        assertEquals(KeepResult.Kept, editor.keepCopy(downloadCopy.id))

        val offered = scans.storedSuggestions().single()
        assertEquals(documents, offered.file.path)
        assertNull("The kept copy is not offered", suggestionFor(download))
        assertTrue(requireNotNull(scans.indexed(download)).isOriginalLocked)
    }

    @Test
    fun `the copy offered after the change has the suggestion a scan would give it`() = runBlocking {
        val (download, documents) = identicalPair()
        scans.scan()
        editor.keepCopy(requireNotNull(scans.indexed(download)).id)
        val afterChange = requireNotNull(suggestionFor(documents))

        scans.scan()
        val afterScan = requireNotNull(suggestionFor(documents))

        // The row of the file itself was written again by the scan, so only the suggestion is compared.
        assertEquals(afterScan.category, afterChange.category)
        assertEquals(afterScan.confidenceScore, afterChange.confidenceScore)
        assertEquals(afterScan.primaryReason, afterChange.primaryReason)
        assertEquals(afterScan.reasons, afterChange.reasons)
        assertEquals(afterScan.isDismissed, afterChange.isDismissed)
    }

    @Test
    fun `the copy that was kept until then can be moved to the trash straight away`() = runBlocking {
        val (download, documents) = identicalPair()
        scans.scan()
        val trash = TrashHarness(scans)
        editor.keepCopy(requireNotNull(scans.indexed(download)).id)

        val result = trash.manager.trash(listOf(requireNotNull(scans.indexed(documents)).id))

        assertTrue(result.refused.toString(), result.refused.isEmpty())
        assertEquals(documents, result.trashed.single().originalPath)
    }

    @Test
    fun `what else a copy is stays part of why it is offered`() = runBlocking {
        // Two identical log files: each is a temporary file and an exact copy of the other.
        scans.write("logs/notes.log", content)
        scans.write("logs/notes (1).log", content)
        scans.scan()
        val original = scans.at("logs/notes.log")
        val copy = scans.at("logs/notes (1).log")
        val before = requireNotNull(suggestionFor(copy))
        assertEquals(SuggestionCategory.EXACT_DUPLICATE, before.category)

        editor.keepCopy(requireNotNull(scans.indexed(copy)).id)

        val after = requireNotNull(suggestionFor(original))
        assertEquals(before.category, after.category)
        assertEquals(before.confidenceScore, after.confidenceScore)
        assertEquals(before.reasons, after.reasons)
    }

    @Test
    fun `stopping keeping a copy returns the group to its usual rule`() = runBlocking {
        val (download, documents) = identicalPair()
        scans.scan()
        val downloadCopy = requireNotNull(scans.indexed(download))
        editor.keepCopy(downloadCopy.id)

        assertEquals(ReleaseResult.Released, editor.releaseCopy(downloadCopy.id))

        assertFalse(requireNotNull(scans.indexed(download)).isOriginalLocked)
        assertEquals(download, scans.storedSuggestions().single().file.path)
        assertNull(suggestionFor(documents))
    }

    @Test
    fun `several copies can be kept, and each of them stays`() = runBlocking {
        scans.write("Download/a.pdf", content, modifiedDaysAgo = 300)
        scans.write("Documents/a.pdf", content, modifiedDaysAgo = 200)
        scans.write("Backup/a.pdf", content, modifiedDaysAgo = 100)
        scans.scan()
        // The usual rule keeps the oldest copy outside Download, which is the one in Documents (200 days, against
        // 100 for Backup). The other two are offered.
        val offeredBefore = scans.storedSuggestions().paths().toSet()
        assertEquals(setOf(scans.at("Download/a.pdf"), scans.at("Backup/a.pdf")), offeredBefore)

        editor.keepCopy(requireNotNull(scans.indexed(scans.at("Download/a.pdf"))).id)
        editor.keepCopy(requireNotNull(scans.indexed(scans.at("Backup/a.pdf"))).id)

        assertEquals("Only the copy that is not kept is offered now", listOf(scans.at("Documents/a.pdf")), scans.storedSuggestions().paths())
    }

    @Test
    fun `a copy that is kept already, one that is not a duplicate, and one that is gone`() = runBlocking {
        val (download, documents) = identicalPair()
        scans.write("Documents/notes.txt", "different".toByteArray())
        scans.scan()

        val kept = requireNotNull(scans.indexed(documents))
        // The copy kept by the usual rule is not locked, so it can be locked on purpose.
        assertEquals(KeepResult.Kept, editor.keepCopy(kept.id))
        assertEquals(KeepResult.AlreadyKept, editor.keepCopy(kept.id))

        val alone = requireNotNull(scans.indexed(scans.at("Documents/notes.txt")))
        assertEquals(KeepResult.NotADuplicate, editor.keepCopy(alone.id))
        assertEquals(ReleaseResult.NotKept, editor.releaseCopy(alone.id))
        // Kept on purpose, but the other copies are gone: there is no group to return to its usual rule.
        scans.fileIndex.setOriginalLocked(alone.id, true)
        assertEquals(ReleaseResult.NotADuplicate, editor.releaseCopy(alone.id))

        assertEquals(KeepResult.FileGone, editor.keepCopy(999_999L))
        assertEquals(ReleaseResult.FileGone, editor.releaseCopy(999_999L))
    }

    @Test
    fun `a suggestion the user ignored stays ignored when its group changes`() = runBlocking {
        val (download, documents) = identicalPair()
        scans.write("Download/report (1).pdf", content, modifiedDaysAgo = 200)
        scans.scan()
        val ignoredCopy = requireNotNull(scans.indexed(scans.at("Download/report (1).pdf")))
        scans.suggestions.setDismissed(listOf(ignoredCopy.id), dismissed = true)

        editor.keepCopy(requireNotNull(scans.indexed(download)).id)

        val still = requireNotNull(suggestionFor(scans.at("Download/report (1).pdf")))
        assertTrue(still.isDismissed)
    }

    @Test
    fun `a copy that changed on storage since the scan is not moved on the strength of the new suggestion`() = runBlocking {
        val (download, documents) = identicalPair()
        scans.scan()
        editor.keepCopy(requireNotNull(scans.indexed(download)).id)
        // Edited to other content of the same length, after the scan that suggested it.
        java.io.File(documents).writeBytes(ByteArray(2_048) { index -> (index % 249).toByte() })
        val trash = TrashHarness(scans)

        val result = trash.manager.trash(listOf(requireNotNull(scans.indexed(documents)).id))

        assertEquals(listOf(TrashRefusal.CHANGED_SINCE_SUGGESTED), result.refused.map { it.reason })
    }
}
