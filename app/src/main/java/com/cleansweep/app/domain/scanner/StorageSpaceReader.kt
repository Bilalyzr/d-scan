package com.cleansweep.app.domain.scanner

/** How much one storage volume holds, and how much of it an app may use. [root] is the volume's canonical path. */
data class VolumeSpace(
    val root: String,
    val totalBytes: Long,
    val freeBytes: Long,
)

/** Reads the size of the storage volumes that are mounted, for the storage gauge on the dashboard. */
fun interface StorageSpaceReader {

    /** One entry per volume that can be measured. A volume that cannot be read is left out. */
    fun volumes(): List<VolumeSpace>

    companion object {
        val NONE: StorageSpaceReader = StorageSpaceReader { emptyList() }
    }
}
