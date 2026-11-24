package com.cleansweep.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class IndexedFileTest {

    @Test
    fun `a folder placeholder carries no size and no hashes`() {
        val placeholder = IndexedFile(
            path = "/storage/emulated/0/Empty",
            name = "Empty",
            extension = "",
            mimeType = null,
            category = FileCategory.OTHER,
            sizeBytes = 0L,
            lastModified = 1L,
            createdAt = null,
            isDirectory = true,
        )
        assertNull(placeholder.partialHash)
        assertNull(placeholder.fullHash)

        assertThrows(IllegalArgumentException::class.java) { placeholder.copy(sizeBytes = 12L) }
        assertThrows(IllegalArgumentException::class.java) { placeholder.copy(fullHash = "sha256:abc") }
        assertThrows(IllegalArgumentException::class.java) { placeholder.copy(partialHash = "xxh64:1") }
    }

    @Test
    fun `sizes are never negative`() {
        assertThrows(IllegalArgumentException::class.java) {
            IndexedFile(
                path = "/storage/emulated/0/negative.bin",
                name = "negative.bin",
                extension = "bin",
                mimeType = null,
                category = FileCategory.OTHER,
                sizeBytes = -1L,
                lastModified = 1L,
                createdAt = null,
            )
        }
    }

    @Test
    fun `an observed file starts unlocked and without hashes`() {
        val observed = IndexedFile(
            path = "/storage/emulated/0/a.txt",
            name = "a.txt",
            extension = "txt",
            mimeType = null,
            category = FileCategory.DOCUMENT,
            sizeBytes = 3L,
            lastModified = 1L,
            createdAt = null,
        )
        assertEquals(0L, observed.id)
        assertNull(observed.partialHash)
        assertNull(observed.fullHash)
        assertFalse(observed.isOriginalLocked)
        assertFalse(observed.isDirectory)
    }
}

class DuplicateGroupTest {

    private fun group(vararg lastModified: Long): DuplicateGroup {
        val copies = lastModified.mapIndexed { index, modified ->
            IndexedFile(
                path = "/storage/emulated/0/file$index.bin",
                name = "file$index.bin",
                extension = "bin",
                mimeType = null,
                category = FileCategory.OTHER,
                sizeBytes = 500L,
                lastModified = modified,
                createdAt = null,
                fullHash = "sha256:abc",
            )
        }
        return DuplicateGroup(fullHash = "sha256:abc", copies = copies)
    }

    @Test
    fun `wasted bytes are every copy but one`() {
        assertEquals(500L, group(1L, 2L).wastedBytes)
        assertEquals(1_500L, group(1L, 2L, 3L, 4L).wastedBytes)
    }

    @Test
    fun `a lone file is not a duplicate group`() {
        assertThrows(IllegalArgumentException::class.java) { group(1L) }
    }
}
