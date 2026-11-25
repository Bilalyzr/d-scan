package com.cleansweep.app.domain.scanner

import kotlinx.coroutines.flow.StateFlow

/**
 * What the screens need from the scan service: its state, and the things a user can do about a scan.
 *
 * [ScanController] is the implementation. The screens see only this interface, so a ViewModel can be tested
 * against a fake and never starts a real scan.
 */
interface ScanControl {
    val state: StateFlow<ScanState>

    val isRunning: Boolean

    /**
     * Starts a scan and returns at once.
     *
     * @return false when a scan is already running, or when the app may not read storage yet. In the second
     *   case the state becomes [ScanState.Failed] with [ScanFailure.STORAGE_PERMISSION_MISSING], which is the
     *   caller's cue to show the storage access screen.
     */
    fun start(request: ScanRequest = ScanRequest()): Boolean

    /** Makes the scan wait where it is. Nothing is undone; [resume] carries on from the next entry. */
    fun pause(): Boolean

    fun resume(): Boolean

    /** Stops the scan. The state becomes [ScanState.Cancelled] once the scan has come to a halt. */
    fun cancel(): Boolean

    /** Goes back to [ScanState.Idle] once no scan is running, to put the last result away. */
    fun reset()
}
