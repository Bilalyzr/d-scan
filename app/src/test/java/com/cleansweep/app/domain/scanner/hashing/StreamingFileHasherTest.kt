package com.cleansweep.app.domain.scanner.hashing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

class StreamingFileHasherTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val random = Random(42)

    private fun hasher(
        bufferBytes: Int = HashSchemes.STREAM_BUFFER_BYTES,
        stamps: FileStampReader = FileStampReader.DEFAULT,
        hashDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) = StreamingFileHasher(Dispatchers.IO, hashDispatcher, stamps, bufferBytes)

    private fun newFile(name: String, content: ByteArray): File =
        File(temporaryFolder.root, name).apply { writeBytes(content) }

    private fun bytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun sha256(content: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(content)

    private fun expected(content: ByteArray): String = HashSchemes.encode(HashSchemes.FULL, sha256(content))

    private fun hashed(outcome: HashOutcome): String = (outcome as HashOutcome.Computed).value

    // ---- Full hash ----

    @Test
    fun `full hash is the SHA-256 of the whole content`() = runBlocking {
        val file = newFile("abc.bin", "abc".toByteArray())

        val outcome = hasher().fullHash(file)

        assertEquals(
            HashOutcome.Computed("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            outcome,
        )
    }

    @Test
    fun `full hash is right at every buffer boundary`() = runBlocking {
        val buffer = 64
        val hasher = hasher(bufferBytes = buffer)

        for (size in listOf(0, 1, buffer - 1, buffer, buffer + 1, 2 * buffer, 5 * buffer + 13)) {
            val content = bytes(size)

            val outcome = hasher.fullHash(newFile("size-$size.bin", content))

            assertEquals("size $size", HashOutcome.Computed(expected(content)), outcome)
        }
    }

    @Test
    fun `full hash of a multi-megabyte file is right with the real 64 KB buffer`() = runBlocking {
        val content = bytes(5 * 1024 * 1024 + 123)

        val outcome = hasher().fullHash(newFile("big.bin", content))

        assertEquals(HashOutcome.Computed(expected(content)), outcome)
    }

    // ---- Partial hash (Stage 2) ----

    @Test
    fun `partial hash covers the first and the last 4 KB and nothing in between`() = runBlocking {
        val base = bytes(100 * 1024)
        fun variant(name: String, mutate: (ByteArray) -> Unit): File {
            val copy = base.copyOf()
            mutate(copy)
            return newFile(name, copy)
        }
        val reference = variant("reference.bin") {}
        val sameHeadAndTail = variant("middle.bin") { it[50 * 1024] = (it[50 * 1024] + 1).toByte() }
        val otherHead = variant("head.bin") { it[0] = (it[0] + 1).toByte() }
        val otherTail = variant("tail.bin") { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val hasher = hasher()

        val referenceHash = hashed(hasher.partialHash(reference))

        assertEquals(referenceHash, hashed(hasher.partialHash(sameHeadAndTail)))
        assertNotEquals(referenceHash, hashed(hasher.partialHash(otherHead)))
        assertNotEquals(referenceHash, hashed(hasher.partialHash(otherTail)))
        assertTrue(referenceHash.startsWith(HashSchemes.PARTIAL + ":"))
        // The partial hash is a different value from the full one, so the two columns can never be confused.
        assertNotEquals(referenceHash, hashed(hasher.fullHash(reference)))
    }

    @Test
    fun `a file of 8 KB or less is hashed whole by the partial stage`() = runBlocking {
        val hasher = hasher()

        for (size in listOf(0, 1, 4 * 1024, 8 * 1024)) {
            val content = bytes(size)
            val partial = hashed(hasher.partialHash(newFile("small-$size.bin", content)))

            assertEquals("size $size", expected(content), partial.replace(HashSchemes.PARTIAL, HashSchemes.FULL))
        }
    }

    @Test
    fun `one byte above 8 KB the middle is no longer part of the partial hash`() = runBlocking {
        val first = bytes(8 * 1024 + 1)
        val second = first.copyOf().also { it[4 * 1024] = (it[4 * 1024] + 1).toByte() }
        val hasher = hasher()

        assertEquals(hashed(hasher.partialHash(newFile("a.bin", first))), hashed(hasher.partialHash(newFile("b.bin", second))))
        assertNotEquals(expected(first), expected(second))
    }

    // ---- Files that change or disappear ----

    @Test
    fun `a file that no longer exists has vanished`() = runBlocking {
        val missing = File(temporaryFolder.root, "missing.bin")

        assertEquals(HashOutcome.Vanished, hasher().fullHash(missing))
        assertEquals(HashOutcome.Vanished, hasher().partialHash(missing))
    }

    @Test
    fun `a file that cannot be read is reported as unreadable instead of being given a hash`() = runBlocking {
        val directory = temporaryFolder.newFolder("not-a-file")

        val outcome = hasher().fullHash(directory)

        assertTrue(outcome.toString(), outcome is HashOutcome.Unreadable)
        assertTrue(hasher().partialHash(directory) is HashOutcome.Unreadable)
    }

    @Test
    fun `a file that changes while it is read is not reported as hashed`() = runBlocking {
        val file = newFile("changing.bin", bytes(200 * 1024))
        val real = FileStampReader.DEFAULT
        // Each hash takes two stamps: odd reads, before hashing, see the real file; even reads see another one.
        val reads = AtomicInteger()
        val changing = FileStampReader { probed ->
            if (probed === file && reads.incrementAndGet() % 2 == 0) FileStamp(1L, 2L) else real.read(probed)
        }
        val hasher = hasher(stamps = changing)

        assertEquals(HashOutcome.ChangedWhileReading, hasher.fullHash(file))
        assertEquals(HashOutcome.ChangedWhileReading, hasher.partialHash(file))
    }

    // ---- Cancellation and concurrency ----

    @Test
    fun `cancelling a full hash stops it and leaves the buffers usable`() = runBlocking {
        val digesting = HoldingDispatcher()
        val hasher = hasher(hashDispatcher = digesting)
        val huge = File(temporaryFolder.root, "huge.bin")
        RandomAccessFile(huge, "rw").use { it.setLength(256L * 1024 * 1024) }

        // On its own thread pool: runBlocking has one thread, and this test blocks it while waiting below.
        val job = launch(Dispatchers.Default) { hasher.fullHash(huge) }
        assertTrue("digesting never started", digesting.firstTask.await(10, TimeUnit.SECONDS))
        job.cancel()
        digesting.release()
        withTimeout(10_000) { job.join() }

        assertTrue(job.isCancelled)
        // The cancelled hash must have handed its buffers back intact.
        val content = bytes(300 * 1024)
        assertEquals(HashOutcome.Computed(expected(content)), hasher().fullHash(newFile("after.bin", content)))
    }

    @Test
    fun `many full hashes running at the same time each get their own file's hash`() = runBlocking {
        val hasher = hasher(bufferBytes = 1024)
        val contents = (1..12).map { bytes(it * 4096 + it) }
        val files = contents.mapIndexed { index, content -> newFile("parallel-$index.bin", content) }

        val outcomes = files.map { file -> async(Dispatchers.Default) { hasher.fullHash(file) } }.awaitAll()

        assertEquals(contents.map { HashOutcome.Computed(expected(it)) }, outcomes)
    }
}

/**
 * A dispatcher that holds every task until [release] is called. It lets a test freeze a hash between reading
 * and digesting, which is the only moment a large file is reliably still being processed.
 */
private class HoldingDispatcher : CoroutineDispatcher() {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val released = AtomicBoolean(false)
    val firstTask = CountDownLatch(1)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.add(block)
        firstTask.countDown()
        if (released.get()) {
            drain()
        }
    }

    fun release() {
        released.set(true)
        drain()
    }

    private fun drain() {
        while (true) {
            val task = queue.poll() ?: return
            Dispatchers.Default.dispatch(EmptyCoroutineContext, task)
        }
    }
}
