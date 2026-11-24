package com.cleansweep.app.domain.scanner.hashing

import java.io.File

/** What a file looked like at one moment: enough to tell whether it changed in between. */
data class FileStamp(
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * Reads the size and modification time of a file, or null when it does not exist.
 *
 * An interface so that tests can make a file "change" between two reads without a race against real I/O.
 */
fun interface FileStampReader {
    fun read(file: File): FileStamp?

    companion object {
        val DEFAULT: FileStampReader = FileStampReader { file ->
            if (file.exists()) FileStamp(file.length(), file.lastModified()) else null
        }
    }
}
