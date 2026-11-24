package com.cleansweep.app.domain.model

/**
 * Every indexed copy of one exact content hash (full SHA-256).
 *
 * A group always has at least two members; groups of one are not duplicates and never leave the repository.
 * Which copy is the Original is decided by the lock policy in Phase 2, not stored here.
 */
data class DuplicateGroup(
    val fullHash: String,
    val copies: List<IndexedFile>,
) {
    init {
        require(copies.size >= 2) { "A duplicate group needs at least two copies ($fullHash)" }
    }

    val sizeBytes: Long
        get() = copies.first().sizeBytes

    /** Bytes that would be freed if every copy but one were removed. */
    val wastedBytes: Long
        get() = sizeBytes * (copies.size - 1)
}
