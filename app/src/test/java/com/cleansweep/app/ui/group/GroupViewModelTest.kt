package com.cleansweep.app.ui.group

import androidx.lifecycle.SavedStateHandle
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
import com.cleansweep.app.domain.scanner.duplicates.AutoSelectRule
import com.cleansweep.app.domain.scanner.duplicates.DuplicateGroupEditor
import com.cleansweep.app.domain.scanner.junk.InstalledApkOracle
import com.cleansweep.app.file
import com.cleansweep.app.ui.FakeFileIndexRepository
import com.cleansweep.app.ui.FakeScanControl
import com.cleansweep.app.ui.FakeSafeTrash
import com.cleansweep.app.ui.FakeSuggestionRepository
import com.cleansweep.app.ui.FakeTrashRepository
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

/** The screen of one group of identical files: what it shows, and what keeping, ticking and moving do. */
class GroupViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

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
    private val rules = FakeWhitelistRepository(index, suggestions)
    private val scans = FakeScanControl()
    private val access = FakeAccess()
    private val marked = CleanSelectionStore()

    private val editor = DuplicateGroupEditor(
        fileIndex = index,
        suggestions = suggestions,
        whitelist = rules,
        installedApks = InstalledApkOracle.NONE,
        guard = SuggestionGuard(),
        settings = FakeSettingsRepository(),
        clock = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC),
        ioDispatcher = mainDispatcher.dispatcher,
    )

    /**
     * Three identical files: the oldest in Download, the newest in Documents. The usual rule keeps the copy in
     * Backup, the oldest of those outside Download. Download and Documents are offered.
     */
    @Before
    fun groupOfThree() {
        val files = listOf(
            file("/p/Download/a.pdf", lastModified = 100L, fullHash = "h").copy(id = 1L),
            file("/p/Documents/a.pdf", lastModified = 300L, fullHash = "h").copy(id = 2L),
            file("/p/Backup/a.pdf", lastModified = 200L, fullHash = "h").copy(id = 3L),
        )
        index.set(*files.toTypedArray())
        suggestions.set(suggestionFor(1L, files[0]), suggestionFor(2L, files[1]))
    }

    private fun suggestionFor(
        id: Long,
        file: IndexedFile,
        score: Int = 100,
    ) = Suggestion(id, SuggestionCategory.EXACT_DUPLICATE, score, ReasonCode.EXACT_COPY, listOf(ReasonCode.EXACT_COPY), false, file)

    private val manifest = FakeTrashRepository()
    private val trash = FakeSafeTrash(index, suggestions, manifest)

    private fun makeViewModel(
        hash: String = "h",
    ) = GroupViewModel(
        fileIndex = index,
        suggestions = suggestions,
        scans = scans,
        storageAccess = access,
        selectionStore = marked,
        editor = editor,
        trash = trash,
        savedState = SavedStateHandle(mapOf(Routes.ARG_HASH to hash)),
    )

    /** The first state that satisfies [predicate], giving the screen's own work a turn between looks. */
    private suspend fun StateFlow<GroupUiState>.await(
        predicate: (GroupUiState) -> Boolean,
    ): GroupUiState = withTimeout(10_000L) {
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

    /** Runs [block] with the screen watched the way its screen watches it. */
    private fun withEvents(
        hash: String = "h",
        block: suspend CoroutineScope.(events: MutableList<GroupEvent>, viewModel: GroupViewModel) -> Unit,
    ): Unit = runBlocking {
        val events = ArrayList<GroupEvent>()
        val viewModel = makeViewModel(hash)
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
    fun `the kept copy is shown first, then the others from oldest to newest`() = withEvents { _, viewModel ->
        val state = viewModel.uiState.await { it.copies.size == 3 }

        assertEquals(listOf(3L, 1L, 2L), state.copies.map { it.file.id })
        assertEquals(listOf(true, false, false), state.copies.map { it.isOriginal })
        assertEquals("Only the copies that are not kept are offered", listOf(1L, 2L), state.offered.map { it.file.id })
        assertEquals(200L, state.reclaimableBytes)
        assertEquals("a.pdf", state.name)
        assertFalse(state.isGone)
    }

    @Test
    fun `keeping another copy offers the copy that was kept until then`() = withEvents { events, viewModel ->
        val before = viewModel.uiState.await { it.copies.size == 3 }
        val download = before.copies.first { it.file.id == 1L }

        viewModel.keep(download)
        awaitUntil { events.size == 1 }
        val after = viewModel.uiState.await { it.copies.first().file.id == 1L && !it.busy }

        assertEquals(listOf(1L, 3L, 2L), after.copies.map { it.file.id })
        assertEquals("Backup is offered now", listOf(3L, 2L), after.offered.map { it.file.id })
        assertEquals(GroupEvent.CopyKept, events.single())
        assertEquals(listOf(1L to true), index.lockChanges)
        assertTrue(after.copies.first { it.file.id == 1L }.isKeptByUser)
    }

    @Test
    fun `the copies that are safe to clean are ticked from the start, and a kept copy cannot be ticked`() =
        withEvents { events, viewModel ->
            val state = viewModel.uiState.await { it.copies.size == 3 }
            assertEquals("Exact copies are safe to clean, so both are ticked", listOf(1L, 2L), state.selected.map { it.file.id })
            assertTrue(state.canClean)

            viewModel.toggle(state.copies.first { it.file.id == 1L })
            val unticked = viewModel.uiState.await { it.selected.size == 1 }
            assertEquals(listOf(2L), unticked.selected.map { it.file.id })
            assertEquals(100L, unticked.summary.bytes)

            viewModel.toggle(state.copies.first { it.file.id == 3L })
            awaitUntil { events.size == 1 }

            assertEquals(GroupEvent.KeptByRule, events.single())
            assertEquals("The kept copy is not ticked", listOf(2L), viewModel.uiState.value.selected.map { it.file.id })
        }

    @Test
    fun `a rule ticks every copy but the one it keeps`() = withEvents { events, viewModel ->
        viewModel.uiState.await { it.copies.size == 3 }

        viewModel.applyRule(AutoSelectRule.KEEP_OLDEST)
        awaitUntil { events.size == 1 }
        val after = viewModel.uiState.await { it.copies.first().file.id == 1L && !it.busy }

        assertEquals("Download holds the oldest copy, so it is kept and the other two are ticked", setOf(2L, 3L), after.selected.map { it.file.id }.toSet())
        assertEquals(GroupEvent.RuleApplied(AutoSelectRule.KEEP_OLDEST), events.single())

        viewModel.applyRule(AutoSelectRule.UNSELECT_ALL)
        val none = viewModel.uiState.await { it.selected.isEmpty() && !it.busy }

        assertTrue(none.selected.isEmpty())
        assertFalse(none.canClean)
    }

    @Test
    fun `a rule that names the copy kept already changes no lock`() = withEvents { events, viewModel ->
        viewModel.uiState.await { it.copies.size == 3 }

        // Backup is kept already, and it lies on the shortest path.
        viewModel.applyRule(AutoSelectRule.KEEP_SHORTEST_PATH)
        awaitUntil { events.size == 1 }
        val after = viewModel.uiState.await { !it.busy }

        assertTrue(index.lockChanges.isEmpty())
        assertEquals("The other two are ticked", setOf(1L, 2L), after.selected.map { it.file.id }.toSet())
    }

    @Test
    fun `giving up a copy kept by the user returns the group to its rule`() = withEvents { events, viewModel ->
        val before = viewModel.uiState.await { it.copies.size == 3 }
        val download = before.copies.first { it.file.id == 1L }
        viewModel.keep(download)
        awaitUntil { events.size == 1 }
        val kept = viewModel.uiState.await { it.copies.first().file.id == 1L && !it.busy }

        viewModel.release(kept.copies.first())
        awaitUntil { events.size == 2 }
        val after = viewModel.uiState.await { it.copies.first().file.id == 3L && !it.busy }

        assertEquals(GroupEvent.CopyReleased, events[1])
        assertEquals(listOf(1L to true, 1L to false), index.lockChanges)
        assertEquals("Backup is kept by the rule again", listOf(3L, 1L, 2L), after.copies.map { it.file.id })
    }

    @Test
    fun `nothing can be ticked while a scan runs or without access to storage`() = withEvents { _, viewModel ->
        val state = viewModel.uiState.await { it.copies.size == 3 }
        val offered = state.copies.first { it.file.id == 1L }
        scans.state.value = ScanState.Running(ScanProgress(), isPaused = false)
        viewModel.uiState.await { it.isScanning }

        viewModel.toggle(offered)

        assertEquals("Ticking changes nothing while a scan runs", listOf(1L, 2L), viewModel.uiState.value.selected.map { it.file.id })
        viewModel.requestClean()

        scans.state.value = ScanState.Idle
        access.granted = false
        viewModel.onResume()
        viewModel.uiState.await { !it.storageAccessGranted }

        assertFalse(viewModel.uiState.value.canMark)
    }

    @Test
    fun `ignoring a copy hides its suggestion, and it cannot be ticked until it is brought back`() = withEvents { events, viewModel ->
        val state = viewModel.uiState.await { it.copies.size == 3 }
        val offered = state.copies.first { it.file.id == 1L }

        viewModel.ignore(offered)
        awaitUntil { events.size == 1 }
        val ignored = viewModel.uiState.await { it.offered.none { copy -> copy.file.id == 1L } }

        assertEquals(GroupEvent.Ignored(1L), events.single())
        assertFalse(ignored.copies.first { it.file.id == 1L }.canMark)

        viewModel.unignore(1L)
        awaitUntil { events.size == 2 }
        val back = viewModel.uiState.await { it.offered.any { copy -> copy.file.id == 1L } }

        assertEquals(GroupEvent.Unignored(1L), events[1])
        assertTrue(back.copies.first { it.file.id == 1L }.canMark)
    }

    @Test
    fun `the ticked copies go to Safe Trash only after the sheet was confirmed`() = withEvents { events, viewModel ->
        viewModel.uiState.await { it.selected.size == 2 }

        viewModel.requestClean()
        viewModel.uiState.await { it.confirming }
        viewModel.cancelClean()
        viewModel.uiState.await { !it.confirming }
        assertTrue("Asking is not moving", trash.askedToTrash.isEmpty())

        viewModel.requestClean()
        viewModel.uiState.await { it.confirming }
        viewModel.clean()
        awaitUntil { events.size == 1 }
        viewModel.uiState.await { it.selected.isEmpty() && !it.busy }

        assertEquals(listOf(listOf(1L, 2L)), trash.askedToTrash)
        val outcome = (events.single() as GroupEvent.MovedToTrash).outcome
        assertEquals(listOf("/p/Download/a.pdf", "/p/Documents/a.pdf"), outcome.moved.map { it.originalPath })
    }

    @Test
    fun `when the files are no longer a group the screen says so`() = withEvents { _, viewModel ->
        viewModel.uiState.await { it.copies.size == 3 }

        // Both offered copies were moved away: one copy is left, which is no group.
        index.removeFiles(listOf("/p/Download/a.pdf", "/p/Documents/a.pdf"))

        val gone = viewModel.uiState.await { it.isGone }

        assertTrue(gone.isGone)
        assertFalse(gone.loading)
    }

    @Test
    fun `a group that was never there is said to be gone, not waited for`() = withEvents(hash = "no-such-hash") { _, viewModel ->
        assertTrue(viewModel.uiState.await { !it.loading }.isGone)
    }
}
