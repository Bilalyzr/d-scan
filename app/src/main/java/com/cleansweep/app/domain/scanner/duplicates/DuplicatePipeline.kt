package com.cleansweep.app.domain.scanner.duplicates

import com.cleansweep.app.domain.model.DuplicateGroup
import com.cleansweep.app.domain.model.FileHashUpdate
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.scanner.PauseGate
import com.cleansweep.app.domain.scanner.ScanPhase
import com.cleansweep.app.domain.scanner.ScanTracker
import com.cleansweep.app.domain.scanner.hashing.FileHasher
import com.cleansweep.app.domain.scanner.hashing.HashOutcome
import com.cleansweep.app.domain.scanner.hashing.HashSchemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The 3-stage exact duplicate pipeline on top of the index (PRD 4.2).
 *
 * | Stage | Question asked | How many files are read |
 * | --- | --- | --- |
 * | 1 | Does any other file have this byte size? | none: a query on the index |
 * | 2 | Do they also share their first and last 4 KB? | the files that share a size, and only their edges |
 * | 3 | Are they the same all the way through? | the files that share size and edges, whole |
 *
 * Each stage shrinks the set for the next one, so a file is read from beginning to end only when two cheap
 * checks already say it is a likely copy. A file whose hashes are already stored and current is skipped by
 * Stage 2 and Stage 3 alike, which is what makes a second scan cheap.
 */
class DuplicatePipeline(
    private val hasher: FileHasher,
    private val fileIndex: FileIndexRepository,
) {
    /**
     * Brings the stored hashes up to date and returns every group of byte-identical files.
     *
     * @param workers how many files may be hashed at once. Each one reads on the I/O dispatcher and digests on
     *   the hashing dispatcher; [workers] is what keeps a large batch from heating or draining the device.
     * @param gate the walk and the hashing wait here while the scan is paused.
     */
    suspend fun run(
        workers: Int,
        gate: PauseGate,
        tracker: ScanTracker,
    ): List<DuplicateGroup> {
        require(workers > 0) { "At least one hashing worker is needed, got $workers" }

        // Stage 1: a byte size that no other file has rules a file out without reading it.
        val sameSize = fileIndex.getSizeCollisionCandidates(MIN_DUPLICATE_SIZE_BYTES)

        // Stage 2.
        val needsPartial = sameSize.filter { !HashSchemes.isPartial(it.partialHash) }
        tracker.enterPhase(ScanPhase.PARTIAL_HASHING, filesToHash = needsPartial.size)
        hash(needsPartial, workers, gate, tracker, fileIndex::updatePartialHashes) { file ->
            hasher.partialHash(File(file.path))
        }

        // Stage 3.
        val sameHeadAndTail = fileIndex.getPartialHashCollisionCandidates()
        val needsFull = sameHeadAndTail.filter { !HashSchemes.isFull(it.fullHash) }
        tracker.enterPhase(ScanPhase.FULL_HASHING, filesToHash = needsFull.size)
        hash(needsFull, workers, gate, tracker, fileIndex::updateFullHashes) { file ->
            hasher.fullHash(File(file.path))
        }

        return fileIndex.getDuplicateGroups()
    }

    /**
     * Feeds [candidates] to [workers] coroutines through a queue, so that exactly that many files are in
     * flight whatever the size of the list. Results are written to the index in batches while the stage runs,
     * so a scan that is cancelled half-way keeps the work that was done.
     */
    private suspend fun hash(
        candidates: List<IndexedFile>,
        workers: Int,
        gate: PauseGate,
        tracker: ScanTracker,
        persist: suspend (List<FileHashUpdate>) -> Unit,
        hashOne: suspend (IndexedFile) -> HashOutcome,
    ) {
        val results = HashResults(persist)
        coroutineScope {
            val queue = Channel<IndexedFile>(capacity = workers)
            launch {
                try {
                    for (candidate in candidates) {
                        queue.send(candidate)
                    }
                } finally {
                    queue.close()
                }
            }
            repeat(workers) {
                launch {
                    for (candidate in queue) {
                        gate.awaitOpen()
                        ensureActive()
                        results.record(candidate, hashOne(candidate), tracker)
                    }
                }
            }
        }
        results.finish()
    }

    /** Collects what a stage found, from several coroutines at once, and writes it out in batches. */
    private inner class HashResults(
        private val persist: suspend (List<FileHashUpdate>) -> Unit,
    ) {
        private val lock = Mutex()
        private val updates = ArrayList<FileHashUpdate>()
        private val vanished = ArrayList<String>()

        suspend fun record(
            file: IndexedFile,
            outcome: HashOutcome,
            tracker: ScanTracker,
        ) {
            when (outcome) {
                is HashOutcome.Computed -> {
                    val batch = lock.withLock {
                        updates += FileHashUpdate(file.id, outcome.value)
                        if (updates.size >= PERSIST_EVERY) updates.takeAll() else null
                    }
                    // Written outside the lock, so the other workers keep collecting meanwhile.
                    if (batch != null) {
                        persist(batch)
                    }
                }

                // Deleted, or its volume ejected, since the walk saw it. Its row has to go, or the next
                // scan would report a file that is not there.
                HashOutcome.Vanished -> lock.withLock {
                    vanished += file.path
                }

                // Being written to, or locked by another app. Nothing is stored, so the next scan tries again.
                else -> tracker.error()
            }
            tracker.fileHashed()
        }

        /** Writes what is left over and takes the files that no longer exist out of the index. */
        suspend fun finish() {
            val batch = lock.withLock { updates.takeAll() }
            if (batch.isNotEmpty()) {
                persist(batch)
            }
            val gone = lock.withLock { vanished.takeAll() }
            if (gone.isNotEmpty()) {
                fileIndex.removeFiles(gone)
            }
        }

        private fun <T> MutableList<T>.takeAll(): List<T> = toList().also { clear() }
    }

    companion object {
        /**
         * Copies smaller than this are not looked for. They reclaim nothing worth showing, while identical
         * marker and configuration files are common inside app folders, where removing one can break an app.
         * The user's minimum file size setting can raise this further.
         */
        const val MIN_DUPLICATE_SIZE_BYTES: Long = 1_024L

        /** Hash results are written to the index this many at a time. */
        const val PERSIST_EVERY: Int = 500
    }
}
