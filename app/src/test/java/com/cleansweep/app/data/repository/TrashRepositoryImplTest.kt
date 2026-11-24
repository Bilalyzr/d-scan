package com.cleansweep.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.data.local.db.CleanSweepDatabase
import com.cleansweep.app.domain.model.TrashEntry
import com.cleansweep.app.domain.model.TrashPolicy
import com.cleansweep.app.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrashRepositoryImplTest {

    private lateinit var database: CleanSweepDatabase
    private lateinit var repository: TrashRepositoryImpl

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        repository = TrashRepositoryImpl(database, database.trashManifestDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun entry(
        originalPath: String,
        deletedAt: Long = 1_000L,
        sizeBytes: Long = 100L,
    ) = TrashEntry(
        originalPath = originalPath,
        trashPath = "/data/user/0/com.cleansweep.app/files/trash/${originalPath.substringAfterLast('/')}",
        sizeBytes = sizeBytes,
        deletedAt = deletedAt,
        expiresAt = TrashPolicy.expiresAt(deletedAt),
    )

    @Test
    fun `recording an entry returns it with its id and never mutates the caller's value`() = runTest {
        val recorded = repository.record(entry("/r/a.bin"))

        assertTrue(recorded.id > 0L)
        assertEquals(entry("/r/a.bin"), recorded.copy(id = 0L))
        assertEquals(recorded, repository.getById(recorded.id))
        assertEquals(recorded, repository.getByOriginalPath("/r/a.bin"))
    }

    @Test
    fun `an entry that already carries an id is rejected so ids stay owned by the database`() {
        assertThrows(IllegalArgumentException::class.java) {
            runTest { repository.record(entry("/r/a.bin").copy(id = 42L)) }
        }
    }

    @Test
    fun `entries are listed newest first with a running total`() = runTest {
        val older = repository.record(entry("/r/older.bin", deletedAt = 1_000L, sizeBytes = 100L))
        repository.record(entry("/r/newer.bin", deletedAt = 2_000L, sizeBytes = 250L))

        assertEquals(listOf("/r/newer.bin", "/r/older.bin"), repository.observeEntries().first().map { it.originalPath })
        assertEquals(2, repository.observeEntryCount().first())
        assertEquals(350L, repository.observeTotalBytes().first())
        assertNull(repository.getByOriginalPath("/r/missing.bin"))
    }

    @Test
    fun `only entries past their expiry are returned for the purge`() = runTest {
        val day: Long = 24L * 60 * 60 * 1000
        val old = repository.record(entry("/r/old.bin", deletedAt = 0L))
        val recent = repository.record(entry("/r/recent.bin", deletedAt = 40 * day))

        val expired = repository.getExpired(now = 45 * day)

        assertEquals(listOf(old), expired)
        assertEquals(30 * day, old.expiresAt)
        assertEquals(recent, repository.getById(recent.id))
    }

    @Test
    fun `an empty trash reports zero bytes rather than null`() = runTest {
        assertEquals(0L, repository.observeTotalBytes().first())
        assertEquals(0, repository.observeEntryCount().first())
        assertTrue(repository.getExpired(now = Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `removing entries handles more ids than SQLite allows parameters`() = runTest {
        val recorded = (1..1_200).map { repository.record(entry("/r/file-$it.bin")) }

        repository.removeByIds(recorded.map { it.id })

        assertEquals(0, repository.observeEntryCount().first())
        repository.removeByIds(emptyList())
    }

    @Test
    fun `clearing the manifest empties it`() = runTest {
        repository.record(entry("/r/a.bin"))
        repository.record(entry("/r/b.bin"))

        repository.clearManifest()

        assertEquals(0, repository.observeEntryCount().first())
        assertEquals(0L, repository.observeTotalBytes().first())
    }
}
