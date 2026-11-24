package com.cleansweep.app.domain.scanner.hashing

import java.io.File

/** What became of an attempt to hash one file. */
sealed interface HashOutcome {
    /** The file was hashed. [value] is `<scheme>:<hex>`, see [HashSchemes]. */
    data class Computed(val value: String) : HashOutcome

    /** The file is gone: deleted, or its volume was ejected, since the scan saw it. */
    data object Vanished : HashOutcome

    /** The file changed while it was read, so the hash would describe no real state of it. */
    data object ChangedWhileReading : HashOutcome

    /** The file exists but could not be read: permission, I/O error, a locked file. */
    data class Unreadable(val cause: Exception) : HashOutcome
}

/**
 * The two hashes of the 3-stage duplicate pipeline. Both are main-safe.
 *
 * Every call checks that the file still has the same size and modification time after it was read as before. A
 * file that is being written to is therefore never given a hash, instead of being given a wrong one.
 */
interface FileHasher {
    /**
     * Stage 2: a hash over the first and the last 4 KB of [file], enough to tell apart most files that merely
     * share a byte size. A file of 8 KB or less is hashed whole.
     */
    suspend fun partialHash(file: File): HashOutcome

    /** Stage 3: SHA-256 over the whole content of [file], read through a fixed-size buffer. */
    suspend fun fullHash(file: File): HashOutcome
}
