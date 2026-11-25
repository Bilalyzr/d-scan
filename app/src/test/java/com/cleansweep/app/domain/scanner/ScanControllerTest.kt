package com.cleansweep.app.domain.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.domain.model.SuggestionCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

private class MutableAccess(
    var granted: Boolean = true,
) : StorageAccess {
    override fun canReadStorage(): Boolean = granted
}

/**
 * The scan service on a real engine and real files. What the service adds on top of the runner is all that is
 * tested here: one scan at a time, permission and failure states, pause and resume, cancellation, and thinned
 * progress.
 */
@RunWith(AndroidJUnit4::class)
class ScanControllerTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var harness: ScanHarness
    private val access = MutableAccess()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = CopyOnWriteArrayList<ScanState>()
    private var recording: Job? = null

    /** The hand-moved clock of the controller under test: no time passes unless a test moves it. */
    private var now: Long = 0L
    private lateinit var controller: ScanController

    @Before
    fun setUp() {
        harness = ScanHarness(temporaryFolder.root)
        controller = ScanController(harness.runner, access, scope) { now }
        recording = scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            controller.state.collect { states += it }
        }
    }

    @After
    fun tearDown() {
        recording?.cancel()
        scope.cancel()
        harness.close()
    }

    /**
     * Waits until the controller's state satisfies [where], and returns that state.
     *
     * The states are also recorded by a collector on another thread. Assertions are about that recording, so it is
     * given time to catch up here; otherwise a test could look before the collector had seen what it waits for.
     */
    private suspend fun awaitState(where: (ScanState) -> Boolean): ScanState {
        val state = withTimeout(20_000) { controller.state.first { where(it) } }
        withTimeout(20_000) { while (states.none(where)) delay(1) }
        return state
    }

    private fun running(): ScanState.Running = controller.state.value as ScanState.Running

    /**
     * Starts a scan over two identical files and waits until it is inside the hashing stage, where it stays
     * until the returned deferred is completed.
     */
    private suspend fun holdScanWhileHashing(): CompletableDeferred<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.hasher.beforeHashing = {
            entered.complete(Unit)
            release.await()
        }
        val content = ScanHarness.deterministicBytes(sizeBytes = 4_096, seed = 1)
        harness.write("Documents/a.bin", content)
        harness.write("Download/a (1).bin", content)
        assertTrue(controller.start())
        withTimeout(10_000) { entered.await() }
        return release
    }

    // ---- Starting ----

    @Test
    fun `a scan runs to completion and ends in a summary`() = runBlocking {
        harness.write("Download/notes.log", 100, seed = 1)

        assertTrue(controller.start())

        val finished = awaitState { it is ScanState.Finished } as ScanState.Finished
        assertEquals(1L, finished.summary.filesIndexed)
        assertEquals(mapOf(SuggestionCategory.TEMP_FILE to 1), finished.summary.suggestions)
        assertFalse(controller.isRunning)
        // The state went from Idle to Running before anything else.
        assertEquals(ScanState.Idle, states.first())
        assertTrue(states[1] is ScanState.Running)
    }

    @Test
    fun `only one scan runs at a time`() = runBlocking {
        val release = holdScanWhileHashing()

        assertFalse(controller.start())

        assertTrue(controller.state.value is ScanState.Running)
        release.complete(Unit)
        awaitState { it is ScanState.Finished }
        assertEquals(1, states.count { it is ScanState.Finished })
    }

    @Test
    fun `a scan cannot start without permission to read storage`() = runBlocking {
        access.granted = false
        harness.write("Download/notes.log", 100, seed = 1)

        assertFalse(controller.start())

        assertEquals(ScanState.Failed(ScanFailure.STORAGE_PERMISSION_MISSING), controller.state.value)
        assertFalse(controller.isRunning)
        assertTrue("nothing may have been read", harness.hasher.partiallyHashed.isEmpty())
    }

    @Test
    fun `a scan with no readable volume fails with a reason the app can explain`() = runBlocking {
        harness.roots = emptyList()

        assertTrue(controller.start())

        val failed = awaitState { it is ScanState.Failed } as ScanState.Failed
        assertEquals(ScanFailure.NO_STORAGE_TO_SCAN, failed.failure)
        assertTrue(failed.cause is ScanException)
        assertFalse(controller.isRunning)
    }

    @Test
    fun `an unexpected error is reported with its cause instead of leaving the scan running forever`() = runBlocking {
        harness.settings.failure = IOException("settings file is corrupt")

        assertTrue(controller.start())

        val failed = awaitState { it is ScanState.Failed } as ScanState.Failed
        assertEquals(ScanFailure.UNEXPECTED_ERROR, failed.failure)
        assertEquals("settings file is corrupt", failed.cause?.message)
        assertFalse(controller.isRunning)
    }

    // ---- Pause and resume ----

    @Test
    fun `pause and resume are reflected in the state, and only mean something while a scan is running`() =
        runBlocking {
            val release = holdScanWhileHashing()

            assertTrue(controller.pause())
            assertTrue(running().isPaused)
            assertTrue("pausing twice changes nothing", controller.pause())
            assertTrue(running().isPaused)

            assertTrue(controller.resume())
            assertFalse(running().isPaused)

            release.complete(Unit)
            awaitState { it is ScanState.Finished }
            assertFalse(controller.pause())
            assertFalse(controller.resume())
        }

    @Test
    fun `a paused scan waits where it is until it is resumed`() = runBlocking {
        repeat(300) { harness.write("Download/f$it.log", 10, seed = it) }

        assertTrue(controller.start())
        assertTrue(controller.pause())
        delay(300)
        assertTrue("a paused scan must not finish", controller.state.value is ScanState.Running)
        assertTrue(running().isPaused)

        assertTrue(controller.resume())

        val finished = awaitState { it is ScanState.Finished } as ScanState.Finished
        assertEquals(300, finished.summary.suggestions.getValue(SuggestionCategory.TEMP_FILE))
    }

    // ---- Cancelling ----

    @Test
    fun `cancelling stops the scan, reports it as cancelled, and does not block the next scan`() = runBlocking {
        val release = holdScanWhileHashing()
        assertTrue(controller.cancel())

        val cancelled = awaitState { it is ScanState.Cancelled } as ScanState.Cancelled
        assertEquals(ScanPhase.PARTIAL_HASHING, cancelled.lastProgress.phase)
        assertFalse(controller.isRunning)
        assertFalse("nothing is left to cancel", controller.cancel())

        // The walk had finished before the hashing was held, so both files are in the index.
        assertNotNull(harness.indexed(harness.at("Download/a (1).bin")))
        assertNotNull(harness.indexed(harness.at("Documents/a.bin")))
        assertTrue(harness.storedSuggestions().isEmpty())

        release.complete(Unit)
        assertTrue(controller.start())
        awaitState { it is ScanState.Finished }
        // The cancelled scan and the one after it each ended in their own state, in that order.
        val ends = states.filterIsInstance<ScanState>() .mapNotNull { it as? ScanState.Cancelled ?: (it as? ScanState.Finished) }
        assertTrue(ends[0] is ScanState.Cancelled)
        assertTrue(ends[1] is ScanState.Finished)
    }

    @Test
    fun `pausing or cancelling when nothing runs does nothing`() {
        assertFalse(controller.pause())
        assertFalse(controller.resume())
        assertFalse(controller.cancel())
        assertEquals(ScanState.Idle, controller.state.value)
    }

    // ---- Progress ----

    @Test
    fun `while no time passes, only the phase changes are published`() = runBlocking {
        repeat(400) { harness.write("Download/f$it.log", 10, seed = it) }

        assertTrue(controller.start())
        awaitState { it is ScanState.Finished }

        val runningStates = states.filterIsInstance<ScanState.Running>()
        assertEquals(
            listOf(ScanPhase.WALKING, ScanPhase.PARTIAL_HASHING, ScanPhase.FULL_HASHING, ScanPhase.ANALYSING),
            runningStates.map { it.progress.phase }.distinct(),
        )
        // Few snapshots for four hundred files, and the counters in them only ever move forward.
        assertTrue("got ${runningStates.size} snapshots", runningStates.size < 10)
        val filesScanned = runningStates.map { it.progress.filesScanned }
        assertEquals(filesScanned.sorted(), filesScanned)
        assertEquals(400L, runningStates.last().progress.filesScanned)
    }

    @Test
    fun `snapshots are published as time goes by, and they never skip a phase change`() = runBlocking {
        // Two hundred milliseconds pass between any two snapshots the controller looks at.
        var clock = 0L
        val lively = ScanController(harness.runner, access, scope) { clock += 200; clock }
        val published = CopyOnWriteArrayList<ScanProgress>()
        val collector = scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            lively.state.collect { if (it is ScanState.Running) published += it.progress }
        }
        repeat(200) { harness.write("Folder $it/notes.log", 10, seed = it) }

        assertTrue(lively.start())
        withTimeout(20_000) { lively.state.first { it is ScanState.Finished } }
        collector.cancel()

        val phases = published.map { it.phase }
        assertEquals(ScanPhase.entries.toList(), phases.distinct())
        assertTrue("got ${published.size} snapshots", published.size > ScanPhase.entries.size)
        assertEquals(200L, published.last().filesScanned)
    }

    @Test
    fun `reset puts the last result away`() = runBlocking {
        harness.write("Download/notes.log", 100, seed = 1)
        assertTrue(controller.start())
        awaitState { it is ScanState.Finished }

        controller.reset()

        assertEquals(ScanState.Idle, controller.state.value)
    }
}
