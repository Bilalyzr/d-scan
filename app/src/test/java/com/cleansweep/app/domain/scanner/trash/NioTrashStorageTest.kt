package com.cleansweep.app.domain.scanner.trash

import com.cleansweep.app.data.scanner.trash.NioTrashStorage
import com.cleansweep.app.data.scanner.trash.deleteFailure
import com.cleansweep.app.data.scanner.trash.moveFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/** [TrashStorage] on real files and folders. */
class NioTrashStorageTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val storage = NioTrashStorage()

    private fun pathOf(relative: String): String = File(temporaryFolder.root, relative).path

    private fun file(
        relative: String,
        content: String = "content",
    ): File = File(temporaryFolder.root, relative).apply {
        parentFile!!.mkdirs()
        writeText(content)
    }

    private fun emptyFolder(relative: String): File = File(temporaryFolder.root, relative).apply {
        parentFile!!.mkdirs()
        mkdir()
    }

    @Test
    fun `a file moves to a path that is free`() {
        val file = file("Download/notes.log")
        val target = pathOf("Download/.moved")

        assertEquals(MoveResult.Moved, storage.move(file.path, target))

        assertFalse(file.exists())
        assertEquals("content", File(target).readText())
    }

    @Test
    fun `an empty folder moves like a file`() {
        val folder = emptyFolder("Download/Old Stuff")
        val target = pathOf("Download/.moved")

        assertEquals(MoveResult.Moved, storage.move(folder.path, target))

        assertFalse(folder.exists())
        assertTrue(File(target).isDirectory)
    }

    @Test
    fun `moving something that is not there is reported as such, and creates nothing`() {
        assertEquals(MoveResult.SourceMissing, storage.move(pathOf("missing.txt"), pathOf("target.txt")))

        assertFalse(File(pathOf("target.txt")).exists())
    }

    @Test
    fun `a file is deleted, and a file that is already gone is reported as missing`() {
        val file = file("Download/notes.log")

        assertEquals(DeleteResult.Deleted, storage.deleteFile(file.path))
        assertEquals(DeleteResult.Missing, storage.deleteFile(file.path))
    }

    @Test
    fun `a folder with something in it is left alone, and deleted once it is empty`() {
        val folder = file("Download/Old Stuff/letter.pdf").parentFile

        assertEquals(DeleteResult.NotEmpty, storage.deleteEmptyDirectory(folder!!.path))
        assertTrue(File(folder, "letter.pdf").exists())

        Files.delete(File(folder, "letter.pdf").toPath())

        assertEquals(DeleteResult.Deleted, storage.deleteEmptyDirectory(folder.path))
    }

    @Test
    fun `neither delete touches the other kind of entry`() {
        val file = file("Download/notes.log")
        val empty = emptyFolder("Download/Empty")

        assertEquals(DeleteResult.Missing, storage.deleteEmptyDirectory(file.path))
        assertEquals(DeleteResult.Missing, storage.deleteFile(empty.path))

        assertTrue(file.exists())
        assertTrue(empty.isDirectory)
    }

    @Test
    fun `folders are created where they are missing, and not where a file is in the way`() {
        val deep = pathOf("a/b/c")

        assertTrue(storage.ensureDirectory(deep))
        assertTrue(File(deep).isDirectory)

        val inTheWay = file("in-the-way.txt")
        assertFalse(storage.ensureDirectory(inTheWay.path))
        assertEquals("content", inTheWay.readText())
    }

    @Test
    fun `a stamp tells a folder from a file and is null for nothing`() {
        val file = file("Download/notes.log")
        val folder = emptyFolder("Download/Old Stuff")

        val stamp = storage.stamp(file.path)!!

        assertFalse(stamp.isDirectory)
        assertEquals(7L, stamp.sizeBytes)
        assertEquals(file.lastModified(), stamp.lastModified)
        assertTrue(storage.stamp(folder.path)!!.isDirectory)
        assertNull(storage.stamp(pathOf("nothing.txt")))
    }

    @Test
    fun `only an empty folder counts as empty`() {
        val empty = emptyFolder("Download/Empty")
        val full = file("Download/Full/letter.pdf").parentFile
        val file = file("Download/notes.log")

        assertTrue(storage.isEmptyDirectory(empty.path))
        assertFalse(storage.isEmptyDirectory(full!!.path))
        assertFalse(storage.isEmptyDirectory(file.path))
        assertFalse(storage.isEmptyDirectory(pathOf("nothing")))
    }

    @Test
    fun `read-only storage is told apart from every other failure`() {
        assertEquals(MoveResult.ReadOnly, moveFailure(AccessDeniedException("/a", "/b", "Permission denied")))
        assertEquals(MoveResult.ReadOnly, moveFailure(FileSystemException("/a", null, "Read-only file system")))
        assertEquals(MoveResult.SourceMissing, moveFailure(NoSuchFileException("/a")))
        assertEquals(MoveResult.TargetExists, moveFailure(FileAlreadyExistsException("/a", "/b", null)))
        assertTrue(moveFailure(IOException("I/O error")) is MoveResult.Failed)

        assertEquals(DeleteResult.ReadOnly, deleteFailure(AccessDeniedException("/a", null, "Permission denied")))
        assertEquals(DeleteResult.Missing, deleteFailure(NoSuchFileException("/a")))
        assertTrue(deleteFailure(IOException("I/O error")) is DeleteResult.Failed)
    }
}
