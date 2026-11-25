package com.cleansweep.app.domain.scanner.trash

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.data.local.db.entity.WhitelistRuleEntity
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.TrashEntry
import com.cleansweep.app.domain.model.TrashPolicy
import com.cleansweep.app.domain.model.WhitelistRuleType
import com.cleansweep.app.domain.scanner.SCAN_NOW
import com.cleansweep.app.domain.scanner.ScanHarness
import com.cleansweep.app.domain.util.StoragePaths
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * Moving files to Safe Trash and getting them back, over real files and a real in-memory database.
 * Every file here goes through a real scan first, so that what is trashed is what the engine suggested.
 */
@RunWith(AndroidJUnit4::class)
class TrashManagerTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var scans: ScanHarness
    private lateinit var trash: TrashHarness
    private val content: ByteArray = "the notes".toByteArray()

    @Before
    fun setUp() {
        scans = ScanHarness(temporaryFolder.root)
        trash = TrashHarness(scans)
    }

    @After
    fun tearDown() {
        scans.close()
    }

    private fun at(relative: String): String = scans.at(relative)

    private fun onDisk(path: String): Boolean = File(path).exists()

    private suspend fun entries(): List<TrashEntry> = trash.manifest.observeEntries().first()

    /** Writes a file the scan suggests as temporary junk, scans, and returns what the index knows about it. */
    private suspend fun suggestedFile(relative: String = "Download/notes.log"): IndexedFile {
        scans.write(relative, content)
        scans.scan()
        return requireNotNull(scans.indexed(at(relative)))
    }

    /** Trashes a suggested file and returns its manifest entry. */
    private suspend fun trashOne(relative: String = "Download/notes.log"): TrashEntry =
        trash.manager.trash(listOf(suggestedFile(relative).id)).trashed.single()

    /** Three identical reports of which one is the Original by default, scanned. */
    private suspend fun threeCopies(): Pair<IndexedFile, List<IndexedFile>> {
        scans.write("Documents/report.pdf", 50_000, seed = 1, modifiedDaysAgo = 400)
        scans.write("Download/report.pdf", 50_000, seed = 1, modifiedDaysAgo = 300)
        scans.write("Backup/report.pdf", 50_000, seed = 1, modifiedDaysAgo = 200)
        scans.scan()
        val original = requireNotNull(scans.indexed(at("Documents/report.pdf")))
        return original to scans.storedSuggestions().map { it.file }
    }

    /** An empty folder the scan suggests, scanned, as the index knows it. */
    private suspend fun suggestedEmptyFolder(relative: String = "Download/Old Project"): IndexedFile {
        scans.folder(relative)
        scans.ageFolder(relative)
        scans.scan()
        return requireNotNull(scans.indexed(at(relative)))
    }

    // ---- Moving to the trash ----

    @Test
    fun `trashing a file moves it aside under a hidden name and records it`() = runBlocking {
        val file = suggestedFile()

        val result = trash.manager.trash(listOf(file.id))

        assertTrue(result.refused.toString(), result.refused.isEmpty())
        val entry = result.trashed.single()
        assertEquals(at("Download/notes.log"), entry.originalPath)
        assertEquals(at("Download"), StoragePaths.parentOf(entry.trashPath))
        assertTrue(TrashNaming.isTrashedName(StoragePaths.nameOf(entry.trashPath)))
        assertFalse(onDisk(entry.originalPath))
        assertArrayEquals(content, File(entry.trashPath).readBytes())
        assertEquals(TrashPolicy.expiresAt(entry.deletedAt), entry.expiresAt)
        assertEquals(content.size.toLong(), result.bytesTrashed)
        assertEquals(listOf(entry.id), result.entryIds)

        // The index and the suggestions forgot it, the manifest knows it.
        assertNull(scans.indexed(entry.originalPath))
        assertTrue(scans.storedSuggestions().isEmpty())
        assertEquals(listOf(entry), entries())

        // The gallery is told about both paths, and the purge is planned.
        assertEquals(listOf(entry.originalPath, entry.trashPath), trash.mediaScans.last)
        assertEquals(1, trash.scheduler.scheduled)
    }

    @Test
    fun `a batch is handled file by file, and every entry gets a name of its own`() = runBlocking {
        val first = suggestedFile("Download/notes.log")
        val second = suggestedFile("Download/notes (1).log")
        scans.write("Documents/keep.txt", content)
        scans.scan()
        val keep = requireNotNull(scans.indexed(at("Documents/keep.txt")))

        val result = trash.manager.trash(listOf(first.id, second.id, keep.id, 999_999L))

        assertEquals(listOf(at("Download/notes.log"), at("Download/notes (1).log")), result.trashed.map { it.originalPath })
        assertEquals(2 * content.size.toLong(), result.bytesTrashed)
        assertEquals(listOf(TrashRefusal.NOT_SUGGESTED, TrashRefusal.NOT_INDEXED), result.refused.map { it.reason })
        assertEquals("", result.refused.last().path)
        assertEquals(2, result.trashed.map { StoragePaths.nameOf(it.trashPath) }.toSet().size)
        assertTrue(onDisk(keep.path))
        assertEquals(2, entries().size)
        assertEquals("planned once, not once per file", 1, trash.scheduler.scheduled)
    }

    @Test
    fun `nothing is trashed that is not suggested at this moment`() = runBlocking {
        val log = suggestedFile()
        scans.write("Documents/keep.txt", content)
        scans.scan()
        val keep = requireNotNull(scans.indexed(at("Documents/keep.txt")))
        scans.suggestions.setDismissed(listOf(log.id), dismissed = true)

        val result = trash.manager.trash(listOf(keep.id, log.id))

        assertTrue(result.trashed.isEmpty())
        assertEquals(listOf(TrashRefusal.NOT_SUGGESTED, TrashRefusal.NOT_SUGGESTED), result.refused.map { it.reason })
        assertTrue(onDisk(log.path))
        assertTrue(onDisk(keep.path))
        assertTrue(entries().isEmpty())
        assertEquals(0, trash.scheduler.scheduled)
    }

    @Test
    fun `a whitelisted file is never trashed`() = runBlocking {
        val file = suggestedFile()
        // Written straight to the database: adding a rule the normal way also removes the file from the index.
        scans.database.whitelistRuleDao().insert(
            WhitelistRuleEntity(pathPattern = file.path, ruleType = WhitelistRuleType.FILE, createdAt = 1L),
        )

        val result = trash.manager.trash(listOf(file.id))

        assertEquals(listOf(TrashRefusal.WHITELISTED), result.refused.map { it.reason })
        assertTrue(onDisk(file.path))
        assertTrue(entries().isEmpty())
    }

    @Test
    fun `the Original of a duplicate group is never trashed`() = runBlocking {
        val (original, redundant) = threeCopies()

        val result = trash.manager.trash(redundant.map { it.id } + original.id)

        assertEquals(setOf(at("Download/report.pdf"), at("Backup/report.pdf")), result.trashed.map { it.originalPath }.toSet())
        // The Original is not offered for deletion in the first place, so it is refused like anything else
        // that is not suggested.
        assertEquals(listOf(TrashRefusal.NOT_SUGGESTED), result.refused.map { it.reason })
        assertTrue(onDisk(original.path))
        assertTrue("the reports left are not duplicates of anything", scans.fileIndex.getDuplicateGroups().isEmpty())
    }

    @Test
    fun `a copy that became the Original since the scan is not trashed on an old suggestion`() = runBlocking {
        val (original, redundant) = threeCopies()
        // The Original disappears some other way, and no scan has run since.
        assertTrue(File(original.path).delete())
        // Of the copies left, the policy keeps the one outside Downloads, however old the other one is.
        val newOriginal = redundant.first { it.path == at("Backup/report.pdf") }

        val result = trash.manager.trash(redundant.map { it.id })

        assertEquals(listOf(at("Download/report.pdf")), result.trashed.map { it.originalPath })
        assertEquals(listOf(TrashRefusal.PROTECTED_ORIGINAL), result.refused.map { it.reason })
        assertTrue(onDisk(newOriginal.path))
    }

    @Test
    fun `a copy the user locked is never trashed`() = runBlocking {
        val (_, redundant) = threeCopies()
        val backup = redundant.first { it.path == at("Backup/report.pdf") }
        scans.fileIndex.setOriginalLocked(backup.id, locked = true)

        val result = trash.manager.trash(listOf(backup.id))

        assertEquals(listOf(TrashRefusal.PROTECTED_ORIGINAL), result.refused.map { it.reason })
        assertTrue(onDisk(backup.path))
        assertTrue(entries().isEmpty())
    }

    @Test
    fun `asking for every copy of a group at once still leaves one in place`() = runBlocking {
        val (original, redundant) = threeCopies()

        val result = trash.manager.trash(redundant.map { it.id } + original.id + redundant.first().id)

        assertEquals(2, result.trashed.size)
        assertEquals(1, result.refused.size)
        assertEquals("every file asked for is accounted for once", 3, result.trashed.size + result.refused.size)
        val survivors = (redundant + original).filter { onDisk(it.path) }
        assertEquals(listOf(original), survivors)
    }

    @Test
    fun `a file that changed since the scan is left alone for the next scan`() = runBlocking {
        val file = suggestedFile()
        File(file.path).writeBytes("twice as much content now!!".toByteArray())

        val result = trash.manager.trash(listOf(file.id))

        assertEquals(listOf(TrashRefusal.CHANGED_SINCE_SUGGESTED), result.refused.map { it.reason })
        assertTrue(onDisk(file.path))
        assertNotNull(scans.indexed(file.path))
        assertEquals(1, scans.storedSuggestions().size)
        assertTrue(entries().isEmpty())
    }

    @Test
    fun `a file that is gone is forgotten`() = runBlocking {
        val file = suggestedFile()
        assertTrue(File(file.path).delete())

        val result = trash.manager.trash(listOf(file.id))

        assertEquals(listOf(TrashRefusal.GONE), result.refused.map { it.reason })
        assertNull(scans.indexed(file.path))
        assertTrue(scans.storedSuggestions().isEmpty())
        assertTrue(entries().isEmpty())
    }

    @Test
    fun `read-only storage refuses the deletion and leaves no trace`() = runBlocking {
        val file = suggestedFile()
        trash.storage.makeReadOnly(file.path)

        val result = trash.manager.trash(listOf(file.id))

        assertEquals(listOf(TrashRefusal.STORAGE_READ_ONLY), result.refused.map { it.reason })
        assertTrue(onDisk(file.path))
        assertNotNull(scans.indexed(file.path))
        assertEquals(1, scans.storedSuggestions().size)
        assertTrue(entries().isEmpty())
        assertEquals(0, trash.scheduler.scheduled)
        assertTrue(trash.mediaScans.calls.isEmpty())
    }

    // ---- Empty folders ----

    @Test
    fun `an empty folder moves to the trash as a folder, and no scan ever sees it again`() = runBlocking {
        val folder = suggestedEmptyFolder()

        val result = trash.manager.trash(listOf(folder.id))

        val entry = result.trashed.single()
        assertTrue(File(entry.trashPath).isDirectory)
        assertFalse(onDisk(entry.originalPath))
        assertNull(scans.indexed(entry.originalPath))

        scans.scan()

        assertNull(scans.indexed(entry.trashPath))
        assertTrue("a folder holding a trashed entry is not empty", scans.storedSuggestions().isEmpty())
    }

    @Test
    fun `a folder that received a file since the scan is left alone`() = runBlocking {
        val folder = suggestedEmptyFolder()
        scans.write("Download/Old Project/letter.pdf", 10, seed = 1)

        val result = trash.manager.trash(listOf(folder.id))

        assertEquals(listOf(TrashRefusal.CHANGED_SINCE_SUGGESTED), result.refused.map { it.reason })
        assertTrue(onDisk(folder.path))
        assertTrue(onDisk(at("Download/Old Project/letter.pdf")))
        assertTrue(entries().isEmpty())
    }

    // ---- Restoring ----

    @Test
    fun `restore puts a file back where it came from and forgets the entry`() = runBlocking {
        val entry = trashOne()

        val restored = trash.manager.restore(listOf(entry.id))

        assertEquals(listOf(entry.originalPath), restored.restoredPaths)
        assertTrue(restored.failures.isEmpty())
        assertTrue(restored.nothingLeft.isEmpty())
        assertArrayEquals(content, File(entry.originalPath).readBytes())
        assertFalse(onDisk(entry.trashPath))
        assertTrue(entries().isEmpty())
        assertEquals(1, trash.scheduler.cancelled)
        assertEquals(listOf(entry.originalPath), trash.mediaScans.last)
    }

    @Test
    fun `restore puts a file next to one that has taken its old name`() = runBlocking {
        val entry = trashOne()
        val other = "someone else's notes".toByteArray()
        File(entry.originalPath).writeBytes(other)
        File(at("Download/notes (restored).log")).writeBytes(other)

        val restored = trash.manager.restore(listOf(entry.id))

        assertEquals(listOf(at("Download/notes (restored 2).log")), restored.restoredPaths)
        assertArrayEquals(content, File(restored.restoredPaths.single()).readBytes())
        assertArrayEquals(other, File(entry.originalPath).readBytes())
        assertArrayEquals(other, File(at("Download/notes (restored).log")).readBytes())
    }

    @Test
    fun `restore creates the folder the file came from when that no longer exists`() = runBlocking {
        val trashedPath = at("Other/.cleansweep-trashed-0123456789abcdef-notes.log")
        File(trashedPath).apply {
            parentFile!!.mkdirs()
            writeBytes(content)
        }
        val entry = trash.manifest.record(
            TrashEntry(
                originalPath = at("A/B/notes.log"),
                trashPath = trashedPath,
                sizeBytes = content.size.toLong(),
                deletedAt = SCAN_NOW,
                expiresAt = TrashPolicy.expiresAt(SCAN_NOW),
            ),
        )

        val restored = trash.manager.restore(listOf(entry.id))

        assertEquals(listOf(at("A/B/notes.log")), restored.restoredPaths)
        assertArrayEquals(content, File(at("A/B/notes.log")).readBytes())
        assertFalse(onDisk(trashedPath))
        assertTrue(entries().isEmpty())
    }

    @Test
    fun `a trashed folder comes back as a folder`() = runBlocking {
        val folder = suggestedEmptyFolder()
        val entry = trash.manager.trash(listOf(folder.id)).trashed.single()

        val restored = trash.manager.restore(listOf(entry.id))

        assertEquals(listOf(folder.path), restored.restoredPaths)
        assertTrue(File(folder.path).isDirectory)
        assertFalse(onDisk(entry.trashPath))
    }

    @Test
    fun `restore fails on read-only storage and keeps the entry`() = runBlocking {
        val entry = trashOne()
        trash.storage.makeReadOnly(entry.trashPath)

        val restored = trash.manager.restore(listOf(entry.id))

        assertEquals(listOf(RestoreFailureItem(entry.originalPath, RestoreFailure.STORAGE_READ_ONLY)), restored.failures)
        assertTrue(restored.restoredPaths.isEmpty())
        assertEquals(listOf(entry.id), entries().map { it.id })
        assertTrue(onDisk(entry.trashPath))
    }

    @Test
    fun `restore of an entry whose file is gone forgets the entry`() = runBlocking {
        val entry = trashOne()
        assertTrue(File(entry.trashPath).delete())

        val restored = trash.manager.restore(listOf(entry.id))

        assertEquals(listOf(entry.originalPath), restored.nothingLeft)
        assertTrue(restored.restoredPaths.isEmpty())
        assertTrue(entries().isEmpty())
        assertEquals(1, trash.scheduler.cancelled)
    }

    @Test
    fun `restoring one of several leaves the others where they are`() = runBlocking {
        val first = trashOne("Download/first.log")
        val second = trashOne("Download/second.log")

        val restored = trash.manager.restore(listOf(first.id))

        assertEquals(listOf(first.originalPath), restored.restoredPaths)
        assertEquals(listOf(second.originalPath), entries().map { it.originalPath })
        assertTrue(onDisk(second.trashPath))
        assertEquals(0, trash.scheduler.cancelled)
    }

    @Test
    fun `a row that does not point at a trashed entry is never moved`() = runBlocking {
        scans.write("Documents/keep.txt", content)
        val entry = trash.manifest.record(
            TrashEntry(
                originalPath = at("Documents/elsewhere.txt"),
                trashPath = at("Documents/keep.txt"),
                sizeBytes = content.size.toLong(),
                deletedAt = SCAN_NOW,
                expiresAt = TrashPolicy.expiresAt(SCAN_NOW),
            ),
        )

        val restored = trash.manager.restore(listOf(entry.id))

        assertEquals(listOf(at("Documents/elsewhere.txt")), restored.nothingLeft)
        assertTrue(onDisk(at("Documents/keep.txt")))
        assertFalse(onDisk(at("Documents/elsewhere.txt")))
        assertTrue(entries().isEmpty())
    }

    @Test
    fun `asking for nothing does nothing`() = runBlocking {
        assertEquals(TrashResult.NOTHING, trash.manager.trash(emptyList()))
        assertTrue(trash.manager.restore(emptyList()).restoredPaths.isEmpty())
        assertTrue(entries().isEmpty())
    }
}
