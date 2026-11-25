package com.cleansweep.app.ui.suggestions

import androidx.lifecycle.SavedStateHandle
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.scanner.FakeSettingsRepository
import com.cleansweep.app.domain.scanner.ScanProgress
import com.cleansweep.app.domain.scanner.ScanState
import com.cleansweep.app.domain.scanner.StorageAccess
import com.cleansweep.app.domain.scanner.StorageRootsProvider
import com.cleansweep.app.domain.scanner.SuggestionGuard
import com.cleansweep.app.domain.scanner.clean.CleanSelectionStore
import com.cleansweep.app.domain.scanner.duplicates.AutoSelectRule
import com.cleansweep.app.domain.scanner.duplicates.DuplicateGroupEditor
import com.cleansweep.app.domain.scanner.junk.InstalledApkOracle
import com.cleansweep.app.domain.scanner.trash.Refusal
import com.cleansweep.app.domain.scanner.trash.TrashRefusal
import com.cleansweep.app.domain.scanner.walker.FileMetadata
import com.cleansweep.app.domain.scanner.walker.FileMetadataReader
import com.cleansweep.app.domain.scanner.whitelist.WhitelistManager
import com.cleansweep.app.domain.util.StoragePaths
import com.cleansweep.app.file
import com.cleansweep.app.ui.FakeFileIndexRepository
import com.cleansweep.app.ui.FakeScanControl
import com.cleansweep.app.ui.FakeSafeTrash
import com.cleansweep.app.ui.FakeSuggestionRepository
import com.cleansweep.app.ui.FakeTrashRepository
import com.cleansweep.app.ui.FakeWhitelistRepository
import com.cleansweep.app.ui.MainDispatcherRule
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** What the Suggestions Hub shows, marks, and moves to Safe Trash. */
class SuggestionsViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    /** A test that waits for a state that never comes fails after a minute instead of hanging the whole run. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private class FakeAccess(
        var granted: Boolean,
    ) : StorageAccess {
        override fun canReadStorage(): Boolean = granted
    }

    private val index = FakeFileIndexRepository()
    private val suggestions = FakeSuggestionRepository(index)
    private val manifest = FakeTrashRepository()
    private val trash = FakeSafeTrash(index, suggestions, manifest)
    private val rules = FakeWhitelistRepository(index, suggestions)
    private val scans = FakeScanControl()
    private val access = FakeAccess(granted = true)
    private val marked = CleanSelectionStore()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC)

    /** The paths the whitelist manager finds on "storage". */
    private val onStorage = HashSet<String>()

    private val editor = DuplicateGroupEditor(
        fileIndex = index,
        suggestions = suggestions,
        whitelist = rules,
        installedApks = InstalledApkOracle.NONE,
        guard = SuggestionGuard(),
        settings = FakeSettingsRepository(),
        clock = clock,
        ioDispatcher = mainDispatcher.dispatcher,
    )

    private val whitelist = WhitelistManager(
        whitelist = rules,
        roots = StorageRootsProvider { listOf("/p") },
        metadata = FileMetadataReader { path ->
            path.takeIf { it in onStorage }?.let {
                FileMetadata(
                    isDirectory = false,
                    isRegularFile = true,
                    isSymbolicLink = false,
                    sizeBytes = 1L,
                    lastModified = 1L,
                    createdAt = null,
                )
            }
        },
    )

    private fun makeViewModel(
        vararg route: Pair<String, String>,
    ) = SuggestionsViewModel(
        fileIndex = index,
        suggestions = suggestions,
        scans = scans,
        storageAccess = access,
        selectionStore = marked,
        editor = editor,
        whitelist = whitelist,
        trash = trash,
        savedState = SavedStateHandle(route.toMap()),
        ioDispatcher = mainDispatcher.dispatcher,
    )

    @Before
    fun scannedOnce() {
        index.lastScanAt = 1_000L
    }

    @After
    fun nothingLeftBehind() {
        onStorage.clear()
    }

    /** Adds an indexed file and a suggestion for it, as a scan would have. */
    private fun suggest(
        id: Long,
        score: Int = 100,
        category: SuggestionCategory = SuggestionCategory.EXACT_DUPLICATE,
        size: Long = 100L,
        path: String = "/p/file$id",
        lastModified: Long = 1_000L,
        hash: String? = null,
        kind: FileCategory = FileCategory.OTHER,
    ): Suggestion {
        val reason = when (category) {
            SuggestionCategory.EXACT_DUPLICATE -> ReasonCode.EXACT_COPY
            SuggestionCategory.TEMP_FILE -> ReasonCode.TEMP_OR_LOG
            SuggestionCategory.LARGE_FORGOTTEN -> ReasonCode.LARGE_FILE
            else -> ReasonCode.ZERO_BYTE
        }
        val file = file(path, sizeBytes = size, lastModified = lastModified, category = kind, fullHash = hash).copy(id = id)
        index.set(*index.files.value.filterNot { it.id == id }.toTypedArray(), file)
        val suggestion = Suggestion(id, category, score, reason, listOf(reason), isDismissed = false, file = file)
        suggestions.add(suggestion)
        return suggestion
    }

    /**
     * Three identical files: the oldest in Download, the newest in Documents. The usual rule keeps the copy in
     * Backup, the oldest of those outside Download, so Download and Backup are offered.
     */
    private fun groupOfThree() {
        suggest(1L, hash = "h", path = "/p/Download/a.pdf", lastModified = 100L)
        suggest(2L, hash = "h", path = "/p/Documents/a.pdf", lastModified = 300L)
        suggest(3L, hash = "h", path = "/p/Backup/a.pdf", lastModified = 200L)
        suggestions.set(*suggestions.items.value.filterNot { it.fileId == 2L }.toTypedArray())
    }

    /**
     * The first state that satisfies [predicate], giving the hub's own work a turn between looks. Gives up after
     * ten seconds, so that a state that never comes is reported and not waited for forever.
     *
     * Looked at by polling, and not by collecting: the hub shares its state only while someone collects it, and
     * stopping and starting that sharing is itself work on the test scheduler, which nothing would run while a
     * collector waited for it.
     */
    private suspend fun StateFlow<HubUiState>.await(
        predicate: (HubUiState) -> Boolean,
    ): HubUiState = withTimeout(10_000L) {
        while (!predicate(value)) {
            mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
            delay(1)
        }
        value
    }

    /** Lets the hub's own work run, then reads its state as the screen would see it. */
    private suspend fun settled(
        viewModel: SuggestionsViewModel,
    ): HubUiState {
        repeat(3) {
            mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
            delay(1)
        }
        return viewModel.uiState.value
    }

    /**
     * Gives the other coroutines of the test a turn, until [condition] holds.
     *
     * The pause between turns is a `delay`, not a `yield`: a coroutine that only yields keeps this thread's queue
     * busy, and the timeout that should end the wait never gets its turn.
     */
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

    /**
     * Runs [block] with the hub watched the way its screen watches it: something collects its state, so that the
     * state is kept up to date, and everything it reports is collected into a list.
     */
    private fun withEvents(
        vararg route: Pair<String, String>,
        block: suspend CoroutineScope.(events: MutableList<HubEvent>, viewModel: SuggestionsViewModel) -> Unit,
    ): Unit = runBlocking {
        val events = ArrayList<HubEvent>()
        val viewModel = makeViewModel(*route)
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

    // ---- What is shown ----

    @Test
    fun `safe items are marked from the start, and items that want a review are not`() = withEvents { _, viewModel ->
        suggest(1L, score = 100)
        suggest(2L, score = 90)
        suggest(3L, score = 60, category = SuggestionCategory.LARGE_FORGOTTEN, size = 700L)

        val state = viewModel.uiState.await { it.items.size == 2 }

        assertEquals(HubTab.SAFE_TO_CLEAN, state.tab)
        assertEquals(listOf(1L, 2L), state.items.map { it.fileId })
        assertEquals(setOf(1L, 2L), state.selected.map { it.fileId }.toSet())
        assertEquals(200L, state.summary.bytes)
        assertEquals(2, state.summary.safeCount)
        assertEquals(0, state.summary.reviewCount)
        assertEquals(2, state.safe?.itemCount)
        assertEquals(1, state.review?.itemCount)
        assertTrue(state.hasScanned)
        assertTrue(state.canClean)
    }

    @Test
    fun `the review tab lists what wants a review, and ticking one adds it to what will be moved`() = withEvents { _, viewModel ->
        val large = suggest(3L, score = 60, category = SuggestionCategory.LARGE_FORGOTTEN, size = 700L)

        assertTrue("Nothing that wants a review is on the safe tab", viewModel.uiState.await { it.hasScanned }.items.isEmpty())
        viewModel.selectTab(HubTab.REVIEW_RECOMMENDED)
        val reviewTab = viewModel.uiState.await { it.tab == HubTab.REVIEW_RECOMMENDED && it.items.size == 1 }

        assertEquals(listOf(3L), reviewTab.items.map { it.fileId })
        assertTrue("Nothing is marked on this tab by default", reviewTab.selected.isEmpty())
        assertFalse(reviewTab.canClean)

        viewModel.setSelected(large, true)

        val after = viewModel.uiState.await { it.selected.isNotEmpty() }
        assertEquals(listOf(3L), after.selected.map { it.fileId })
        assertEquals(1, after.summary.reviewCount)
        assertTrue(after.summary.needsReview)
    }

    @Test
    fun `the duplicates tab lists every group with the copy it keeps`() = withEvents { _, viewModel ->
        index.set(
            file("/p/Download/a.pdf", sizeBytes = 100L, lastModified = 100L, fullHash = "h").copy(id = 1L),
            file("/p/Documents/a.pdf", sizeBytes = 100L, lastModified = 300L, fullHash = "h").copy(id = 2L),
        )
        viewModel.uiState.await { it.groups.size == 1 }
        viewModel.selectTab(HubTab.ALL_DUPLICATES)

        val state = viewModel.uiState.await { it.tab == HubTab.ALL_DUPLICATES }

        val row = state.groups.single()
        assertEquals("The copy outside Download is the one kept", 2L, row.original.id)
        assertEquals("a.pdf", row.name)
        assertEquals(100L, row.reclaimableBytes)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `a filter from the route narrows the list down, and can be cleared`() =
        withEvents("category" to "TEMP_FILE") { _, viewModel ->
            suggest(1L, score = 100, category = SuggestionCategory.TEMP_FILE)
            suggest(2L, score = 90, kind = FileCategory.VIDEO, path = "/p/film.mp4")

            val filtered = viewModel.uiState.await { it.items.size == 1 && it.filter.category == SuggestionCategory.TEMP_FILE }

            assertEquals(listOf(1L), filtered.items.map { it.fileId })

            viewModel.clearFilter()

            val everything = viewModel.uiState.await { it.filter.isEmpty && it.items.size == 2 }
            assertTrue(everything.filter.isEmpty)
        }

    @Test
    fun `a kind of file from the route narrows every tab down`() =
        withEvents("type" to "VIDEO") { _, viewModel ->
            suggest(2L, score = 90, kind = FileCategory.VIDEO, path = "/p/film.mp4")
            suggest(3L, score = 60, category = SuggestionCategory.LARGE_FORGOTTEN, kind = FileCategory.VIDEO, path = "/p/big.mp4")
            suggest(4L, score = 100, path = "/p/file4")

            val safeVideos = viewModel.uiState.await { it.items.size == 1 && it.filter.type == FileCategory.VIDEO }
            assertEquals(listOf(2L), safeVideos.items.map { it.fileId })

            viewModel.selectTab(HubTab.REVIEW_RECOMMENDED)
            val reviewVideos = viewModel.uiState.await { it.tab == HubTab.REVIEW_RECOMMENDED && it.items.size == 1 }
            assertEquals(3L, reviewVideos.items.single().fileId)
        }

    @Test
    fun `before any scan the hub says so, and its scan button reaches the scan service`() = withEvents { _, viewModel ->
        index.lastScanAt = null

        assertFalse(viewModel.uiState.await { !it.hasScanned }.hasScanned)

        viewModel.startScan()

        assertEquals(1, scans.starts)
    }

    // ---- Marking ----

    @Test
    fun `the other two ways to mark many at once`() = withEvents { _, viewModel ->
        suggest(1L)
        suggest(2L)
        suggest(3L, score = 60, category = SuggestionCategory.LARGE_FORGOTTEN)
        viewModel.uiState.await { it.selected.size == 2 }

        viewModel.unselectAll()
        viewModel.uiState.await { it.selected.isEmpty() }

        viewModel.selectAllSafe()
        val again = viewModel.uiState.await { it.selected.size == 2 }

        assertEquals("Only what is safe, not what wants a review", setOf(1L, 2L), again.selected.map { it.fileId }.toSet())
    }

    @Test
    fun `ignoring a suggestion hides it, and it can be brought back`() = withEvents { events, viewModel ->
        val first = suggest(1L)
        suggest(2L)
        viewModel.uiState.await { it.items.size == 2 }

        viewModel.ignore(first)
        val hidden = viewModel.uiState.await { it.items.size == 1 && it.ignoredCount == 1 }
        awaitUntil { events.size == 1 }

        assertEquals(listOf(2L), hidden.items.map { it.fileId })
        assertEquals("What was ignored is not marked", listOf(2L), hidden.selected.map { it.fileId })
        assertEquals(listOf(HubEvent.Ignored(1L)), events.toList())

        viewModel.setShowIgnored(true)
        val shown = viewModel.uiState.await { it.showIgnored && it.items.size == 2 }
        assertTrue(shown.items.first { it.fileId == 1L }.isDismissed)

        viewModel.unignore(1L)
        val back = viewModel.uiState.await { it.ignoredCount == 0 }
        awaitUntil { events.size == 2 }

        assertEquals(setOf(1L, 2L), back.selected.map { it.fileId }.toSet())
        assertEquals(HubEvent.Unignored(1L), events[1])
    }

    @Test
    fun `a file that is never to be suggested again goes on the exclusions, and can come off`() = withEvents { events, viewModel ->
        val one = suggest(1L)
        suggest(2L)
        // The whitelist manager looks for the canonical path, which on a Windows test host is not "/p/...".
        val canonical = StoragePaths.canonicalize(one.file.path)
        onStorage += canonical
        viewModel.uiState.await { it.items.size == 2 }

        viewModel.neverSuggest(one)
        awaitUntil { events.size == 1 }
        val excluded = viewModel.uiState.await { it.items.size == 1 }

        val rule = (events.single() as HubEvent.Excluded).rule
        assertEquals(canonical, rule.path)
        assertEquals(listOf(rule), rules.rules.value)
        assertTrue("The file left the index with its suggestion", index.files.value.none { it.id == 1L })
        assertEquals(listOf(2L), excluded.selected.map { it.fileId })

        viewModel.allowAgain(rule)

        assertTrue(rules.rules.value.isEmpty())
    }

    @Test
    fun `a rule marks the copies a group does not keep, and keeps the copy the rule names`() = withEvents { events, viewModel ->
        groupOfThree()
        viewModel.uiState.await { it.selected.size == 2 }

        viewModel.applyAutoSelect(AutoSelectRule.KEEP_OLDEST)
        awaitUntil { events.size == 1 }
        val after = viewModel.uiState.await { !it.busy }

        assertEquals(
            "Download holds the oldest copy: it is kept now, and the other two are marked",
            setOf(2L, 3L),
            after.selected.map { it.fileId }.toSet(),
        )
        assertEquals(listOf(1L to true), index.lockChanges)
        assertEquals(HubEvent.RuleApplied(AutoSelectRule.KEEP_OLDEST, groupsChanged = 1), events.single())
        assertTrue("The kept copy is not offered", suggestions.items.value.none { it.fileId == 1L && !it.isDismissed })
    }

    @Test
    fun `a rule for the copy that is kept already changes no lock, and marks by the rule all the same`() = withEvents { events, viewModel ->
        groupOfThree()
        viewModel.uiState.await { it.selected.size == 2 }

        // Backup is kept already, and it lies on the shortest path.
        viewModel.applyAutoSelect(AutoSelectRule.KEEP_SHORTEST_PATH)
        awaitUntil { events.size == 1 }

        assertEquals(HubEvent.RuleApplied(AutoSelectRule.KEEP_SHORTEST_PATH, groupsChanged = 0), events.single())
        assertTrue("No lock was changed", index.lockChanges.isEmpty())
        val after = viewModel.uiState.await { !it.busy }
        assertEquals("The copy the rule keeps is not marked, whatever was marked before", listOf(1L), after.selected.map { it.fileId })
    }

    @Test
    fun `unticking every copy leaves nothing marked`() = withEvents { events, viewModel ->
        groupOfThree()
        suggest(9L, score = 100, category = SuggestionCategory.TEMP_FILE)
        viewModel.uiState.await { it.selected.size == 3 }

        viewModel.applyAutoSelect(AutoSelectRule.UNSELECT_ALL)
        awaitUntil { events.size == 1 }
        val after = viewModel.uiState.await { it.selected.isEmpty() && !it.busy }

        assertTrue(after.selected.isEmpty())
        assertFalse(after.canClean)
        assertEquals(HubEvent.RuleApplied(AutoSelectRule.UNSELECT_ALL, groupsChanged = 0), events.single())
    }

    // ---- Moving to Safe Trash ----

    @Test
    fun `nothing is asked or moved while a scan runs or storage may not be read`() = withEvents { _, viewModel ->
        suggest(1L)
        assertTrue(viewModel.uiState.await { it.selected.isNotEmpty() }.canClean)

        scans.state.value = ScanState.Running(ScanProgress(), isPaused = false)
        val duringScan = viewModel.uiState.await { it.isScanning }
        assertTrue(duringScan.selected.isNotEmpty())
        assertFalse(duringScan.canClean)
        viewModel.requestClean()
        assertFalse("The sheet does not open", settled(viewModel).confirming)

        scans.state.value = ScanState.Idle
        access.granted = false
        viewModel.onResume()
        val withoutAccess = viewModel.uiState.await { !it.storageAccessGranted }
        assertFalse(withoutAccess.canClean)
        viewModel.requestClean()
        assertFalse("The sheet does not open", settled(viewModel).confirming)
        assertTrue(trash.askedToTrash.isEmpty())
    }

    @Test
    fun `what is marked goes to Safe Trash only after the sheet was confirmed`() = withEvents { events, viewModel ->
        suggest(1L)
        suggest(2L)
        viewModel.uiState.await { it.selected.size == 2 }
        assertTrue(trash.askedToTrash.isEmpty())

        viewModel.requestClean()
        assertTrue(settled(viewModel).confirming)
        assertTrue("Asking is not moving", trash.askedToTrash.isEmpty())

        viewModel.cancelClean()
        assertFalse(settled(viewModel).confirming)

        viewModel.requestClean()
        assertTrue(settled(viewModel).confirming)
        viewModel.clean()
        awaitUntil { events.size == 1 }
        viewModel.uiState.await { it.selected.isEmpty() && !it.busy && !it.confirming }

        assertEquals(listOf(listOf(1L, 2L)), trash.askedToTrash)
        val outcome = (events.single() as HubEvent.MovedToTrash).outcome
        assertEquals(listOf("/p/file1", "/p/file2"), outcome.moved.map { it.originalPath })
        assertEquals(200L, outcome.bytes)
        assertTrue(outcome.refused.isEmpty())
        assertEquals(2, manifest.entries.value.size)
    }

    @Test
    fun `what was refused comes back with what was moved`() = withEvents { events, viewModel ->
        suggest(1L)
        suggest(2L)
        viewModel.uiState.await { it.selected.size == 2 }
        trash.nextRefusals = listOf(Refusal(2L, "/p/file2", TrashRefusal.STORAGE_READ_ONLY))

        viewModel.requestClean()
        viewModel.clean()
        awaitUntil { events.size == 1 }

        val outcome = (events.single() as HubEvent.MovedToTrash).outcome
        assertEquals(listOf("/p/file1"), outcome.moved.map { it.originalPath })
        assertEquals(listOf(TrashRefusal.STORAGE_READ_ONLY), outcome.refused.map { it.reason })
        assertEquals("The file that was refused is still where it was", 2L, index.files.value.single().id)
    }

    @Test
    fun `undo puts the files of the last move back where they came from, once`() = withEvents { events, viewModel ->
        suggest(1L)
        viewModel.uiState.await { it.selected.isNotEmpty() }
        viewModel.requestClean()
        viewModel.clean()
        awaitUntil { events.size == 1 }

        viewModel.undoLastTrash()
        awaitUntil { events.size == 2 }

        assertEquals(HubEvent.Restored(1), events[1])
        assertTrue(manifest.entries.value.isEmpty())

        // The move was taken back already; a second tap has nothing to take back and reports nothing.
        viewModel.undoLastTrash()
        viewModel.uiState.await { !it.busy }
        delay(20)

        assertEquals(2, events.size)
    }
}
