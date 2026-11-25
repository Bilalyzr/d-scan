package com.cleansweep.app.ui.home

import com.cleansweep.app.domain.model.CategoryTotal
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.domain.model.TierTotal
import com.cleansweep.app.domain.model.TrashEntry
import com.cleansweep.app.domain.scanner.ScanFailure
import com.cleansweep.app.domain.scanner.ScanProgress
import com.cleansweep.app.domain.scanner.ScanState
import com.cleansweep.app.domain.scanner.StorageAccess
import com.cleansweep.app.domain.scanner.StorageSpaceReader
import com.cleansweep.app.domain.scanner.VolumeSpace
import com.cleansweep.app.file
import com.cleansweep.app.ui.FakeFileIndexRepository
import com.cleansweep.app.ui.FakeScanControl
import com.cleansweep.app.ui.FakeSuggestionRepository
import com.cleansweep.app.ui.FakeTrashRepository
import com.cleansweep.app.ui.MainDispatcherRule
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** What the dashboard works out from the index, the suggestions and the storage. */
class HomeViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val index = FakeFileIndexRepository()
    private val suggestions = FakeSuggestionRepository()
    private val trash = FakeTrashRepository()
    private val scans = FakeScanControl()
    private val access = FakeAccess(granted = true)
    private val space = FakeSpace()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC)

    private class FakeAccess(
        var granted: Boolean,
    ) : StorageAccess {
        override fun canReadStorage(): Boolean = granted
    }

    /** Answers with fixed volumes, counts its calls, and can be made to fail. */
    private class FakeSpace : StorageSpaceReader {
        var volumes = listOf(VolumeSpace("/storage/emulated/0", 100_000L, 40_000L))
        var calls = 0
        var failure: Exception? = null

        override fun volumes(): List<VolumeSpace> {
            calls++
            failure?.let { throw it }
            return volumes
        }
    }

    /** Reads the first state that satisfies [predicate]. The ViewModel's work runs at once on the test dispatcher. */
    private fun awaitState(
        predicate: (HomeUiState) -> Boolean,
    ): HomeUiState = viewModel().uiState.await(predicate)

    private fun StateFlow<HomeUiState>.await(
        predicate: (HomeUiState) -> Boolean,
    ): HomeUiState = runBlocking { first { predicate(it) } }

    private fun viewModel() = HomeViewModel(
        fileIndex = index,
        suggestions = suggestions,
        trash = trash,
        scans = scans,
        storageAccess = access,
        storageSpace = space,
        clock = clock,
        ioDispatcher = mainDispatcher.dispatcher,
    )

    private fun suggestion(
        fileId: Long,
        category: SuggestionCategory,
        score: Int,
        size: Long,
    ): Suggestion {
        val reason = when (category) {
            SuggestionCategory.EXACT_DUPLICATE -> ReasonCode.EXACT_COPY
            SuggestionCategory.TEMP_FILE -> ReasonCode.TEMP_OR_LOG
            else -> ReasonCode.LARGE_FILE
        }
        return Suggestion(
            fileId = fileId,
            category = category,
            confidenceScore = score,
            primaryReason = reason,
            reasons = listOf(reason),
            isDismissed = false,
            file = file("/p/$fileId", sizeBytes = size),
        )
    }

    @Test
    fun `the gauge is every volume added up`() {
        space.volumes = listOf(
            VolumeSpace("/storage/emulated/0", 100_000L, 40_000L),
            VolumeSpace("/storage/1234-5678", 32_000L, 20_000L),
        )

        val state = awaitState { it.hasStorageSizes }

        assertEquals(132_000L, state.totalBytes)
        assertEquals(60_000L, state.freeBytes)
        assertEquals(72_000L, state.usedBytes)
        assertEquals(72_000f / 132_000f, state.usedFraction, 0.0001f)
    }

    @Test
    fun `before the first scan the dashboard says so and offers nothing`() {
        val state = awaitState { it.hasStorageSizes }

        assertFalse(state.hasScanned)
        assertNull(state.lastScanAt)
        assertNull(state.safeToClean)
        assertNull(state.reviewRecommended)
        assertFalse(state.isScanning)
        assertEquals(ScanState.Idle, state.scan)
        assertEquals(1_800_000_000_000L, state.now)
    }

    @Test
    fun `what is indexed is broken down by kind of file, largest first`() {
        index.set(
            file("/p/a.jpg", sizeBytes = 300L, category = FileCategory.IMAGE),
            file("/p/b.jpg", sizeBytes = 100L, category = FileCategory.IMAGE),
            file("/p/c.mp4", sizeBytes = 900L, category = FileCategory.VIDEO),
        )
        index.lastScanAt = 1_700_000_000_000L

        val state = awaitState { it.hasScanned }

        assertEquals(
            listOf(
                CategoryTotal(FileCategory.VIDEO, 1, 900L),
                CategoryTotal(FileCategory.IMAGE, 2, 400L),
            ),
            state.categoryTotals,
        )
        assertEquals(3, state.indexedFiles)
        assertEquals(1_700_000_000_000L, state.lastScanAt)
    }

    @Test
    fun `what could be freed is each tier, and what the trash holds`() {
        suggestions.set(
            suggestion(1L, SuggestionCategory.EXACT_DUPLICATE, score = 100, size = 500L),
            suggestion(2L, SuggestionCategory.TEMP_FILE, score = 80, size = 100L),
            suggestion(3L, SuggestionCategory.LARGE_FORGOTTEN, score = 60, size = 700L),
        )
        trash.set(
            TrashEntry(
                originalPath = "/p/old.log",
                trashPath = "/p/.cleansweep-trashed-1-old.log",
                sizeBytes = 50L,
                deletedAt = 10L,
                expiresAt = 20L,
            ),
        )

        val state = awaitState { it.safeToClean != null }

        assertEquals(TierTotal(SuggestionTier.SAFE_TO_CLEAN, 2, 600L), state.safeToClean)
        assertEquals(TierTotal(SuggestionTier.REVIEW_RECOMMENDED, 1, 700L), state.reviewRecommended)
        assertEquals(1, state.trashEntries)
        assertEquals(50L, state.trashBytes)
    }

    @Test
    fun `a scan that is running shows on the dashboard, and so does how the last one ended`() {
        val viewModel = viewModel()

        scans.state.value = ScanState.Running(ScanProgress(filesScanned = 10L), isPaused = false)
        assertTrue(viewModel.uiState.await { it.isScanning }.isScanning)

        scans.state.value = ScanState.Failed(ScanFailure.NO_STORAGE_TO_SCAN)
        val failed = viewModel.uiState.await { it.scan is ScanState.Failed }

        assertFalse(failed.isScanning)
        assertEquals(ScanFailure.NO_STORAGE_TO_SCAN, (failed.scan as ScanState.Failed).failure)
    }

    @Test
    fun `storage is measured only once there is access to it, and granted access is noticed on return`() {
        access.granted = false
        val viewModel = viewModel()

        assertFalse(viewModel.uiState.await { !it.storageAccessGranted }.storageAccessGranted)
        assertEquals("Nothing can be measured without access", 0, space.calls)

        access.granted = true
        viewModel.onResume()

        assertTrue(viewModel.uiState.await { it.storageAccessGranted }.storageAccessGranted)
        assertEquals(1, space.calls)
    }

    @Test
    fun `access taken away in the system settings is noticed on return`() {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.await { it.hasStorageSizes }.storageAccessGranted)

        access.granted = false
        viewModel.onResume()

        assertFalse(viewModel.uiState.await { !it.storageAccessGranted }.storageAccessGranted)
    }

    @Test
    fun `a storage that cannot be measured leaves the dashboard standing`() {
        space.failure = IllegalStateException("volume went away")

        val state = awaitState { it.storageAccessGranted }.also {
            // The measurement runs on the same test dispatcher; give it a turn before judging.
            mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
        }

        assertFalse(state.hasStorageSizes)
        assertEquals(0L, state.totalBytes)
        assertEquals(0f, state.usedFraction, 0f)
    }

    @Test
    fun `the scan button starts a scan through the scan service`() {
        val viewModel = viewModel()

        assertTrue(viewModel.startScan())

        assertEquals(1, scans.starts)
        assertTrue(viewModel.uiState.await { it.isScanning }.isScanning)
    }
}
