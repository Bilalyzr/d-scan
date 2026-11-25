package com.cleansweep.app.domain.scanner.trash

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.domain.model.TrashEntry
import com.cleansweep.app.domain.model.TrashPolicy
import com.cleansweep.app.domain.scanner.DAY_MILLIS
import com.cleansweep.app.domain.scanner.SCAN_NOW
import com.cleansweep.app.domain.scanner.ScanHarness
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
import java.io.File

/**
 * Deleting for good: the 30-day purge, emptying the trash, and the check at every app start.
 * Deleting is the one thing Safe Trash does that cannot be taken back, so each test here is about what must
 * never be deleted.
 */
@RunWith(AndroidJUnit4::class)
class TrashPurgeTest {

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

    /** Trashes a file the scan suggested as temporary junk, and returns its manifest entry. */
    private suspend fun trashOne(relative: String = "Download/notes.log"): TrashEntry {
        scans.write(relative, content)
        scans.scan()
        val file = requireNotNull(scans.indexed(at(relative)))
        return trash.manager.trash(listOf(file.id)).trashed.single()
    }

    /**
     * Writes a manifest row by hand, as if a crash had left it behind. The entry was "deleted" 31 days before
     * the test's clock, so its retention window is over.
     */
    private suspend fun recordExpiredRow(
        originalPath: String,
        trashPath: String,
    ): TrashEntry {
        val deletedAt = SCAN_NOW - 31 * DAY_MILLIS
        return trash.manifest.record(
            TrashEntry(
                originalPath = originalPath,
                trashPath = trashPath,
                sizeBytes = content.size.toLong(),
                deletedAt = deletedAt,
                expiresAt = TrashPolicy.expiresAt(deletedAt),
            ),
        )
    }

    // ---- The 30-day purge ----

    @Test
    fun `nothing is purged a day early, and everything on the day`() = runBlocking {
        val entry = trashOne()

        scans.clock.advanceBy(29 * DAY_MILLIS)
        assertEquals(PurgeResult.NOTHING, trash.manager.purgeExpired())
        assertTrue(onDisk(entry.trashPath))

        scans.clock.advanceBy(DAY_MILLIS)
        val result = trash.manager.purgeExpired()

        assertEquals(1, result.deletedEntries)
        assertFalse(onDisk(entry.trashPath))
        assertTrue(entries().isEmpty())
    }

    @Test
    fun `only the entries whose 30 days are over are purged`() = runBlocking {
        val first = trashOne("Download/first.log")
        scans.clock.advanceBy(20 * DAY_MILLIS)
        val second = trashOne("Download/second.log")
        scans.clock.advanceBy(11 * DAY_MILLIS)

        val result = trash.manager.purgeExpired()

        assertEquals(1, result.deletedEntries)
        assertEquals(content.size.toLong(), result.bytesFreed)
        assertEquals(0, result.missingEntries + result.foldersNoLongerEmpty + result.failedEntries + result.forgottenRows)
        assertFalse(onDisk(first.trashPath))
        assertTrue(onDisk(second.trashPath))
        assertEquals(listOf(second.originalPath), entries().map { it.originalPath })
        assertEquals("the trash is not empty, so the purge stays planned", 0, trash.scheduler.cancelled)
    }

    @Test
    fun `a trashed folder that is no longer empty is never purged`() = runBlocking {
        scans.folder("Download/Old Project")
        scans.ageFolder("Download/Old Project")
        scans.scan()
        val folder = requireNotNull(scans.indexed(at("Download/Old Project")))
        val entry = trash.manager.trash(listOf(folder.id)).trashed.single()
        val stranger = File(entry.trashPath, "not-mine.txt").apply { writeText("put there after the folder was trashed") }
        scans.clock.advanceBy(31 * DAY_MILLIS)

        val result = trash.manager.purgeExpired()

        assertEquals(1, result.foldersNoLongerEmpty)
        assertEquals(0, result.deletedEntries)
        assertTrue(stranger.exists())
        assertEquals(listOf(entry.id), entries().map { it.id })
    }

    @Test
    fun `an entry the storage will not let go of stays in the manifest`() = runBlocking {
        val entry = trashOne()
        scans.clock.advanceBy(31 * DAY_MILLIS)
        trash.storage.makeReadOnly(entry.trashPath)

        val result = trash.manager.purgeExpired()

        assertEquals(1, result.failedEntries)
        assertEquals(0L, result.bytesFreed)
        assertTrue(onDisk(entry.trashPath))
        assertEquals(listOf(entry.id), entries().map { it.id })
    }

    @Test
    fun `an entry whose file is already gone is forgotten`() = runBlocking {
        val entry = trashOne()
        assertTrue(File(entry.trashPath).delete())
        scans.clock.advanceBy(31 * DAY_MILLIS)

        val result = trash.manager.purgeExpired()

        assertEquals(1, result.missingEntries)
        assertEquals(0, result.deletedEntries)
        assertTrue(entries().isEmpty())
        assertEquals(1, trash.scheduler.cancelled)
    }

    // ---- Empty trash ----

    @Test
    fun `empty trash deletes everything, however recent`() = runBlocking {
        val first = trashOne("Download/first.log")
        val second = trashOne("Download/second.log")

        val result = trash.manager.emptyTrashPermanently()

        assertEquals(2, result.deletedEntries)
        assertEquals(2 * content.size.toLong(), result.bytesFreed)
        assertFalse(onDisk(first.trashPath))
        assertFalse(onDisk(second.trashPath))
        assertTrue(entries().isEmpty())
        assertEquals(1, trash.scheduler.cancelled)
    }

    @Test
    fun `purging an empty trash does nothing`() = runBlocking {
        assertEquals(PurgeResult.NOTHING, trash.manager.purgeExpired())
        assertEquals(PurgeResult.NOTHING, trash.manager.emptyTrashPermanently())
    }

    // ---- Rows that must not be trusted ----

    @Test
    fun `a row that does not describe a trashed entry is dropped without touching the file`() = runBlocking {
        scans.write("Documents/keep.txt", content)
        recordExpiredRow(originalPath = at("Documents/elsewhere.txt"), trashPath = at("Documents/keep.txt"))

        val result = trash.manager.purgeExpired()

        assertEquals(1, result.forgottenRows)
        assertEquals(0, result.deletedEntries)
        assertTrue(onDisk(at("Documents/keep.txt")))
        assertTrue(entries().isEmpty())
    }

    @Test
    fun `a row whose move never happened is forgotten at the next start`() = runBlocking {
        scans.write("Documents/keep.txt", content)
        recordExpiredRow(
            originalPath = at("Documents/keep.txt"),
            trashPath = at("Documents/.cleansweep-trashed-0123456789abcdef-keep.txt"),
        )

        val result = trash.manager.maintain()

        assertEquals(1, result.forgottenRows)
        assertEquals(0, result.purge.deletedEntries)
        assertNull(result.failure)
        assertTrue("the file was never moved and stays where it is", onDisk(at("Documents/keep.txt")))
        assertTrue(entries().isEmpty())
    }

    // ---- The check at every app start ----

    @Test
    fun `the start-up check purges what has expired and leaves the rest`() = runBlocking {
        val expired = trashOne("Download/first.log")
        scans.clock.advanceBy(31 * DAY_MILLIS)
        val fresh = trashOne("Download/second.log")

        val result = trash.manager.maintain()

        assertNull(result.failure)
        assertEquals(0, result.forgottenRows)
        assertEquals(1, result.purge.deletedEntries)
        assertFalse(onDisk(expired.trashPath))
        assertTrue(onDisk(fresh.trashPath))
        assertEquals(listOf(fresh.id), entries().map { it.id })
    }

    @Test
    fun `the start-up check survives storage that fails`() = runBlocking {
        val entry = trashOne()
        trash.storage.storageFailure = IllegalStateException("volume unreadable")

        val result = trash.manager.maintain()

        assertTrue(result.failure is IllegalStateException)
        assertTrue(onDisk(entry.trashPath))
        assertEquals(listOf(entry.id), entries().map { it.id })
    }

    // ---- The recurring purge ----

    @Test
    fun `the purge is planned once the trash holds something, and dropped once it is empty`() = runBlocking {
        val first = trashOne("Download/first.log")
        assertEquals(1, trash.scheduler.scheduled)
        val second = trashOne("Download/second.log")
        assertEquals("planned once, not once per entry", 1, trash.scheduler.scheduled)

        trash.manager.restore(listOf(first.id, second.id))

        assertEquals(1, trash.scheduler.cancelled)
        trashOne("Download/third.log")
        assertEquals(2, trash.scheduler.scheduled)
    }

    @Test
    fun `a purge that cannot be planned does not fail the deletion`() = runBlocking {
        trash.scheduler.failure = IllegalStateException("WorkManager is not available")

        val entry = trashOne("Download/notes.log")

        assertTrue("the file is in the trash all the same", onDisk(entry.trashPath))
        assertEquals(listOf(entry.id), entries().map { it.id })
        assertEquals(0, trash.scheduler.scheduled)

        // The plan is asked for again the next time the trash changes.
        trashOne("Download/second.log")
        assertEquals(1, trash.scheduler.scheduled)
    }
}
