package com.cleansweep.app.data.scanner.trash

import android.content.Context
import android.media.MediaScannerConnection
import com.cleansweep.app.domain.scanner.trash.DeleteResult
import com.cleansweep.app.domain.scanner.trash.MediaScanner
import com.cleansweep.app.domain.scanner.trash.MoveResult
import com.cleansweep.app.domain.scanner.trash.TrashStamp
import com.cleansweep.app.domain.scanner.trash.TrashStorage
import com.cleansweep.app.domain.scanner.walker.FileMetadataReader
import com.cleansweep.app.data.scanner.NioFileMetadataReader
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * [TrashStorage] on `java.nio`.
 *
 * A move is asked for as an atomic move. Renaming within a folder is atomic on every file system Android uses,
 * so an entry is always either at its old name or at its new one, never both or neither, and it is never copied.
 */
class NioTrashStorage(
    private val metadata: FileMetadataReader = NioFileMetadataReader(),
) : TrashStorage {

    override fun stamp(path: String): TrashStamp? =
        metadata.read(path)?.let { TrashStamp(it.isDirectory, it.sizeBytes, it.lastModified) }

    override fun ensureDirectory(path: String): Boolean = try {
        Files.createDirectories(Paths.get(path))
        true
    } catch (error: IOException) {
        false
    }

    override fun isEmptyDirectory(path: String): Boolean = try {
        val folder = Paths.get(path)
        Files.isDirectory(folder) && Files.newDirectoryStream(folder).use { !it.iterator().hasNext() }
    } catch (error: IOException) {
        false
    }

    override fun move(
        from: String,
        to: String,
    ): MoveResult = try {
        Files.move(Paths.get(from), Paths.get(to), StandardCopyOption.ATOMIC_MOVE)
        MoveResult.Moved
    } catch (error: IOException) {
        moveFailure(error)
    }

    // Deleting cannot be taken back, so each method first makes sure that what is at the path is the kind of
    // entry it is allowed to remove, whatever a caller concluded from an earlier look.
    override fun deleteFile(path: String): DeleteResult = try {
        val entry = Paths.get(path)
        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            DeleteResult.Missing
        } else {
            Files.deleteIfExists(entry)
            DeleteResult.Deleted
        }
    } catch (error: IOException) {
        deleteFailure(error)
    }

    override fun deleteEmptyDirectory(path: String): DeleteResult = try {
        val folder = Paths.get(path)
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            DeleteResult.Missing
        } else {
            Files.deleteIfExists(folder)
            DeleteResult.Deleted
        }
    } catch (error: DirectoryNotEmptyException) {
        DeleteResult.NotEmpty
    } catch (error: IOException) {
        deleteFailure(error)
    }
}

internal fun moveFailure(error: IOException): MoveResult = when {
    error is NoSuchFileException -> MoveResult.SourceMissing
    error is FileAlreadyExistsException -> MoveResult.TargetExists
    error.isOnReadOnlyStorage() -> MoveResult.ReadOnly
    else -> MoveResult.Failed(error)
}

internal fun deleteFailure(error: IOException): DeleteResult = when {
    error is NoSuchFileException -> DeleteResult.Missing
    error.isOnReadOnlyStorage() -> DeleteResult.ReadOnly
    else -> DeleteResult.Failed(error)
}

/**
 * Android reports a read-only volume, or a folder it keeps apps from changing, in one of two ways: as an access
 * error, or as a plain file system error whose reason names the read-only file system.
 */
private fun IOException.isOnReadOnlyStorage(): Boolean =
    this is AccessDeniedException ||
        (this is FileSystemException && reason?.contains("read-only", ignoreCase = true) == true)

/** Tells the media index about paths that were moved to or from Safe Trash. */
class AndroidMediaScanner(
    private val context: Context,
) : MediaScanner {

    override fun scan(paths: Collection<String>) {
        if (paths.isEmpty()) {
            return
        }
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }
}
