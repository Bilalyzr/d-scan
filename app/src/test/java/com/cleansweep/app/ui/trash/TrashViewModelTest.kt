package com.cleansweep.app.ui.trash

import com.cleansweep.app.domain.model.TrashEntry
import com.cleansweep.app.domain.model.TrashPolicy
import com.cleansweep.app.domain.scanner.StorageAccess
import com.cleansweep.app.domain.scanner.trash.PurgeResult
import com.cleansweep.app.domain.scanner.trash.SafeTrash
import com.cleansweep.app.ui.FakeSafeTrashForScreen
import com.cleansweep.app.ui.FakeTrashRepository
import com.cleansweep.app.ui.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId

/** The Safe Trash screen: what it shows of the bin, and what putting back and emptying do. */
class TrashViewModelTest {

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

    /** A clock the test moves by hand: the days left must follow the day, not the moment of the first look. */
    private class SettableClock(
        var now: Long,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private val manifest = FakeTrashRepository()
    private val trash: SafeTrash = FakeSafeTrashForScreen(manifest)
    private val access = FakeAccess()

    @Before
    fun setUp() {
        val deletedAt = NOW - 5 * DAY
        manifest.set(
            TrashEntry(originalPath = "/p/Documents/report.pdf", trashPath = "/p/.cleansweep-trashed-1-report.pdf", sizeBytes = 1_200_000L, deletedAt = deletedAt, expiresAt = TrashPolicy.expiresAt(deletedAt)),
            TrashEntry(originalPath = "/p/Download/setup.log", trashPath = "/p/Download/.cleansweep-trashed-2-setup.log", sizeBytes = 3_000L, deletedAt = deletedAt - 20 * DAY, expiresAt = TrashPolicy.expiresAt(deletedAt - 20 * DAY)),
        )
    }

    private val clock = SettableClock(NOW)

    private fun makeViewModel() = TrashViewModel(trash = trash, storageAccess = access, clock = clock)

    private suspend fun StateFlow<TrashUiState>.await(
        predicate: (TrashUiState) -> Boolean,
    ): TrashUiState = withTimeout(10_000L) {
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

    private fun withEvents(
        block: suspend CoroutineScope.(events: MutableList<TrashEvent>, viewModel: TrashViewModel) -> Unit,
    ): Unit = runBlocking {
        val events = ArrayList<TrashEvent>()
        val viewModel = makeViewModel()
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
    fun `each entry is shown with its name, where it came from, and the days it has left`() = withEvents { _, viewModel ->
        val state = viewModel.uiState.await { it.rows.size == 2 }

        assertEquals(listOf("report.pdf", "setup.log"), state.rows.map { it.name })
        assertEquals(listOf("/p/Documents", "/p/Download"), state.rows.map { it.originalFolder })
        assertEquals("Five days old: 25 days left", listOf(25, 5), state.rows.map { it.daysLeft })
        assertEquals(1_203_000L, state.totalBytes)
        assertEquals(5, state.nextPurgeDays)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `the days left follow the day, not the moment of the first look`() = withEvents { _, viewModel ->
        viewModel.uiState.await { it.rows.size == 2 }

        clock.now += 4 * DAY
        viewModel.onResume()

        val later = viewModel.uiState.await { it.rows.first().daysLeft == 21 }

        assertEquals(listOf(21, 1), later.rows.map { it.daysLeft })
    }

    @Test
    fun `an entry whose time is up is shown as due`() = withEvents { _, viewModel ->
        viewModel.uiState.await { it.rows.size == 2 }

        clock.now += 30 * DAY
        viewModel.onResume()

        val due = viewModel.uiState.await { it.rows.all { row -> row.isDue } }

        assertTrue(due.rows.all { it.daysLeft == 0 })
        assertEquals(0, due.nextPurgeDays)
    }

    @Test
    fun `putting one entry back removes it from the bin and reports it`() = withEvents { events, viewModel ->
        val state = viewModel.uiState.await { it.rows.size == 2 }
        val first = state.rows.first().entry

        viewModel.restore(first.id)
        awaitUntil { events.size == 1 }
        val after = viewModel.uiState.await { it.rows.size == 1 && !it.busy }

        assertEquals(TrashEvent.Restored(1, 0, 0), events.single())
        assertEquals("Only the other one is left", "setup.log", after.rows.single().name)
    }

    @Test
    fun `putting everything back empties the bin`() = withEvents { events, viewModel ->
        viewModel.uiState.await { it.rows.size == 2 }

        viewModel.restoreEverything()
        awaitUntil { events.size == 1 }

        assertTrue(viewModel.uiState.await { it.isEmpty && !it.busy }.isEmpty)
        assertEquals(TrashEvent.Restored(2, 0, 0), events.single())
    }

    @Test
    fun `nothing is deleted for good until the question was answered`() = withEvents { events, viewModel ->
        viewModel.uiState.await { it.rows.size == 2 }

        viewModel.requestEmpty()
        assertTrue("The question is on screen, nothing is deleted", viewModel.uiState.await { it.confirmingEmpty }.rows.size == 2)

        viewModel.cancelEmpty()
        assertFalse(viewModel.uiState.await { !it.confirmingEmpty }.confirmingEmpty)
        assertEquals("Cancelled: nothing deleted", 2, manifest.entries.value.size)

        viewModel.requestEmpty()
        viewModel.emptyTrashPermanently()
        awaitUntil { events.size == 1 }

        assertTrue(manifest.entries.value.isEmpty())
        assertEquals(TrashEvent.Emptied(PurgeResult(2, 1_203_000L, 0, 0, 0, 0)), events.single())
    }

    @Test
    fun `emptying an empty bin asks nothing and deletes nothing`() = withEvents { events, viewModel ->
        manifest.entries.value = emptyList()
        viewModel.uiState.await { it.isEmpty }

        viewModel.requestEmpty()
        viewModel.emptyTrashPermanently()

        assertFalse(viewModel.uiState.value.confirmingEmpty)
        awaitUntil { !viewModel.uiState.value.busy }
        assertEquals("Nothing to report", 0, events.size)
    }

    @Test
    fun `what the bin kept is reported, not only what it deleted`() = withEvents { events, viewModel ->
        (trash as FakeSafeTrashForScreen).nextEmptyResult = PurgeResult(1, 1_200_000L, 0, 1, 0, 0)
        viewModel.uiState.await { it.rows.size == 2 }
        viewModel.requestEmpty()
        viewModel.emptyTrashPermanently()
        awaitUntil { events.size == 1 }

        val reported = (events.single() as TrashEvent.Emptied).result
        assertEquals(1, reported.deletedEntries)
        assertEquals("A folder that had something in it after all stays", 1, reported.foldersNoLongerEmpty)
    }

    @Test
    fun `access taken away in the system settings is noticed on return`() = withEvents { _, viewModel ->
        assertTrue(viewModel.uiState.await { it.rows.size == 2 }.storageAccessGranted)

        access.granted = false
        viewModel.onResume()

        assertFalse(viewModel.uiState.await { !it.storageAccessGranted }.storageAccessGranted)
    }

    @Test
    fun `an empty bin shows nothing to restore`() = withEvents { _, viewModel ->
        manifest.entries.value = emptyList()

        val empty = viewModel.uiState.await { it.isEmpty }

        assertNull(empty.nextPurgeDays)
        assertEquals(0L, empty.totalBytes)
    }

    private companion object {
        const val DAY: Long = 24L * 60 * 60 * 1000
        val NOW: Long = 1_800_000_000_000L
    }
}
