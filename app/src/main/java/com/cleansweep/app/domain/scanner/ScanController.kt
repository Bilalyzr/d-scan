package com.cleansweep.app.domain.scanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the scan service is doing. Emitted through [ScanController.state]. */
sealed interface ScanState {
    data object Idle : ScanState

    data class Running(
        val progress: ScanProgress,
        val isPaused: Boolean,
    ) : ScanState

    data class Finished(
        val summary: ScanSummary,
    ) : ScanState

    /** The user cancelled. What was indexed and hashed up to [lastProgress] is kept for the next scan. */
    data class Cancelled(
        val lastProgress: ScanProgress,
    ) : ScanState

    data class Failed(
        val failure: ScanFailure,
        /** Set when something unexpected went wrong, so that it can be logged. */
        val cause: Throwable? = null,
    ) : ScanState
}

/**
 * The scan service: the one place that starts, pauses, resumes and cancels scans.
 *
 * One scan runs at a time. It runs in [scope], which outlives any screen, so a scan survives the user leaving
 * the progress screen, and it never blocks the caller: [start] returns at once. Cancellation is plain coroutine
 * cancellation, and the walk and the hashing check for it between entries and between buffers.
 *
 * Progress is thinned out to one snapshot per [PROGRESS_INTERVAL_MS], plus one for every phase change, because
 * files go by far faster than any screen can draw.
 */
class ScanController(
    private val runner: ScanRunner,
    private val storageAccess: StorageAccess,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : ScanControl {
    private val lock = Any()

    @Volatile
    private var job: Job? = null
    private var run = 0
    private var gate = PauseGate()
    private var isPaused = false
    private var lastProgress = ScanProgress()
    private var lastPublishedPhase: ScanPhase? = null
    private var lastPublishedAt = 0L

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    override val state: StateFlow<ScanState> = _state.asStateFlow()

    override val isRunning: Boolean
        get() = synchronized(lock) { job?.isActive == true }

    /**
     * Starts a scan and returns at once.
     *
     * @return false when a scan is already running, or when the app may not read storage yet. In the second
     *   case the state becomes [ScanState.Failed] with [ScanFailure.STORAGE_PERMISSION_MISSING]: the caller has
     *   to show the prominent disclosure first and send the user to the system settings.
     */
    override fun start(request: ScanRequest): Boolean {
        synchronized(lock) {
            if (job?.isActive == true) {
                return false
            }
            // From here on, anything still unwinding from an earlier scan must not report any more.
            val myRun = ++run
            if (!storageAccess.canReadStorage()) {
                _state.value = ScanState.Failed(ScanFailure.STORAGE_PERMISSION_MISSING)
                return false
            }
            val myGate = PauseGate()
            gate = myGate
            isPaused = false
            lastProgress = ScanProgress()
            lastPublishedPhase = null
            lastPublishedAt = -PROGRESS_INTERVAL_MS
            _state.value = ScanState.Running(ScanProgress(), isPaused = false)
            job = scope.launch {
                try {
                    val summary = runner.run(request, myGate) { progress -> onProgress(myRun, progress) }
                    synchronized(lock) {
                        if (run == myRun) _state.value = ScanState.Finished(summary)
                    }
                } catch (cancelled: CancellationException) {
                    synchronized(lock) {
                        if (run == myRun) _state.value = ScanState.Cancelled(lastProgress)
                    }
                    throw cancelled
                } catch (failure: ScanException) {
                    synchronized(lock) {
                        if (run == myRun) _state.value = ScanState.Failed(failure.failure, failure)
                    }
                } catch (failure: Exception) {
                    synchronized(lock) {
                        if (run == myRun) _state.value = ScanState.Failed(ScanFailure.UNEXPECTED_ERROR, failure)
                    }
                }
            }
        }
        return true
    }

    /** Makes the scan wait where it is. Nothing is undone; [resume] carries on from the next entry. */
    override fun pause(): Boolean = setPaused(paused = true)

    override fun resume(): Boolean = setPaused(paused = false)

    private fun setPaused(paused: Boolean): Boolean {
        synchronized(lock) {
            if (job?.isActive != true) {
                return false
            }
            isPaused = paused
            if (paused) gate.close() else gate.open()
            _state.value = ScanState.Running(lastProgress, isPaused = paused)
            return true
        }
    }

    /** Stops the scan. The state becomes [ScanState.Cancelled] once the scan has come to a halt. */
    override fun cancel(): Boolean {
        synchronized(lock) {
            val running = job
            if (running?.isActive != true) {
                return false
            }
            running.cancel()
            return true
        }
    }

    /** Goes back to [ScanState.Idle] once no scan is running, to put the last result away. */
    override fun reset() {
        synchronized(lock) {
            if (job?.isActive != true) {
                _state.value = ScanState.Idle
            }
        }
    }

    private fun onProgress(
        myRun: Int,
        progress: ScanProgress,
    ) {
        synchronized(lock) {
            lastProgress = progress
            val phaseChanged = progress.phase != lastPublishedPhase
            val due = nowMillis() - lastPublishedAt >= PROGRESS_INTERVAL_MS
            if (!phaseChanged && !due) {
                return
            }
            lastPublishedPhase = progress.phase
            lastPublishedAt = nowMillis()
            // A snapshot from a worker can arrive after its scan has been replaced or has finished.
            if (run == myRun && job?.isActive == true) {
                _state.value = ScanState.Running(progress, isPaused = isPaused)
            }
        }
    }

    companion object {
        const val PROGRESS_INTERVAL_MS: Long = 150L
    }
}
