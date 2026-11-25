package com.cleansweep.app.ui.preview

import androidx.lifecycle.SavedStateHandle
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.scanner.FakeSettingsRepository
import com.cleansweep.app.domain.scanner.ScanProgress
import com.cleansweep.app.domain.scanner.ScanState
import com.cleansweep.app.domain.scanner.StorageAccess
import com.cleansweep.app.domain.scanner.SuggestionGuard
import com.cleansweep.app.domain.scanner.clean.CleanSelectionStore
import com.cleansweep.app.domain.scanner.duplicates.DuplicateGroupEditor
import com.cleansweep.app.domain.scanner.junk.InstalledApkOracle
import com.cleansweep.app.file
import com.cleansweep.app.ui.FakeFileIndexRepository
import com.cleansweep.app.ui.FakeScanControl
import com.cleansweep.app.ui.FakeSuggestionRepository
import com.cleansweep.app.ui.FakeWhitelistRepository
import com.cleansweep.app.ui.MainDispatcherRule
import com.cleansweep.app.ui.navigation.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The preview of one file: how it is shown, and the choice between keeping the copy and marking it. */
class PreviewViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    /** A test that waits for a state that never comes fails after a minute instead of hanging the whole run. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private class FakeAccess(
        var granted: Boolean = true,
    ) : StorageAccess {
        override fun canReadStorage(): Boolean = granted
    }

    private val index = FakeFileIndexRepository()
    private val suggestions = FakeSuggestionRepository(index)
    private val scans = FakeScanControl()
    private val access = FakeAccess()
    private val marked = CleanSelectionStore()

    private val editor = DuplicateGroupEditor(
        fileIndex = index,
        suggestions = suggestions,
        whitelist = FakeWhitelistRepository(index, suggestions),
        installedApks = InstalledApkOracle.NONE,
        guard = SuggestionGuard(),
        settings = FakeSettingsRepository(),
        clock = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC),
        ioDispatcher = mainDispatcher.dispatcher,
    )

    @Before
    fun scannedOnce() {
        index.lastScanAt = 1_000L
    }

    @After
    fun nothingLeftBehind() {
        index.set()
        suggestions.set()
    }

    private fun indexed(
        id: Long,
        name: String,
        content: String? = null,
        hash: String? = null,
        category: FileCategory = FileCategory.DOCUMENT,
        locked: Boolean = false,
    ): IndexedFile {
        val onDisk: File? = content?.let { File(temporaryFolder.root, name).apply { parentFile?.mkdirs(); writeText(it) } }
        val file = file(onDisk?.absolutePath ?: "/p/$name", sizeBytes = (content?.length ?: 10).toLong().coerceAtLeast(1L), category = category, fullHash = hash)
            .copy(id = id, isOriginalLocked = locked)
        index.set(*index.files.value.filterNot { it.id == id }.toTypedArray(), file)
        return file
    }

    private fun suggest(
        file: IndexedFile,
        score: Int = 100,
        category: SuggestionCategory = SuggestionCategory.EXACT_DUPLICATE,
    ): Suggestion {
        val reason = if (category == SuggestionCategory.EXACT_DUPLICATE) ReasonCode.EXACT_COPY else ReasonCode.TEMP_OR_LOG
        val suggestion = Suggestion(file.id, category, score, reason, listOf(reason), isDismissed = false, file = file)
        suggestions.add(suggestion)
        return suggestion
    }

    private fun makeViewModel(
        fileId: Long,
    ) = PreviewViewModel(
        fileIndex = index,
        suggestions = suggestions,
        scans = scans,
        storageAccess = access,
        selectionStore = marked,
        editor = editor,
        textReader = FileTextPreviewReader(),
        pdfPages = PdfPages(),
        screenWidth = ScreenWidth { 1080 },
        savedState = SavedStateHandle(mapOf(Routes.ARG_FILE_ID to fileId.toString())),
        ioDispatcher = mainDispatcher.dispatcher,
    )

    /** The first state that satisfies [predicate], giving the preview's own work a turn between looks. */
    private suspend fun StateFlow<PreviewUiState>.await(
        predicate: (PreviewUiState) -> Boolean,
    ): PreviewUiState = withTimeout(10_000L) {
        while (!predicate(value)) {
            mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
            delay(1)
        }
        value
    }

    private suspend fun awaitUntil(
        condition: () -> Boolean,
    ) {
        withTimeout(10_000L) {
            while (!condition()) {
                mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
                delay(1)
            }
        }
    }

    /** Runs [block] with the preview watched the way its screen watches it. */
    private fun withEvents(
        fileId: Long,
        block: suspend CoroutineScope.(events: MutableList<PreviewEvent>, viewModel: PreviewViewModel) -> Unit,
    ): Unit = runBlocking {
        val events = ArrayList<PreviewEvent>()
        val viewModel = makeViewModel(fileId)
        val watching = launch { viewModel.uiState.collect { } }
        val collector = launch { viewModel.eventFlow.collect { events += it } }
        try {
            mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
            block(events, viewModel)
        } finally {
            collector.cancelAndJoin()
            watching.cancelAndJoin()
        }
    }

    @Test
    fun `a text file is shown as text, read from the first bytes`() = withEvents(1L) { _, viewModel ->
        val file = indexed(1L, "notes.txt", content = "Quarterly report\nRevenue is up.")

        val state = viewModel.uiState.await { it.content == ContentState.READY }

        assertEquals(PreviewKind.TEXT, state.kind)
        assertEquals(file.name, state.file?.name)
        assertEquals("Quarterly report\nRevenue is up.", viewModel.textPreview.value)
    }

    @Test
    fun `a long text file is shown only up to the limit`() = withEvents(1L) { _, viewModel ->
        val long = "x".repeat((PreviewKinds.TEXT_READ_LIMIT_BYTES + 5_000L).toInt())
        indexed(1L, "long.txt", content = long)

        viewModel.uiState.await { it.content == ContentState.READY }

        assertEquals(PreviewKinds.TEXT_READ_LIMIT_BYTES.toInt(), viewModel.textPreview.value?.length)
    }

    @Test
    fun `a file that cannot be read is reported, and the preview stays standing`() = withEvents(1L) { _, viewModel ->
        indexed(1L, "missing.txt")

        val state = viewModel.uiState.await { it.content == ContentState.FAILED }

        assertEquals(ContentState.FAILED, state.content)
        assertNull(viewModel.textPreview.value)
    }

    @Test
    fun `a file that is not text, a picture, a film, music or a PDF has no preview, and needs nothing loaded`() =
        withEvents(1L) { _, viewModel ->
            indexed(1L, "table.xlsx")

            val state = viewModel.uiState.await { it.content == ContentState.READY }

            assertEquals(PreviewKind.NONE, state.kind)
        }

    @Test
    fun `a copy that is kept by the group is shown as kept and cannot be marked`() = withEvents(1L) { _, viewModel ->
        val kept = indexed(1L, "a.pdf", content = "same", hash = "h", locked = false)
        indexed(2L, "a (1).pdf", content = "same", hash = "h")
        suggest(kept)

        val state = viewModel.uiState.await { it.isDuplicate }

        assertTrue(state.isKept)
        assertTrue(state.isKeptEitherWay)
        assertFalse("The kept copy is never offered, whatever suggestion it has", state.isOffered)
        assertFalse(state.canMark)
        assertFalse(state.isKeptByUser)
    }

    @Test
    fun `a copy that is not kept can be marked for deletion, and unmarked`() = withEvents(1L) { _, viewModel ->
        // The copy outside Download is kept by the usual rule, so the one in Download is offered.
        indexed(2L, "z/b.pdf", content = "same", hash = "h")
        val offered = indexed(1L, "Download/b.pdf", content = "same", hash = "h")
        suggest(offered)
        viewModel.uiState.await { it.isDuplicate }

        viewModel.mark(true)
        val markedState = viewModel.uiState.await { it.isSelected }

        assertTrue(markedState.isOffered)
        assertTrue(markedState.canMark)

        viewModel.mark(false)

        assertFalse(viewModel.uiState.await { !it.isSelected }.isSelected)
    }

    @Test
    fun `keeping a copy that is not the kept one makes it the copy the group keeps`() = withEvents(1L) { events, viewModel ->
        indexed(2L, "z/b.pdf", content = "same", hash = "h")
        val offered = indexed(1L, "Download/b.pdf", content = "same", hash = "h")
        suggest(offered)
        viewModel.uiState.await { it.isDuplicate }

        viewModel.keep()
        awaitUntil { events.size == 1 }
        val state = viewModel.uiState.await { it.isKeptByUser && !it.busy }

        assertEquals(PreviewEvent.Kept, events.single())
        assertTrue(state.isKept)
        assertTrue(state.isKeptByUser)
        assertEquals(listOf(1L to true), index.lockChanges)
    }

    @Test
    fun `keeping a file that is not a duplicate takes its suggestion off the list`() = withEvents(1L) { events, viewModel ->
        val file = indexed(1L, "setup.log")
        suggest(file, category = SuggestionCategory.TEMP_FILE)
        viewModel.uiState.await { it.isOffered }

        viewModel.keep()
        awaitUntil { events.size == 1 }
        val kept = viewModel.uiState.await { it.isIgnored }

        assertEquals(PreviewEvent.Ignored(1L), events.single())
        assertFalse(kept.isDuplicate)
        assertTrue(kept.isIgnored)
        assertTrue(kept.isKeptEitherWay)
        assertFalse(kept.isOffered)

        viewModel.release()
        awaitUntil { events.size == 2 }

        assertEquals(PreviewEvent.Unignored(1L), events[1])
    }

    @Test
    fun `the copy kept by the group's rule cannot be given up`() = withEvents(1L) { events, viewModel ->
        indexed(1L, "a.pdf", content = "same", hash = "h")
        indexed(2L, "a (1).pdf", content = "same", hash = "h")
        viewModel.uiState.await { it.isDuplicate && it.isKept }

        viewModel.release()
        awaitUntil { events.size == 1 }

        assertEquals(PreviewEvent.KeptByRule, events.single())
        assertFalse(viewModel.uiState.value.isKeptByUser)
    }

    @Test
    fun `giving up a copy kept by the user returns the group to its rule`() = withEvents(1L) { events, viewModel ->
        indexed(2L, "z/b.pdf", content = "same", hash = "h")
        indexed(1L, "Download/b.pdf", content = "same", hash = "h")
        viewModel.uiState.await { it.isDuplicate }
        viewModel.keep()
        awaitUntil { events.size == 1 }
        viewModel.uiState.await { it.isKeptByUser && !it.busy }

        viewModel.release()
        awaitUntil { events.size == 2 }
        val state = viewModel.uiState.await { !it.isKeptByUser && !it.busy }

        assertEquals(PreviewEvent.Released, events[1])
        assertFalse(state.isKeptByUser)
    }

    @Test
    fun `nothing can be marked while a scan runs or without access to storage`() = withEvents(1L) { _, viewModel ->
        indexed(2L, "z/b.pdf", content = "same", hash = "h")
        val offered = indexed(1L, "Download/b.pdf", content = "same", hash = "h")
        suggest(offered)
        viewModel.uiState.await { it.isOffered }

        assertTrue("An exact copy is safe to clean, so it is ticked from the start", viewModel.uiState.value.isSelected)

        scans.state.value = ScanState.Running(ScanProgress(), isPaused = false)
        viewModel.uiState.await { it.isScanning }
        viewModel.mark(false)
        mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
        delay(5)
        assertTrue("Nothing changes while a scan runs", viewModel.uiState.value.isSelected)

        scans.state.value = ScanState.Idle
        access.granted = false
        viewModel.onResume()
        assertFalse(viewModel.uiState.await { !it.storageAccessGranted }.canMark)
    }

    @Test
    fun `a file that is not in the index any more is said to be gone`() = withEvents(99L) { _, viewModel ->
        val state = viewModel.uiState.await { !it.loading }

        assertTrue(state.isGone)
        assertNull(state.file)
    }
}
