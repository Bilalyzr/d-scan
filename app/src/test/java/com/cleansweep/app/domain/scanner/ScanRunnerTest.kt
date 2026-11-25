package com.cleansweep.app.domain.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.domain.model.WhitelistRuleType
import com.cleansweep.app.domain.scanner.hashing.DeviceLoad
import com.cleansweep.app.domain.scanner.hashing.ThermalStatus
import com.cleansweep.app.domain.util.StoragePaths
import com.cleansweep.app.file
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
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
 * Whole scans over real files in a temporary folder, through the real walker, hasher and an in-memory database.
 * What the scanner was asked to read is taken from [CountingHasher], which is how the stage gating is checked.
 */
@RunWith(AndroidJUnit4::class)
class ScanRunnerTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var harness: ScanHarness

    @Before
    fun setUp() {
        harness = ScanHarness(temporaryFolder.root)
    }

    @After
    fun tearDown() {
        harness.close()
    }

    private fun at(relative: String): String = harness.at(relative)

    private fun assertSuggestion(
        suggestion: Suggestion,
        category: SuggestionCategory,
        score: Int,
        reasons: List<ReasonCode>,
    ) {
        assertEquals(category, suggestion.category)
        assertEquals(score, suggestion.confidenceScore)
        assertEquals(reasons, suggestion.reasons)
        assertEquals(SuggestionTier.fromScore(score), suggestion.tier)
    }

    // ---- The 3-stage pipeline, end to end ----

    @Test
    fun `a first scan indexes every file, groups exact duplicates and suggests only the redundant copies`() =
        runBlocking {
            harness.write("Documents/report.pdf", 50_000, seed = 1)
            harness.write("Download/report (1).pdf", 50_000, seed = 1)
            harness.write("Download/other.pdf", 50_000, seed = 2)

            val summary = harness.scan()

            assertEquals(3L, summary.filesIndexed)
            assertEquals(150_000L, summary.bytesScanned)
            assertEquals(listOf(harness.root), summary.rootsScanned)
            assertEquals(0, summary.errors)
            assertEquals(1, summary.duplicateGroups)
            assertEquals(50_000L, summary.duplicateBytes)

            // Stage gating, read off what the hasher was asked to do: every same-sized file at its edges, and
            // only the pair whose edges match all the way through.
            assertEquals(
                setOf(at("Documents/report.pdf"), at("Download/report (1).pdf"), at("Download/other.pdf")),
                harness.hasher.partiallyHashed.toSet(),
            )
            assertEquals(
                setOf(at("Documents/report.pdf"), at("Download/report (1).pdf")),
                harness.hasher.fullyHashed.toSet(),
            )

            val stored = harness.storedSuggestions()
            assertEquals(listOf(at("Download/report (1).pdf")), stored.paths())
            assertSuggestion(
                stored.single(),
                SuggestionCategory.EXACT_DUPLICATE,
                score = 100,
                reasons = listOf(ReasonCode.EXACT_COPY, ReasonCode.IN_DOWNLOADS, ReasonCode.STALE_90_DAYS),
            )
        }

    @Test
    fun `a second scan hashes nothing again, and a changed file is rechecked from its edges`() = runBlocking {
        harness.write("Documents/report.pdf", 50_000, seed = 1)
        harness.write("Download/report (1).pdf", 50_000, seed = 1)
        harness.scan()
        harness.hasher.partiallyHashed.clear()
        harness.hasher.fullyHashed.clear()

        harness.scan()

        assertTrue(harness.hasher.partiallyHashed.isEmpty())
        assertTrue(harness.hasher.fullyHashed.isEmpty())
        assertEquals(1, harness.storedSuggestions().size)

        // Same size as before, other content, another modification time.
        harness.write("Download/report (1).pdf", 50_000, seed = 3, modifiedDaysAgo = 100)

        harness.scan()

        assertEquals(listOf(at("Download/report (1).pdf")), harness.hasher.partiallyHashed)
        assertTrue("the edges differ, so the whole file is never read", harness.hasher.fullyHashed.isEmpty())
        assertTrue(harness.storedSuggestions().isEmpty())
    }

    @Test
    fun `copies under one kilobyte are not looked for`() = runBlocking {
        harness.write("Documents/tiny.txt", 1_023, seed = 1)
        harness.write("Download/tiny.txt", 1_023, seed = 1)

        harness.scan()

        assertTrue(harness.hasher.partiallyHashed.isEmpty())
        assertTrue(harness.storedSuggestions().isEmpty())
    }

    // ---- Original Lock ----

    @Test
    fun `every duplicate group keeps one copy out of the suggestions, and a copy the user locked is always kept`() =
        runBlocking {
            harness.write("Documents/report.pdf", 50_000, seed = 1, modifiedDaysAgo = 400)
            harness.write("Download/report.pdf", 50_000, seed = 1, modifiedDaysAgo = 300)
            harness.write("Backup/report.pdf", 50_000, seed = 1, modifiedDaysAgo = 200)

            harness.scan()

            assertEquals(setOf(at("Download/report.pdf"), at("Backup/report.pdf")), harness.storedSuggestions().paths().toSet())

            val locked = requireNotNull(harness.indexed(at("Download/report.pdf")))
            harness.fileIndex.setOriginalLocked(locked.id, true)

            harness.scan()

            // The locked copy is now the Original. The copy that was the Original by default is redundant
            // like any other, and nothing locked is ever offered.
            val stored = harness.storedSuggestions()
            assertEquals(setOf(at("Documents/report.pdf"), at("Backup/report.pdf")), stored.paths().toSet())
            assertTrue(stored.none { it.file.isOriginalLocked })
            // Locking changes which copy is kept, not how many copies there are.
            assertEquals(3, harness.fileIndex.getDuplicateGroups().single().copies.size)
        }

    // ---- Junk ----

    @Test
    fun `junk is found, scored and explained, and hidden entries are left alone`() = runBlocking {
        harness.installedApks.install(at("Download/app.apk"))
        // A large file is one of 1 KB here. Every other file stays below that, so that no item picks up a
        // second finding that would change its score.
        harness.settings.update { it.copy(largeFileThresholdBytes = 1_000L) }
        harness.write("Download/app.apk", 500, seed = 10)
        harness.write("Download/old.xapk", 500, seed = 11, modifiedDaysAgo = 30)
        harness.write("Download/new.apk", 500, seed = 16, modifiedDaysAgo = 1)
        harness.write("Download/notes.log", 100, seed = 12)
        harness.write("Documents/stray.tmp", 100, seed = 13, modifiedDaysAgo = 1)
        harness.write("Documents/empty.dat", 0, seed = 0)
        harness.write("Documents/big.bin", 2_000, seed = 14)
        harness.write("Documents/.nomedia", 0, seed = 0)
        harness.write("DCIM/.thumbnails/thumb1", 500, seed = 15)

        val summary = harness.scan()

        val byPath = harness.storedSuggestions().associateBy { it.file.path }
        assertEquals(
            setOf(
                at("Download/app.apk"),
                at("Download/old.xapk"),
                at("Download/notes.log"),
                at("Documents/stray.tmp"),
                at("Documents/empty.dat"),
                at("Documents/big.bin"),
                at("DCIM/.thumbnails/thumb1"),
            ),
            byPath.keys,
        )
        assertSuggestion(
            byPath.getValue(at("Download/app.apk")),
            SuggestionCategory.OBSOLETE_INSTALLER,
            score = 100,
            reasons = listOf(ReasonCode.INSTALLED_APK, ReasonCode.IN_DOWNLOADS, ReasonCode.STALE_90_DAYS),
        )
        assertSuggestion(
            byPath.getValue(at("Download/old.xapk")),
            SuggestionCategory.OBSOLETE_INSTALLER,
            score = 70,
            reasons = listOf(ReasonCode.IN_DOWNLOADS, ReasonCode.OLD_INSTALLER),
        )
        assertSuggestion(
            byPath.getValue(at("Download/notes.log")),
            SuggestionCategory.TEMP_FILE,
            score = 100,
            reasons = listOf(ReasonCode.TEMP_OR_LOG, ReasonCode.IN_DOWNLOADS, ReasonCode.STALE_90_DAYS),
        )
        assertSuggestion(
            byPath.getValue(at("Documents/stray.tmp")),
            SuggestionCategory.TEMP_FILE,
            score = 60,
            reasons = listOf(ReasonCode.TEMP_OR_LOG, ReasonCode.RECENT),
        )
        assertSuggestion(
            byPath.getValue(at("Documents/empty.dat")),
            SuggestionCategory.ZERO_BYTE,
            score = 95,
            reasons = listOf(ReasonCode.ZERO_BYTE, ReasonCode.STALE_90_DAYS),
        )
        assertSuggestion(
            byPath.getValue(at("Documents/big.bin")),
            SuggestionCategory.LARGE_FORGOTTEN,
            score = 65,
            reasons = listOf(ReasonCode.LARGE_FILE, ReasonCode.STALE_90_DAYS),
        )
        assertSuggestion(
            byPath.getValue(at("DCIM/.thumbnails/thumb1")),
            SuggestionCategory.TEMP_FILE,
            score = 95,
            reasons = listOf(ReasonCode.THUMBNAIL_CACHE, ReasonCode.STALE_90_DAYS),
        )

        // Only plain APK files are read for the app inside them.
        assertEquals(setOf(at("Download/app.apk"), at("Download/new.apk")), harness.installedApks.askedAbout.toSet())
        assertEquals(
            mapOf(
                SuggestionCategory.OBSOLETE_INSTALLER to 2,
                SuggestionCategory.TEMP_FILE to 3,
                SuggestionCategory.ZERO_BYTE to 1,
                SuggestionCategory.LARGE_FORGOTTEN to 1,
            ),
            summary.suggestions,
        )
        assertEquals(9L, summary.filesIndexed)
    }

    @Test
    fun `an empty folder tree becomes one suggestion at its top, and goes away once a file is in it`() = runBlocking {
        harness.folder("Download/MyStuff/Inner")
        harness.ageFolder("Download/MyStuff/Inner")
        harness.ageFolder("Download/MyStuff")
        harness.folder("DCIM") // a folder the system uses: empty, but not the user's to remove

        val summary = harness.scan()

        assertEquals(1, summary.emptyFolders)
        assertTrue(requireNotNull(harness.indexed(at("Download/MyStuff"))).isDirectory)
        assertNull(harness.indexed(at("Download/MyStuff/Inner")))
        val suggestion = harness.storedSuggestions().single()
        assertEquals(at("Download/MyStuff"), suggestion.file.path)
        assertSuggestion(suggestion, SuggestionCategory.EMPTY_FOLDER, 100, listOf(ReasonCode.EMPTY_FOLDER, ReasonCode.IN_DOWNLOADS, ReasonCode.STALE_90_DAYS))

        harness.write("Download/MyStuff/Inner/letter.pdf", 10, seed = 20)

        harness.scan()

        assertNull(harness.indexed(at("Download/MyStuff")))
        assertTrue(harness.storedSuggestions().isEmpty())
    }

    // ---- What is allowed to reach a suggestion ----

    @Test
    fun `a whitelisted folder is neither indexed nor suggested`() = runBlocking {
        harness.write("Protected/keep.log", 100, seed = 1)
        harness.write("Download/notes.log", 100, seed = 2)
        harness.whitelist.addRule(at("Protected"), WhitelistRuleType.FOLDER)

        harness.scan()

        assertNull(harness.indexed(at("Protected/keep.log")))
        assertEquals(listOf(at("Download/notes.log")), harness.storedSuggestions().paths())
    }

    @Test
    fun `files under the minimum size setting are not indexed`() = runBlocking {
        harness.settings.update { it.copy(minFileSizeBytes = 1_000L) }
        harness.write("Download/big.log", 5_000, seed = 1)
        harness.write("Download/small.log", 10, seed = 2)

        val summary = harness.scan()

        assertNull(harness.indexed(at("Download/small.log")))
        assertNotNull(harness.indexed(at("Download/big.log")))
        assertEquals(1L, summary.filesIndexed)
        // Every file was seen, indexed or not.
        assertEquals(5_010L, summary.bytesScanned)
    }

    @Test
    fun `a file deleted between the walk and the hashing is dropped from the index`() = runBlocking {
        harness.write("Documents/report.pdf", 50_000, seed = 1)
        val copy = harness.write("Download/report.pdf", 50_000, seed = 1)
        harness.hasher.beforeHashing = { file ->
            if (file.parentFile?.name == "Download") copy.delete()
        }

        val summary = harness.scan()

        assertNull(harness.indexed(at("Download/report.pdf")))
        assertNotNull(harness.indexed(at("Documents/report.pdf")))
        assertTrue(harness.storedSuggestions().isEmpty())
        assertEquals(0, summary.duplicateGroups)
    }

    // ---- Scopes ----

    @Test
    fun `a volume the scan cannot read keeps its index and is counted as an error`() = runBlocking {
        // A volume is never inside the volume being scanned, so this one sits next to the scanned folder.
        val ejected = StoragePaths.canonicalize(
            File(temporaryFolder.root.parentFile, "cleansweep-ejected-${System.nanoTime()}").absolutePath,
        )
        harness.fileIndex.applyScanBatch(listOf(file("$ejected/DCIM/photo.jpg")), scanTimestamp = 1L)
        harness.roots = listOf(ejected, harness.root)
        harness.write("Download/notes.log", 100, seed = 1)

        val summary = harness.scan()

        assertEquals(listOf(harness.root), summary.rootsScanned)
        assertEquals(1, summary.errors)
        assertNotNull(harness.indexed("$ejected/DCIM/photo.jpg"))
    }

    @Test
    fun `a scan limited to one category leaves the other categories alone`() = runBlocking {
        val photo = harness.write("DCIM/photo.jpg", 10_000, seed = 1)
        harness.write("Documents/notes.pdf", 10_000, seed = 2)
        harness.scan()
        assertTrue(photo.delete())

        harness.scan(ScanRequest(categories = setOf(FileCategory.DOCUMENT)))

        assertNotNull(harness.indexed(at("DCIM/photo.jpg")))
        assertNotNull(harness.indexed(at("Documents/notes.pdf")))
    }

    @Test
    fun `a hot device with a low battery still scans, with fewer hashing workers`() = runBlocking {
        harness.deviceLoad = DeviceLoad(2, 5, ThermalStatus.SEVERE, true)
        harness.write("Documents/report.pdf", 20_000, seed = 1)
        harness.write("Backup/report.pdf", 20_000, seed = 1)

        val summary = harness.scan()

        assertEquals(1, summary.duplicateGroups)
    }

    // ---- Pause and cancel ----

    @Test
    fun `a paused scan holds where it is and carries on when the gate opens`() = runBlocking {
        val gate = PauseGate(initiallyOpen = false)
        harness.write("Download/notes.log", 100, seed = 1)
        val job = launch(Dispatchers.Default) {
            harness.runner.run(ScanRequest(), gate, ScanProgressListener { })
        }
        delay(200)
        assertNull(harness.indexed(at("Download/notes.log")))

        gate.open()
        withTimeout(20_000) { job.join() }

        assertNotNull(harness.indexed(at("Download/notes.log")))
        assertEquals(1, harness.storedSuggestions().size)
    }

    @Test
    fun `cancelling the scan stops it where it is`() = runBlocking {
        harness.write("Documents/report.pdf", 50_000, seed = 1)
        harness.write("Download/report.pdf", 50_000, seed = 1)
        val hashingStarted = CompletableDeferred<Unit>()
        harness.hasher.beforeHashing = {
            hashingStarted.complete(Unit)
            awaitCancellation()
        }
        val job = launch(Dispatchers.Default) { harness.scan() }
        withTimeout(10_000) { hashingStarted.await() }

        withTimeout(10_000) { job.cancelAndJoin() }

        assertTrue(job.isCancelled)
        assertFalse(job.isActive)
        assertTrue(harness.storedSuggestions().isEmpty())
    }
}
