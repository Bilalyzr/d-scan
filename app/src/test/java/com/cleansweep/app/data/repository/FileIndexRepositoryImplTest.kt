package com.cleansweep.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.data.local.db.CleanSweepDatabase
import com.cleansweep.app.data.local.db.dao.FileIndexDao
import com.cleansweep.app.data.local.db.dao.SuggestionItemDao
import com.cleansweep.app.data.local.db.entity.SuggestionItemEntity
import com.cleansweep.app.data.mapper.toEntity
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.FileHashUpdate
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionDraft
import com.cleansweep.app.file
import com.cleansweep.app.folder
import com.cleansweep.app.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private fun suggestionFor(fileId: Long): SuggestionItemEntity =
    SuggestionDraft(fileId, SuggestionCategory.TEMP_FILE, 90, listOf(ReasonCode.TEMP_OR_LOG))
        .toEntity(isDismissed = false)

private fun folderSuggestionFor(placeholderId: Long): SuggestionItemEntity =
    SuggestionDraft(placeholderId, SuggestionCategory.EMPTY_FOLDER, 95, listOf(ReasonCode.EMPTY_FOLDER))
        .toEntity(isDismissed = false)

@RunWith(AndroidJUnit4::class)
class FileIndexRepositoryImplTest {

    private lateinit var database: CleanSweepDatabase
    private lateinit var fileIndexDao: FileIndexDao
    private lateinit var suggestionItemDao: SuggestionItemDao
    private lateinit var repository: FileIndexRepositoryImpl

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        fileIndexDao = database.fileIndexDao()
        suggestionItemDao = database.suggestionItemDao()
        repository = FileIndexRepositoryImpl(database, fileIndexDao, suggestionItemDao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---- Incremental indexing ----

    @Test
    fun `a newly scanned file is stored without hashes and comes back with an id`() = runTest {
        val observed = file("/r/a.bin", partialHash = "not-trusted", fullHash = "not-trusted", isOriginalLocked = true)

        val stored = repository.applyScanBatch(listOf(observed), scanTimestamp = 5_000L).single()

        assertTrue(stored.id > 0L)
        assertNull(stored.partialHash)
        assertNull(stored.fullHash)
        assertFalse(stored.isOriginalLocked)
        assertEquals(5_000L, requireNotNull(repository.getByPath("/r/a.bin")).scanTimestamp)
    }

    @Test
    fun `an unchanged file keeps its cached hashes on the next scan`() = runTest {
        val first = repository.applyScanBatch(listOf(file("/r/a.bin")), scanTimestamp = 1_000L).single()
        repository.updatePartialHashes(listOf(FileHashUpdate(first.id, "xxh64:abc")))
        repository.updateFullHashes(listOf(FileHashUpdate(first.id, "sha256:def")))
        repository.setOriginalLocked(first.id, true)

        val second = repository.applyScanBatch(listOf(file("/r/a.bin")), scanTimestamp = 2_000L).single()

        assertEquals(first.id, second.id)
        assertEquals("xxh64:abc", second.partialHash)
        assertEquals("sha256:def", second.fullHash)
        assertEquals(2_000L, second.scanTimestamp)
        assertTrue(second.isOriginalLocked)
    }

    @Test
    fun `a file whose size changed loses both hashes but keeps its id and its Original Lock`() = runTest {
        val first = repository.applyScanBatch(listOf(file("/r/a.bin", sizeBytes = 100L)), scanTimestamp = 1_000L).single()
        repository.updatePartialHashes(listOf(FileHashUpdate(first.id, "xxh64:abc")))
        repository.updateFullHashes(listOf(FileHashUpdate(first.id, "sha256:def")))
        repository.setOriginalLocked(first.id, true)

        val changed = repository.applyScanBatch(listOf(file("/r/a.bin", sizeBytes = 101L)), scanTimestamp = 2_000L).single()

        assertEquals(first.id, changed.id)
        assertNull(changed.partialHash)
        assertNull(changed.fullHash)
        assertTrue(changed.isOriginalLocked)
    }

    @Test
    fun `a file whose modification time changed loses its hashes even when the size is the same`() = runTest {
        val first = repository.applyScanBatch(listOf(file("/r/a.bin", lastModified = 1_000L)), scanTimestamp = 1_000L).single()
        repository.updateFullHashes(listOf(FileHashUpdate(first.id, "sha256:def")))

        val changed = repository.applyScanBatch(listOf(file("/r/a.bin", lastModified = 2_000L)), scanTimestamp = 2_000L).single()

        assertNull(changed.partialHash)
        assertNull(changed.fullHash)
    }

    @Test
    fun `a batch larger than the SQLite parameter limit is indexed in one call`() = runTest {
        val files = (1..1_500).map { file("/r/file-$it.bin", sizeBytes = it.toLong()) }

        val stored = repository.applyScanBatch(files, scanTimestamp = 1_000L)
        val rescanned = repository.applyScanBatch(files, scanTimestamp = 2_000L)

        assertEquals(1_500, stored.size)
        assertEquals(1_500, stored.map { it.id }.toSet().size)
        assertEquals(stored.map { it.id }, rescanned.map { it.id })
        assertEquals(1_500, repository.observeFileCount().first())
    }

    @Test
    fun `the same path twice in one batch is stored once`() = runTest {
        val stored = repository.applyScanBatch(listOf(file("/r/a.bin"), file("/r/a.bin")), scanTimestamp = 1_000L)

        assertEquals(1, stored.size)
        assertEquals(1, repository.observeFileCount().first())
    }

    @Test
    fun `an empty batch is a no-op`() = runTest {
        assertTrue(repository.applyScanBatch(emptyList(), scanTimestamp = 1_000L).isEmpty())
        repository.updatePartialHashes(emptyList())
        repository.updateFullHashes(emptyList())
    }

    @Test
    fun `a path that changed from file to folder drops the old suggestion`() = runTest {
        val storedFile = repository.applyScanBatch(listOf(file("/r/thing")), scanTimestamp = 1_000L).single()
        suggestionItemDao.upsertAll(listOf(suggestionFor(storedFile.id)))
        assertEquals(1, suggestionItemDao.observeActiveCount().first())

        val storedFolder = repository.applyScanBatch(listOf(folder("/r/thing")), scanTimestamp = 2_000L).single()

        assertTrue(storedFolder.isDirectory)
        assertEquals(0, suggestionItemDao.observeActiveCount().first())
    }

    @Test
    fun `the stale sweep goes through the repository with the same scoping rules`() = runTest {
        repository.applyScanBatch(
            listOf(
                file("/r/Download/old.pdf", category = FileCategory.DOCUMENT, scanTimestamp = 1_000L),
                file("/r/DCIM/old.jpg", category = FileCategory.IMAGE, scanTimestamp = 1_000L),
            ),
            scanTimestamp = 1_000L,
        )

        val removed = repository.deleteStaleFiles(scanStartedAt = 2_000L, root = "/r/Download/", categories = listOf(FileCategory.DOCUMENT))

        assertEquals(1, removed)
        assertNull(repository.getByPath("/r/Download/old.pdf"))
        assertEquals("/r/DCIM/old.jpg", repository.getByPath("/r/DCIM/old.jpg")?.path)
        assertEquals(0, repository.deleteStaleFiles(scanStartedAt = 2_000L, root = "/r", categories = emptyList()))
    }

    // ---- Empty-folder placeholders ----

    @Test
    fun `empty-folder placeholders keep their ids while a vanished folder is removed with its suggestion`() =
        runTest {
            val first = repository.replaceEmptyFolderPlaceholders(
                listOf(folder("/r/A"), folder("/r/B")),
                analysisTimestamp = 1_000L,
            )
            assertTrue(first.all { it.id > 0L })
            val vanished = first.single { it.path == "/r/B" }
            suggestionItemDao.upsertAll(listOf(folderSuggestionFor(vanished.id)))

            val second = repository.replaceEmptyFolderPlaceholders(
                listOf(folder("/r/A"), folder("/r/C")),
                analysisTimestamp = 2_000L,
            )

            assertEquals(first.single { it.path == "/r/A" }.id, second.single { it.path == "/r/A" }.id)
            assertTrue(second.single { it.path == "/r/C" }.id > 0L)
            assertNull(repository.getByPath("/r/B"))
            assertEquals(0, suggestionItemDao.observeActiveCount().first())
            assertEquals(2_000L, requireNotNull(repository.getByPath("/r/A")).scanTimestamp)
            assertTrue(requireNotNull(repository.getByPath("/r/A")).isDirectory)
        }

    @Test
    fun `a file sitting where an empty folder is now is replaced`() = runTest {
        repository.applyScanBatch(listOf(file("/r/thing")), scanTimestamp = 1_000L)

        val placeholder = repository.replaceEmptyFolderPlaceholders(listOf(folder("/r/thing")), analysisTimestamp = 2_000L).single()

        assertTrue(placeholder.isDirectory)
        val row = requireNotNull(repository.getByPath("/r/thing"))
        assertTrue(row.isDirectory)
        assertEquals(0L, row.sizeBytes)
        assertEquals(placeholder.id, row.id)
    }

    @Test
    fun `plain files are rejected as placeholders`() {
        assertThrows(IllegalArgumentException::class.java) {
            runTest { repository.replaceEmptyFolderPlaceholders(listOf(file("/r/a.bin")), analysisTimestamp = 1_000L) }
        }
    }

    // ---- 3-stage duplicate pipeline ----

    @Test
    fun `a size collision narrows to a partial-hash collision and then to a full-hash collision`() = runTest {
        val stored = repository.applyScanBatch(
            listOf(
                file("/r/a.bin", sizeBytes = 500L),
                file("/r/b.bin", sizeBytes = 500L),
                file("/r/c.bin", sizeBytes = 500L),
                file("/r/unique.bin", sizeBytes = 501L),
            ),
            scanTimestamp = 1_000L,
        )
        val idByPath = stored.associate { it.path to it.id }

        // Stage 1: only the three same-sized files are candidates.
        assertEquals(
            listOf("/r/a.bin", "/r/b.bin", "/r/c.bin"),
            repository.getSizeCollisionCandidates(minSizeBytes = 1L).map { it.path },
        )

        repository.updatePartialHashes(
            listOf(
                FileHashUpdate(idByPath.getValue("/r/a.bin"), "xxh64:1"),
                FileHashUpdate(idByPath.getValue("/r/b.bin"), "xxh64:1"),
                FileHashUpdate(idByPath.getValue("/r/c.bin"), "xxh64:2"),
            ),
        )

        // Stage 2: c differs in its first and last 4 KB, so it is out.
        assertEquals(
            listOf("/r/a.bin", "/r/b.bin"),
            repository.getPartialHashCollisionCandidates().map { it.path },
        )
        assertTrue(repository.getDuplicateGroups().isEmpty())

        repository.updateFullHashes(
            listOf(
                FileHashUpdate(idByPath.getValue("/r/a.bin"), "sha256:same"),
                FileHashUpdate(idByPath.getValue("/r/b.bin"), "sha256:same"),
            ),
        )

        // Stage 3: a and b are byte-identical.
        val group = repository.getDuplicateGroups().single()
        assertEquals("sha256:same", group.fullHash)
        assertEquals(listOf("/r/a.bin", "/r/b.bin"), group.copies.map { it.path })
        assertEquals(500L, group.wastedBytes)
    }

    @Test
    fun `duplicate groups only contain real collisions, ordered oldest first`() = runTest {
        val stored = repository.applyScanBatch(
            listOf(
                file("/r/old.bin", lastModified = 1L),
                file("/r/new.bin", lastModified = 9L),
                file("/r/other.bin", lastModified = 5L),
                file("/r/lone.bin", lastModified = 2L),
            ),
            scanTimestamp = 1_000L,
        )
        val hashes = listOf("/r/old.bin", "/r/new.bin", "/r/other.bin").map { FileHashUpdate(stored.single { f -> f.path == it }.id, "sha256:same") } +
            FileHashUpdate(stored.single { it.path == "/r/lone.bin" }.id, "sha256:alone")
        repository.updateFullHashes(hashes)

        val groups = repository.getDuplicateGroups()

        assertEquals(1, groups.size)
        assertEquals(listOf("/r/old.bin", "/r/other.bin", "/r/new.bin"), groups.single().copies.map { it.path })
        assertEquals(2 * 100L, groups.single().wastedBytes)
        assertEquals(groups, repository.observeDuplicateGroups().first())
    }

    @Test
    fun `changing a file removes it from its old duplicate group on the next scan`() = runTest {
        val stored = repository.applyScanBatch(listOf(file("/r/a.bin"), file("/r/b.bin")), scanTimestamp = 1_000L)
        repository.updateFullHashes(stored.map { FileHashUpdate(it.id, "sha256:same") })
        assertEquals(1, repository.getDuplicateGroups().size)

        repository.applyScanBatch(listOf(file("/r/a.bin"), file("/r/b.bin", lastModified = 5_000L)), scanTimestamp = 2_000L)

        assertTrue(repository.getDuplicateGroups().isEmpty())
    }

    // ---- Dashboard ----

    @Test
    fun `category totals are exposed in domain form`() = runTest {
        repository.applyScanBatch(
            listOf(
                file("/r/a.pdf", sizeBytes = 10L, category = FileCategory.DOCUMENT),
                file("/r/b.jpg", sizeBytes = 20L, category = FileCategory.IMAGE),
            ),
            scanTimestamp = 1_000L,
        )

        val totals = repository.observeCategoryTotals().first().associateBy { it.category }

        assertEquals(1, totals.getValue(FileCategory.DOCUMENT).fileCount)
        assertEquals(10L, totals.getValue(FileCategory.DOCUMENT).totalBytes)
        assertEquals(20L, totals.getValue(FileCategory.IMAGE).totalBytes)
    }

    @Test
    fun `clearing the index removes files, placeholders and their suggestions`() = runTest {
        val storedFile = repository.applyScanBatch(listOf(file("/r/a.bin")), scanTimestamp = 1_000L).single()
        val placeholder = repository.replaceEmptyFolderPlaceholders(listOf(folder("/r/Empty")), analysisTimestamp = 1_000L).single()
        suggestionItemDao.upsertAll(listOf(suggestionFor(storedFile.id), folderSuggestionFor(placeholder.id)))

        repository.clearIndex()

        assertEquals(0, repository.observeFileCount().first())
        assertEquals(0, suggestionItemDao.observeActiveCount().first())
    }

    @Test
    fun `lookups and observation return domain files`() = runTest {
        val stored: IndexedFile = repository.applyScanBatch(listOf(file("/r/a.bin")), scanTimestamp = 1_000L).single()

        assertEquals(stored, repository.getById(stored.id))
        assertEquals(stored, repository.getByPath("/r/a.bin"))
        assertEquals(stored, repository.observeById(stored.id).first())
        assertNull(repository.getById(999_999L))
    }
}
