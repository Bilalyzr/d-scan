package com.cleansweep.app.data.scanner

import com.cleansweep.app.domain.scanner.walker.FileMetadata
import com.cleansweep.app.domain.scanner.walker.FileMetadataReader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * [FileMetadataReader] on top of `java.nio`, with one lookup per entry.
 *
 * The attributes are read without following links, so one call tells a symbolic link apart from the file or
 * folder it points to. A missing entry, or one the app may not look at, comes back as null.
 */
class NioFileMetadataReader : FileMetadataReader {

    override fun read(path: String): FileMetadata? = try {
        val attributes = Files.readAttributes(
            File(path).toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        FileMetadata(
            isDirectory = attributes.isDirectory,
            isRegularFile = attributes.isRegularFile,
            isSymbolicLink = attributes.isSymbolicLink,
            sizeBytes = attributes.size(),
            lastModified = attributes.lastModifiedTime().toMillis(),
            createdAt = attributes.creationTime().toMillis(),
        )
    } catch (error: IOException) {
        null
    }
}
