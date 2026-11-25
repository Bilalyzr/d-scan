package com.cleansweep.app.ui.scan

import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.scanner.ScanFailure
import com.cleansweep.app.domain.scanner.ScanPhase
import com.cleansweep.app.domain.scanner.ScanProgress
import com.cleansweep.app.domain.scanner.ScanState
import com.cleansweep.app.domain.scanner.ScanSummary
import com.cleansweep.app.ui.FakeScanControl
import com.cleansweep.app.ui.MainDispatcherRule
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** How far a scan is shown to be, and that the buttons reach the scan service. */
class ScanViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val scans = FakeScanControl()

    private fun running(
        phase: ScanPhase,
        filesHashed: Int = 0,
        filesToHash: Int? = null,
    ) = ScanState.Running(
        ScanProgress(phase = phase, filesHashed = filesHashed, filesToHash = filesToHash),
        isPaused = false,
    )

    @Test
    fun `the ring shows how far a hashing stage is, and keeps turning when the stage has no size`() {
        assertNull(ScanViewModel.hashingFraction(running(ScanPhase.WALKING)))
        assertNull(ScanViewModel.hashingFraction(running(ScanPhase.ANALYSING)))
        assertNull(ScanViewModel.hashingFraction(running(ScanPhase.PARTIAL_HASHING, filesHashed = 10, filesToHash = null)))
        assertNull(ScanViewModel.hashingFraction(running(ScanPhase.FULL_HASHING, filesToHash = 0)))

        val quarter = ScanViewModel.hashingFraction(running(ScanPhase.PARTIAL_HASHING, filesHashed = 10, filesToHash = 40))
        assertEquals(0.25f, quarter!!, 0.0001f)
        val half = ScanViewModel.hashingFraction(running(ScanPhase.FULL_HASHING, filesHashed = 2, filesToHash = 4))
        assertEquals(0.5f, half!!, 0.0001f)
        // Never more than whole, even if a late snapshot counts one too many.
        val over = ScanViewModel.hashingFraction(running(ScanPhase.FULL_HASHING, filesHashed = 5, filesToHash = 4))
        assertEquals(1f, over!!, 0.0001f)
    }

    @Test
    fun `pause, resume and cancel reach the scan service`() {
        scans.state.value = running(ScanPhase.WALKING)
        val viewModel = ScanViewModel(scans)

        assertTrue(viewModel.pause())
        assertEquals(1, scans.pauses)
        assertTrue(viewModel.resume())
        assertEquals(1, scans.resumes)
        assertTrue(viewModel.cancel())
        assertTrue(scans.state.value is ScanState.Cancelled)
    }

    @Test
    fun `starting again asks the scan service for a new scan`() {
        scans.state.value = ScanState.Failed(ScanFailure.NO_STORAGE_TO_SCAN)
        val viewModel = ScanViewModel(scans)

        viewModel.startAgain()

        assertEquals(1, scans.starts)
        assertTrue(scans.state.value is ScanState.Running)
    }

    @Test
    fun `the screen hears of a scan that finishes, and of nothing else`() = runBlocking {
        scans.state.value = running(ScanPhase.WALKING)
        val viewModel = ScanViewModel(scans)

        val heard = async { viewModel.finished.first() }

        scans.state.value = ScanState.Failed(ScanFailure.NO_STORAGE_TO_SCAN)
        scans.state.value = ScanState.Cancelled(ScanProgress())
        assertEquals("Nothing is heard of a scan that failed or was cancelled", false, heard.isCompleted)

        scans.state.value = ScanState.Finished(
            ScanSummary(
                rootsScanned = listOf("/storage/emulated/0"),
                filesIndexed = 12L,
                bytesScanned = 3_400L,
                foldersScanned = 3L,
                emptyFolders = 0,
                duplicateGroups = 1,
                duplicateBytes = 400L,
                suggestions = mapOf(SuggestionCategory.EXACT_DUPLICATE to 1),
                errors = 0,
            ),
        )

        heard.await()
    }
}
