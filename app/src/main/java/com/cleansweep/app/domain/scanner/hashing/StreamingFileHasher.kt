package com.cleansweep.app.domain.scanner.hashing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.invoke
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [FileHasher] that never holds more than a few fixed buffers of a file in memory.
 *
 * Disk reads run on [readDispatcher] and the SHA-256 updates on [hashDispatcher]. For the full hash the two are
 * connected by a pipeline: the reader fills one of three reusable buffers and hands it to the digester, which
 * folds it in and hands it back. Reading (waiting on storage) and digesting (waiting on CPU) overlap, and a
 * 4 GB video costs exactly the same memory as a 4 KB note.
 *
 * Both dispatchers are injected: `Dispatchers.IO` and `Dispatchers.Default` in the app, test dispatchers in tests.
 */
class StreamingFileHasher(
    private val readDispatcher: CoroutineDispatcher,
    private val hashDispatcher: CoroutineDispatcher,
    private val stamps: FileStampReader = FileStampReader.DEFAULT,
    private val bufferBytes: Int = HashSchemes.STREAM_BUFFER_BYTES,
) : FileHasher {

    init {
        require(bufferBytes > 0) { "bufferBytes must be positive, was $bufferBytes" }
    }

    /**
     * Sets of read buffers reused by every full hash, so that hashing thousands of files allocates nothing per
     * chunk. A set is only offered again after the coroutine that used it has completely finished.
     */
    private val spareBuffers = ConcurrentLinkedQueue<BufferSet>()

    override suspend fun partialHash(file: File): HashOutcome {
        val before = stamps.read(file) ?: return HashOutcome.Vanished
        val chunks = try {
            readDispatcher {
                FileInputStream(file).use { input -> readHeadAndTail(input, before.sizeBytes) }
            }
        } catch (error: IOException) {
            return failure(file, error)
        } catch (error: SecurityException) {
            return failure(file, error)
        }
        val digest = hashDispatcher { digestOf(chunks) }
        return finish(HashSchemes.PARTIAL, digest, file, before)
    }

    override suspend fun fullHash(file: File): HashOutcome {
        val before = stamps.read(file) ?: return HashOutcome.Vanished
        val buffers = acquireBuffers()
        val digest = try {
            // coroutineScope returns only once the reader and the digester are both finished, so the buffers are
            // free by the time the `finally` below hands them to the next file.
            coroutineScope { readAndDigest(file, buffers) }
        } catch (error: IOException) {
            return failure(file, error)
        } catch (error: SecurityException) {
            return failure(file, error)
        } finally {
            releaseBuffers(buffers)
        }
        return finish(HashSchemes.FULL, digest, file, before)
    }

    /**
     * Streams [file] through SHA-256. `idle` holds the buffers the reader may fill; the reader takes one, reads
     * up to [bufferBytes] into it and passes it on through `loaded`; the digester folds a loaded buffer into the
     * digest and returns it to `idle`. The channel hand-over is what keeps the two sides from touching the same
     * buffer at the same time.
     */
    private suspend fun CoroutineScope.readAndDigest(file: File, buffers: BufferSet): ByteArray {
        val idle = Channel<Chunk>(buffers.chunks.size)
        val loaded = Channel<Chunk>(buffers.chunks.size)
        for (chunk in buffers.chunks) {
            idle.send(chunk)
        }

        val reader = launch(readDispatcher) {
            try {
                FileInputStream(file).use { input ->
                    while (true) {
                        ensureActive()
                        val chunk = idle.receive()
                        val read = input.read(chunk.buffer)
                        if (read < 0) {
                            break
                        }
                        chunk.count = read
                        loaded.send(chunk)
                    }
                }
                loaded.close()
            } catch (cause: Throwable) {
                // Hands the failure to the digester, which rethrows it once it has drained what was read.
                loaded.close(cause)
            }
        }

        val digest = newDigest()
        withContext(hashDispatcher) {
            for (chunk in loaded) {
                digest.update(chunk.buffer, 0, chunk.count)
                idle.send(chunk)
            }
        }
        reader.join()
        return digest.digest()
    }

    /** The first and the last 4 KB of a file, or the whole file when it is 8 KB or smaller. */
    private fun readHeadAndTail(input: FileInputStream, sizeBytes: Long): Array<ByteArray> {
        val chunkBytes = HashSchemes.PARTIAL_CHUNK_BYTES
        if (sizeBytes <= HashSchemes.WHOLE_FILE_LIMIT_BYTES) {
            val whole = ByteArray(sizeBytes.toInt())
            readUpTo(input, whole)
            return arrayOf(whole)
        }
        val head = ByteArray(chunkBytes)
        val headBytes = readUpTo(input, head)
        val tail = ByteArray(chunkBytes)
        input.channel.position(sizeBytes - chunkBytes)
        val tailBytes = readUpTo(input, tail)
        // A short read means the file shrank underneath us. Hash what was read; the stamp taken afterwards
        // then reports the change instead of letting this value through.
        return arrayOf(head.copyOf(headBytes), tail.copyOf(tailBytes))
    }

    /** Reads [buffer] full or until the end of the stream, and returns how many bytes were read. */
    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var done = 0
        while (done < buffer.size) {
            val read = input.read(buffer, done, buffer.size - done)
            if (read < 0) {
                break
            }
            done += read
        }
        return done
    }

    private fun digestOf(chunks: Array<ByteArray>): ByteArray {
        val digest = newDigest()
        for (chunk in chunks) {
            digest.update(chunk)
        }
        return digest.digest()
    }

    private fun failure(file: File, error: Exception): HashOutcome =
        if (stamps.read(file) == null) HashOutcome.Vanished else HashOutcome.Unreadable(error)

    /** Accepts a hash only if the file looks exactly as it did before it was read. */
    private fun finish(scheme: String, digest: ByteArray, file: File, before: FileStamp): HashOutcome {
        val after = stamps.read(file) ?: return HashOutcome.Vanished
        if (after != before) {
            return HashOutcome.ChangedWhileReading
        }
        return HashOutcome.Computed(HashSchemes.encode(scheme, digest))
    }

    private fun acquireBuffers(): BufferSet = spareBuffers.poll() ?: BufferSet(bufferBytes)

    private fun releaseBuffers(buffers: BufferSet) {
        if (spareBuffers.size < MAX_SPARE_BUFFER_SETS) {
            spareBuffers.add(buffers)
        }
    }

    private fun newDigest(): MessageDigest =
        try {
            MessageDigest.getInstance("SHA-256")
        } catch (error: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is required on every Android device", error)
        }

    private class Chunk(
        val buffer: ByteArray,
    ) {
        var count: Int = 0
    }

    private class BufferSet(
        bufferBytes: Int,
    ) {
        val chunks: Array<Chunk> = Array(POOLED_BUFFERS_PER_SET) { Chunk(ByteArray(bufferBytes)) }
    }

    private companion object {
        /** Three buffers per file in flight: one being read, one being digested, one in hand-over. */
        const val POOLED_BUFFERS_PER_SET = 3

        /** Sets kept for reuse: enough for four workers with slack. Retains about 1.5 MB at most. */
        const val MAX_SPARE_BUFFER_SETS = 8
    }
}
