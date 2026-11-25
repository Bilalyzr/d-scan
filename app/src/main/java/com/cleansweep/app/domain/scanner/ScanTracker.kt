package com.cleansweep.app.domain.scanner

/**
 * Keeps the running [ScanProgress] of one scan and hands snapshots to the listener.
 *
 * The walk and every hashing worker report into the same tracker from different threads, so each method is
 * synchronized and every snapshot is complete: a listener never sees the hashing counters of a stage reset the
 * counters of the walk.
 */
class ScanTracker(
    private val listener: ScanProgressListener,
) {
    var current: ScanProgress = ScanProgress()
        private set

    /** Moves to [phase] and starts its hashing counters over. Always published: phase changes are what the UI waits for. */
    @Synchronized
    fun enterPhase(
        phase: ScanPhase,
        filesToHash: Int? = null,
    ) {
        current = current.copy(phase = phase, filesHashed = 0, filesToHash = filesToHash)
        publish()
    }

    @Synchronized
    fun fileScanned(sizeBytes: Long) {
        current = current.copy(
            filesScanned = current.filesScanned + 1,
            bytesScanned = current.bytesScanned + sizeBytes,
        )
        // Files go by far faster than any screen can draw.
        if (current.filesScanned % PUBLISH_EVERY_N_FILES == 0L) {
            publish()
        }
    }

    @Synchronized
    fun folderEntered(path: String) {
        current = current.copy(foldersScanned = current.foldersScanned + 1, currentFolder = path)
        publish()
    }

    @Synchronized
    fun fileHashed() {
        current = current.copy(filesHashed = current.filesHashed + 1)
        publish()
    }

    /** Counts a failure the scan walked over. Not published on its own: the next snapshot carries it. */
    @Synchronized
    fun error() {
        current = current.copy(errors = current.errors + 1)
    }

    private fun publish() {
        listener.onProgress(current)
    }

    companion object {
        /** A snapshot goes out every so many files; folders, phases and hashes each send one. */
        const val PUBLISH_EVERY_N_FILES: Long = 64L
    }
}
