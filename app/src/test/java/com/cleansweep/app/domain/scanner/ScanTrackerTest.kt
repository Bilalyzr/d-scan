package com.cleansweep.app.domain.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTrackerTest {

    private val snapshots = ArrayList<ScanProgress>()
    private val tracker = ScanTracker(snapshots::add)

    @Test
    fun `every snapshot describes the whole scan, so a later stage never resets the counters of an earlier one`() {
        tracker.fileScanned(10)
        tracker.fileScanned(20)
        tracker.folderEntered("/r/Download")

        tracker.enterPhase(ScanPhase.PARTIAL_HASHING, filesToHash = 3)
        tracker.fileHashed()
        tracker.error()
        tracker.fileHashed()
        tracker.enterPhase(ScanPhase.ANALYSING)

        assertEquals(
            ScanProgress(
                phase = ScanPhase.ANALYSING,
                filesScanned = 2,
                bytesScanned = 30,
                foldersScanned = 1,
                currentFolder = "/r/Download",
                filesHashed = 0,
                filesToHash = null,
                errors = 1,
            ),
            snapshots.last(),
        )
    }

    @Test
    fun `each hashing stage counts from zero and says how many files it has ahead`() {
        tracker.enterPhase(ScanPhase.PARTIAL_HASHING, filesToHash = 3)
        tracker.fileHashed()
        tracker.fileHashed()

        assertEquals(ScanPhase.PARTIAL_HASHING, snapshots.last().phase)
        assertEquals(2, snapshots.last().filesHashed)
        assertEquals(3, snapshots.last().filesToHash)

        tracker.enterPhase(ScanPhase.FULL_HASHING, filesToHash = 1)

        assertEquals(ScanPhase.FULL_HASHING, snapshots.last().phase)
        assertEquals(0, snapshots.last().filesHashed)
        assertEquals(1, snapshots.last().filesToHash)
    }

    @Test
    fun `files are published in batches, folders, phases and hashes at once`() {
        repeat(ScanTracker.PUBLISH_EVERY_N_FILES.toInt() - 1) { tracker.fileScanned(1) }
        assertTrue(snapshots.isEmpty())

        tracker.fileScanned(1)
        assertEquals(1, snapshots.size)

        tracker.folderEntered("/r/a")
        tracker.enterPhase(ScanPhase.ANALYSING)
        tracker.fileHashed()
        assertEquals(4, snapshots.size)
    }

    @Test
    fun `an error is carried by the next snapshot rather than published on its own`() {
        tracker.error()

        assertTrue(snapshots.isEmpty())

        tracker.folderEntered("/r/a")
        assertEquals(1, snapshots.single().errors)
        assertNull(snapshots.single().filesToHash)
    }
}
