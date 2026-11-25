package com.cleansweep.app.ui.scan

import androidx.lifecycle.ViewModel
import com.cleansweep.app.domain.scanner.ScanControl
import com.cleansweep.app.domain.scanner.ScanPhase
import com.cleansweep.app.domain.scanner.ScanState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * How far a scan is, and what the user can do about it. The scan itself runs in the scan service and outlives
 * this screen: leaving the screen does not stop it.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val scans: ScanControl,
) : ViewModel() {

    val state: StateFlow<ScanState> = scans.state

    /** Emits when a scan finishes while this screen is open, so that it can go on to the suggestions. */
    val finished: Flow<Unit> = scans.state
        .filter { it is ScanState.Finished }
        .map { }

    fun pause(): Boolean = scans.pause()

    fun resume(): Boolean = scans.resume()

    fun cancel(): Boolean = scans.cancel()

    /** Starts another scan from where this one ended, for a scan that was cancelled or failed. */
    fun startAgain() {
        scans.start()
    }

    companion object {
        /**
         * The share of the current stage that is done, from 0 to 1, or null while the stage has no known size.
         * Only the two hashing stages know beforehand how many files they have to get through; while folders are
         * being read there is no telling, and the ring keeps turning.
         */
        fun hashingFraction(
            state: ScanState.Running,
        ): Float? {
            val progress = state.progress
            val toHash = progress.filesToHash?.takeIf { it > 0 } ?: return null
            if (state.progress.phase != ScanPhase.PARTIAL_HASHING && state.progress.phase != ScanPhase.FULL_HASHING) {
                return null
            }
            return (progress.filesHashed.toFloat() / toHash).coerceIn(0f, 1f)
        }
    }
}
