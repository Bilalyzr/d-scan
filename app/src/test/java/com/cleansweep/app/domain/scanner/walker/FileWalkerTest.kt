package com.cleansweep.app.domain.scanner.walker

import com.cleansweep.app.data.scanner.NioFileMetadataReader
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.WhitelistMatcher
import com.cleansweep.app.domain.scanner.PauseGate
import com.cleansweep.app.domain.scanner.trash.TrashNaming
import com.cleansweep.app.domain.util.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class FileWalkerTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var root: String
    private val openGate = PauseGate()
    private val walker = FileWalker(NioFileMetadataReader())

    @Before
    fun setUp() {
        root = StoragePaths.canonicalize(temporaryFolder.root.absolutePath)
    }

    private fun folder(relative: String): Unit {
        File(root, relative).mkdirs()
    }

    private fun write(relative: String, content: ByteArray = ByteArray(0)): File =
        File(root, relative).apply {
            parentFile?.mkdirs()
            writeBytes(content)
        }

    private fun request(
        matcher: WhitelistMatcher = WhitelistMatcher(emptyList(), emptyList()),
        minFileSizeBytes: Long = 0L,
        categories: Set<FileCategory> = emptySet(),
        detectEmptyFolders: Boolean = true,
        roots: List<String> = listOf(root),
    ) = WalkRequest(roots, matcher, minFileSizeBytes, categories, detectEmptyFolders)

    private open class RecordingSink : FileSink {
        val files = CopyOnWriteArrayList<IndexedFile>()
        val folders = CopyOnWriteArrayList<String>()
        val sizesSeen = CopyOnWriteArrayList<Long>()

        override fun onFileSeen(sizeBytes: Long) {
            sizesSeen += sizeBytes
        }

        override suspend fun onFile(file: IndexedFile) {
            files += file
        }

        override fun onFolder(path: String) {
            folders += path
        }
    }

    private suspend fun walk(
        request: WalkRequest = request(),
        gate: PauseGate = openGate,
        sink: RecordingSink = RecordingSink(),
    ): Pair<WalkResult, RecordingSink> = walker.walk(request, gate, sink) to sink

    private fun String.at(): String = "$root/$this"

    @Test
    fun `every file below the root is reported with its canonical path, size, times and category`() = runBlocking {
        val photo = write("DCIM/Camera/Photo.JPG", ByteArray(1_024))
        write("Documents/notes.pdf", ByteArray(10))
        write("Music/.nomedia")

        val (result, sink) = walk()

        assertEquals(listOf("DCIM/Camera/Photo.JPG".at(), "Documents/notes.pdf".at(), "Music/.nomedia".at()), sink.files.map { it.path })
        val indexed = sink.files.first { it.name == "Photo.JPG" }
        assertEquals("Photo.JPG", indexed.name)
        assertEquals("jpg", indexed.extension)
        assertEquals(FileCategory.IMAGE, indexed.category)
        assertEquals(1_024L, indexed.sizeBytes)
        assertEquals(photo.lastModified(), indexed.lastModified)
        assertEquals(0L, indexed.id)
        assertEquals(3L, result.filesSeen)
        assertEquals(3L, result.filesIndexed)
        assertEquals(1_034L, result.bytesSeen)
        assertEquals(listOf(root), result.rootsWalked)
        // Files are reported as seen whether or not they end up indexed.
        assertEquals(sink.files.map { it.sizeBytes }, sink.sizesSeen)
        assertEquals(0, result.unreadableRoots)
        assertEquals(0, result.unreadableFolders)
        assertEquals(0, result.symlinksSkipped)
    }

    @Test
    fun `the minimum size and the category filter decide what is indexed`() = runBlocking {
        write("a/big.bin", ByteArray(2_000))
        write("a/small.bin", ByteArray(10))
        write("a/photo.jpg", ByteArray(2_000))

        val (result, sink) = walk(request(minFileSizeBytes = 1_000, categories = setOf(FileCategory.IMAGE)))

        assertEquals(listOf("a/photo.jpg".at()), sink.files.map { it.path })
        assertEquals(3L, result.filesSeen)
        assertEquals(1L, result.filesIndexed)
        assertEquals(4_010L, result.bytesSeen)
    }

    @Test
    fun `a whitelisted folder is never entered, and it keeps its own parent from looking empty`() = runBlocking {
        folder("Work/Tax/2024") // an empty tree inside a whitelisted folder
        folder("Projects/Client/Archive") // an empty tree that holds only a whitelisted subfolder
        folder("Mine/Old Stuff")
        val matcher = WhitelistMatcher(
            files = emptyList(),
            folders = listOf("Work".at(), "Projects/Client".at()),
        )

        val (result, sink) = walk(request(matcher))

        assertFalse(sink.folders.any { it == "Work".at() || it.startsWith("Work/".at()) })
        assertFalse(sink.folders.any { it.startsWith("Projects/Client".at()) })
        // "Projects" holds a protected subfolder, so whether it is empty is not for the scan to say.
        assertEquals(listOf("Mine/Old Stuff".at()), result.emptyFolders.map { it.path })
    }

    @Test
    fun `a whitelisted file is not indexed and does not hide the empty folder next to it`() = runBlocking {
        write("Docs/keep.bin")
        folder("Docs/Empty")
        val matcher = WhitelistMatcher(files = listOf("Docs/keep.bin".at()), folders = emptyList())

        val (result, sink) = walk(request(matcher))

        assertTrue(sink.files.isEmpty())
        assertEquals(listOf("Docs/Empty".at()), result.emptyFolders.map { it.path })
    }

    @Test
    fun `other apps' private folders are skipped while messaging media is walked`() = runBlocking {
        write("Android/data/com.app/cache.bin")
        write("Android/obb/com.game/main.obb")
        write("Android/media/com.whatsapp/WhatsApp/Media/IMG.jpg")

        val (_, sink) = walk()

        assertEquals(listOf("Android/media/com.whatsapp/WhatsApp/Media/IMG.jpg".at()), sink.files.map { it.path })
    }

    @Test
    fun `an empty tree is reported once, at its highest folder that may be suggested`() = runBlocking {
        folder("Download/MyStuff/Inner")
        folder("Download/Other")
        folder("Pictures/Screenshots/Temp")
        folder(".hidden/empty")
        write("Full/file.txt")

        val (result, _) = walk()

        assertEquals(
            listOf("Download/MyStuff".at(), "Download/Other".at(), "Pictures/Screenshots/Temp".at()),
            result.emptyFolders.map { it.path }.sorted(),
        )
        assertTrue(result.emptyFolders.all { it.isDirectory && it.sizeBytes == 0L && it.category == FileCategory.OTHER })
        assertTrue(result.emptyFolders.all { it.name.isNotEmpty() && it.extension.isEmpty() })
    }

    @Test
    fun `empty folders are only worked out when the scan asks for them`() = runBlocking {
        folder("a/b/c")

        val (result, _) = walk(request(detectEmptyFolders = false))

        assertTrue(result.emptyFolders.isEmpty())
    }

    @Test
    fun `a file too small to index still makes its folder non-empty`() = runBlocking {
        write("a/b/tiny.dat", ByteArray(1))

        val (result, sink) = walk(request(minFileSizeBytes = 1_000))

        assertTrue(sink.files.isEmpty())
        assertEquals(1L, result.filesSeen)
        assertTrue(result.emptyFolders.isEmpty())
    }

    @Test
    fun `a root that cannot be read is counted and the walk carries on with the next`() = runBlocking {
        write("here/a.bin")

        val (result, sink) = walk(request(roots = listOf("missing".at(), root)))

        assertEquals(1, result.unreadableRoots)
        assertEquals(listOf(root), result.rootsWalked)
        assertEquals(listOf("here/a.bin".at()), sink.files.map { it.path })
    }

    @Test
    fun `entries of Safe Trash are skipped, but they keep their folder from looking empty`() = runBlocking {
        write("Download/notes.log")
        write("Download/${TrashNaming.trashedName("0123456789abcdef", "photo.jpg")}", ByteArray(50))
        folder("Pictures/${TrashNaming.trashedName("0123456789abcdef", "Old")}")

        val (result, sink) = walk()

        assertEquals(listOf("Download/notes.log".at()), sink.files.map { it.path })
        assertEquals(1L, result.filesSeen)
        assertEquals("a folder that holds only a trashed entry is not empty", emptyList<IndexedFile>(), result.emptyFolders)
    }

    @Test
    fun `a symbolic link is not followed and does not count as an ordinary file`() = runBlocking {
        val real = File(root, "real.bin").apply { writeBytes(ByteArray(5)) }
        val link = File(root, "link.bin")
        try {
            Files.createSymbolicLink(link.toPath(), real.toPath())
        } catch (error: IOException) {
            // Needs Developer Mode or an elevated shell on Windows.
            assumeNoException(error)
        }

        val (result, sink) = walk()

        assertEquals(listOf("real.bin".at()), sink.files.map { it.path })
        assertEquals(1, result.symlinksSkipped)
    }

    @Test
    fun `the walk waits at a closed gate and carries on where it stopped once the gate opens`() = runBlocking {
        repeat(5) { write("f$it/a.bin", ByteArray(1)) }
        val gate = PauseGate()
        val sink = object : RecordingSink() {
            override fun onFolder(path: String) {
                super.onFolder(path)
                if (folders.size == 2) gate.close()
            }
        }

        val job = launch(Dispatchers.Default) { walker.walk(request(), gate, sink) }
        withTimeout(5_000) { while (sink.folders.size < 2) delay(10) }
        val reportedWhilePaused = sink.files.size
        delay(150)
        assertEquals(reportedWhilePaused, sink.files.size)

        gate.open()
        withTimeout(5_000) { job.join() }

        assertEquals(5, sink.files.size)
        assertFalse(job.isCancelled)
    }

    @Test
    fun `cancelling a paused walk ends it promptly`() = runBlocking {
        repeat(5) { write("f$it/a.bin", ByteArray(1)) }
        val gate = PauseGate()
        val sink = object : RecordingSink() {
            override fun onFolder(path: String) {
                super.onFolder(path)
                if (folders.size == 2) gate.close()
            }
        }
        val job = launch(Dispatchers.Default) { walker.walk(request(), gate, sink) }
        withTimeout(5_000) { while (sink.folders.size < 2) delay(10) }

        job.cancel()
        withTimeout(5_000) { job.join() }

        assertTrue(job.isCancelled)
        assertEquals(0, sink.files.size)
    }
}
