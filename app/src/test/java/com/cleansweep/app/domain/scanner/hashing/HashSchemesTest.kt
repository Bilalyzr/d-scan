package com.cleansweep.app.domain.scanner.hashing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class HashSchemesTest {

    private val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())

    @Test
    fun `a value is the scheme, a colon and 64 lowercase hex characters`() {
        val value = HashSchemes.encode(HashSchemes.FULL, digest)

        assertEquals(
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            value,
        )
        assertTrue(HashSchemes.isFull(value))
        assertFalse(HashSchemes.isPartial(value))
    }

    @Test
    fun `partial and full values are told apart by their scheme`() {
        val partial = HashSchemes.encode(HashSchemes.PARTIAL, digest)

        assertTrue(HashSchemes.isPartial(partial))
        assertFalse(HashSchemes.isFull(partial))
    }

    @Test
    fun `a value that is truncated, of another scheme or absent counts as stale for both schemes`() {
        val truncated = HashSchemes.encode(HashSchemes.FULL, digest).dropLast(1)
        val otherScheme = "md5:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

        for (stale in listOf(null, "", truncated, otherScheme)) {
            assertFalse("'$stale' must not count as a full hash", HashSchemes.isFull(stale))
            assertFalse("'$stale' must not count as a partial hash", HashSchemes.isPartial(stale))
        }
    }

    @Test
    fun `the partial chunks of a file of 8 KB or less are the whole file`() {
        assertEquals(2L * HashSchemes.PARTIAL_CHUNK_BYTES, HashSchemes.WHOLE_FILE_LIMIT_BYTES)
        assertEquals(4 * 1024, HashSchemes.PARTIAL_CHUNK_BYTES)
        assertEquals(64 * 1024, HashSchemes.STREAM_BUFFER_BYTES)
    }
}
