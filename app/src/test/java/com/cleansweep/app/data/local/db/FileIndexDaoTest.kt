package com.cleansweep.app.data.local.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.data.local.db.dao.FileIndexDao
import com.cleansweep.app.data.local.db.entity.SuggestionItemEntity
import com.cleansweep.app.data.mapper.toDomain
import com.cleansweep.app.data.mapper.toEntity
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.util.StoragePaths
import com.cleansweep.app.file
import com.cleansweep.app.folder
import com.cleansweep.app.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileIndexDaoTest {

    private lateinit var database: CleanSweepDatabase
    private lateinit var dao: FileIndexDao

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        dao = database.fileIndexDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insert(vararg files: IndexedFile): List<Long> = dao.upsertAll(files.map { it.toEntity() })

    private suspend fun deleteStale(
        scanStartedAt: Long,
        root: String = "/storage/emulated/0",
        categories: Collection<FileCategory> = FileCategory.entries,
    ): Int = dao.deleteStaleFiles(
        scanStartedAt = scanStartedAt,
        root = root.trimEnd('/'),
        descendantPrefix = StoragePaths.descendantPrefix(root),
        categories = categories,
    )

    private suspend fun assertIndexed(vararg paths: String) {
        paths.forEach { path -> assertTrue("$path should still be indexed", dao.getByPath(path) != null) }
    }

    private suspend fun assertGone(vararg paths: String) {
        paths.forEach { path -> assertNull("$path should have been removed", dao.getByPath(path)) }
    }

    @Test
    fun `upsert reports a new row id for an insert and minus one for an update, and the row keeps its id`() =
        runTest {
            val inserted = insert(file("/r/a.bin"))
            assertEquals(1, inserted.size)
            assertTrue(inserted.single() > 0L)

            val updated = file("/r/a.bin", sizeBytes = 7L).copy(id = inserted.single())
            assertEquals(listOf(-1L), dao.upsertAll(listOf(updated.toEntity())))

            val stored = requireNotNull(dao.getByPath("/r/a.bin")).toDomain()
            assertEquals(inserted.single(), stored.id)
            assertEquals(7L, stored.sizeBytes)
            assertEquals(1, dao.observeFileCount().first())
        }

    @Test
    fun `upsert reports insert and update results in the order the rows were passed`() = runTest {
        val (existing) = insert(file("/r/existing.bin"))

        val results = dao.upsertAll(
            listOf(
                file("/r/existing.bin", sizeBytes = 8L).copy(id = existing).toEntity(),
                file("/r/brand-new.bin").toEntity(),
            ),
        )

        assertEquals(-1L, results[0])
        assertTrue(results[1] > 0L)
        assertEquals(2, dao.observeFileCount().first())
    }

    @Test
    fun `the cache entry projection exposes exactly what the incremental scan decides on`() = runTest {
        insert(
            file(
                "/r/a.bin",
                sizeBytes = 42L,
                lastModified = 77L,
                partialHash = "p:1",
                fullHash = "f:1",
                isOriginalLocked = true,
            ),
        )

        val cached = dao.getCacheEntries(listOf("/r/a.bin", "/r/missing.bin")).single()

        assertEquals(42L, cached.sizeBytes)
        assertEquals(77L, cached.lastModified)
        assertEquals("p:1", cached.partialHash)
        assertEquals("f:1", cached.fullHash)
        assertTrue(cached.isOriginalLocked)
        assertFalse(cached.isDirectory)
    }

    @Test
    fun `the stale sweep only removes unseen files under the scanned root in the scanned categories`() = runTest {
        insert(
            file("/storage/emulated/0/Download/old.pdf", category = FileCategory.DOCUMENT, scanTimestamp = 1_000L),
            file("/storage/emulated/0/Download/new.pdf", category = FileCategory.DOCUMENT, scanTimestamp = 2_000L),
            file("/storage/emulated/0/DCIM/old.jpg", category = FileCategory.IMAGE, scanTimestamp = 1_000L),
            file("/storage/1234-5678/Download/old.pdf", category = FileCategory.DOCUMENT, scanTimestamp = 1_000L),
        )

        val removed = deleteStale(scanStartedAt = 1_500L, categories = listOf(FileCategory.DOCUMENT))

        assertEquals(1, removed)
        assertGone("/storage/emulated/0/Download/old.pdf")
        assertIndexed(
            "/storage/emulated/0/Download/new.pdf",
            "/storage/emulated/0/DCIM/old.jpg",
            "/storage/1234-5678/Download/old.pdf",
        )
    }

    @Test
    fun `a scan of one storage volume never sweeps the index of another`() = runTest {
        insert(
            file("/storage/emulated/0/a.bin", scanTimestamp = 1_000L),
            file("/storage/1234-5678/b.bin", scanTimestamp = 1_000L),
        )

        val removed = deleteStale(scanStartedAt = 2_000L, root = "/storage/1234-5678")

        assertEquals(1, removed)
        assertIndexed("/storage/emulated/0/a.bin")
    }

    @Test
    fun `the stale sweep with no categories removes nothing`() = runTest {
        insert(file("/r/a.bin", scanTimestamp = 1_000L))

        assertEquals(0, deleteStale(scanStartedAt = 2_000L, categories = emptyList()))
        assertIndexed("/r/a.bin")
    }

    @Test
    fun `the stale sweep matches folder names literally even when they contain percent, underscore or backslash`() =
        runTest {
            val root = "/storage/emulated/0/My_Folder 100% done\\new"
            val lookAlike = "/storage/emulated/0/MyXFolder 100x done\\new/keep.bin"
            insert(
                file("$root/stale.bin", scanTimestamp = 1_000L),
                file("$root/child/deeper.bin", scanTimestamp = 1_000L),
                file(lookAlike, scanTimestamp = 1_000L),
            )

            val removed = deleteStale(scanStartedAt = 2_000L, root = root)

            assertEquals(2, removed)
            assertGone("$root/stale.bin", "$root/child/deeper.bin")
            assertIndexed(lookAlike)
        }

    @Test
    fun `the stale sweep never removes empty-folder placeholders`() = runTest {
        insert(folder("/r/Empty", scanTimestamp = 1_000L))

        assertEquals(0, deleteStale(scanStartedAt = 2_000L))
        assertIndexed("/r/Empty")
    }

    @Test
    fun `a folder rule purge removes the folder itself and everything below it but no look-alike sibling`() =
        runTest {
            insert(
                file("/r/Tax"),
                file("/r/Tax/2024/return.pdf"),
                file("/r/Taxes/return.pdf"),
                file("/r/other.pdf"),
            )

            val removed = dao.deleteByPathAndDescendants(
                path = "/r/Tax",
                descendantPrefix = StoragePaths.descendantPrefix("/r/Tax"),
            )

            assertEquals(2, removed)
            assertGone("/r/Tax", "/r/Tax/2024/return.pdf")
            assertIndexed("/r/Taxes/return.pdf", "/r/other.pdf")
        }

    @Test
    fun `a file rule purge removes exactly one path`() = runTest {
        insert(file("/r/keep.pdf"), file("/r/keep.pdf.bak"))

        assertEquals(1, dao.deleteByPath("/r/keep.pdf"))
        assertIndexed("/r/keep.pdf.bak")
    }

    @Test
    fun `stage 1 only returns files that share their byte size, down to the minimum size`() = runTest {
        insert(
            file("/r/dup-a.bin", sizeBytes = 1_000L),
            file("/r/dup-b.bin", sizeBytes = 1_000L),
            file("/r/unique.bin", sizeBytes = 1_001L),
            file("/r/tiny-a", sizeBytes = 1L),
            file("/r/tiny-b", sizeBytes = 1L),
            folder("/r/Empty"),
        )

        val candidates = dao.getSizeCollisionCandidates(minSizeBytes = 2L).map { it.path }

        assertEquals(listOf("/r/dup-a.bin", "/r/dup-b.bin"), candidates)
    }

    @Test
    fun `stage 2 groups on size and partial hash together`() = runTest {
        insert(
            file("/r/a1.bin", sizeBytes = 1_000L, partialHash = "p:same"),
            file("/r/a2.bin", sizeBytes = 1_000L, partialHash = "p:same"),
            file("/r/b1.bin", sizeBytes = 1_000L, partialHash = "p:other"),
            file("/r/c1.bin", sizeBytes = 2_000L, partialHash = "p:same"),
            file("/r/no-partial.bin", sizeBytes = 2_000L),
        )

        val candidates = dao.getPartialHashCollisionCandidates().map { it.path }

        assertEquals(listOf("/r/a1.bin", "/r/a2.bin"), candidates)
    }

    @Test
    fun `stage 3 only reports files whose full hash collides`() = runTest {
        val (_, a2) = insert(
            file("/r/a1.bin", sizeBytes = 1_000L, partialHash = "p:same", fullHash = "f:same"),
            file("/r/a2.bin", sizeBytes = 1_000L, partialHash = "p:same"),
            file("/r/b1.bin", sizeBytes = 1_000L, partialHash = "p:same", fullHash = "f:different"),
        )
        assertTrue(dao.getDuplicateFiles().isEmpty())

        assertEquals(1, dao.updateFullHash(a2, "f:same"))

        assertEquals(listOf("/r/a1.bin", "/r/a2.bin"), dao.getDuplicateFiles().map { it.path })
        assertEquals(listOf("/r/a1.bin", "/r/a2.bin"), dao.observeDuplicateFiles().first().map { it.path })
    }

    @Test
    fun `hash updates never touch an empty-folder placeholder`() = runTest {
        val (fileId, folderId) = insert(file("/r/a.bin"), folder("/r/Empty"))

        assertEquals(1, dao.updatePartialHash(fileId, "p:1"))
        assertEquals(0, dao.updatePartialHash(folderId, "p:1"))
        assertEquals(0, dao.updateFullHash(folderId, "f:1"))
        assertNull(requireNotNull(dao.getByPath("/r/Empty")).partialHash)
    }

    @Test
    fun `the Original Lock is stored per file`() = runTest {
        val (a, b) = insert(file("/r/a.bin"), file("/r/b.bin"))

        assertEquals(1, dao.setOriginalLocked(a, true))
        assertEquals(0, dao.setOriginalLocked(999_999L, true))

        assertTrue(requireNotNull(dao.getById(a)).isOriginalLocked)
        assertFalse(requireNotNull(dao.getById(b)).isOriginalLocked)
        assertTrue(requireNotNull(dao.observeById(a).first()).isOriginalLocked)
    }

    @Test
    fun `dashboard aggregates ignore empty-folder placeholders`() = runTest {
        insert(
            file("/r/a.pdf", sizeBytes = 10L, category = FileCategory.DOCUMENT),
            file("/r/b.pdf", sizeBytes = 15L, category = FileCategory.DOCUMENT),
            file("/r/c.jpg", sizeBytes = 5L, category = FileCategory.IMAGE),
            folder("/r/Empty"),
        )

        assertEquals(3, dao.observeFileCount().first())
        val totals = dao.observeCategoryTotals().first().associateBy { it.category }
        assertEquals(setOf(FileCategory.DOCUMENT, FileCategory.IMAGE), totals.keys)
        assertEquals(2, totals.getValue(FileCategory.DOCUMENT).fileCount)
        assertEquals(25L, totals.getValue(FileCategory.DOCUMENT).totalBytes)
    }

    @Test
    fun `junk candidates are exactly the rows a junk detector could report`() = runTest {
        insert(
            file("/r/ordinary.bin", sizeBytes = 100L),
            file("/r/empty.dat", sizeBytes = 0L),
            file("/r/big.bin", sizeBytes = 1_000L),
            file("/r/almost-big.bin", sizeBytes = 999L),
            file("/r/notes.log", sizeBytes = 10L),
            file("/r/app.apk", sizeBytes = 10L),
            file("/r/keep.pdf", sizeBytes = 10L),
            file("/r/DCIM/.thumbnails/thumb1", sizeBytes = 10L),
            folder("/r/Empty"),
        )

        val paths = dao
            .getJunkCandidates(largeFileThresholdBytes = 1_000L, junkExtensions = listOf("log", "apk"))
            .map { it.path }
            .toSet()

        assertEquals(
            setOf("/r/empty.dat", "/r/big.bin", "/r/notes.log", "/r/app.apk", "/r/DCIM/.thumbnails/thumb1", "/r/Empty"),
            paths,
        )
    }

    @Test
    fun `removing files by path takes their suggestions with them`() = runTest {
        val (kept, removed) = insert(file("/r/keep.bin"), file("/r/gone.bin"))
        database.suggestionItemDao().upsertAll(
            listOf(
                SuggestionItemEntity(kept, SuggestionCategory.ZERO_BYTE, 90, ReasonCode.ZERO_BYTE, "ZERO_BYTE", false),
                SuggestionItemEntity(removed, SuggestionCategory.ZERO_BYTE, 90, ReasonCode.ZERO_BYTE, "ZERO_BYTE", false),
            ),
        )

        assertEquals(1, dao.deleteByPaths(listOf("/r/gone.bin")))

        assertGone("/r/gone.bin")
        assertIndexed("/r/keep.bin")
        assertEquals(
            listOf(kept),
            database.suggestionItemDao().observeWithFile(category = null, includeDismissed = true).first().map { it.suggestion.fileId },
        )
    }

    @Test
    fun `aggregates over an empty index report zero rather than nothing`() = runTest {
        assertEquals(0, dao.observeFileCount().first())
        assertTrue(dao.observeCategoryTotals().first().isEmpty())
        assertTrue(dao.getDuplicateFiles().isEmpty())
        assertEquals(emptyList<Long>(), dao.upsertAll(emptyList()))
    }
}
