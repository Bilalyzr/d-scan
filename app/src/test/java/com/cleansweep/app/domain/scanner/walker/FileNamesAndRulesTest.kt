package com.cleansweep.app.domain.scanner.walker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test
    fun `extension is lowercase and has no dot`() {
        assertEquals("jpg", FileNames.extensionOf("Photo.JPG"))
        assertEquals("gz", FileNames.extensionOf("archive.tar.gz"))
        assertEquals("nomedia", FileNames.extensionOf("important.nomedia"))
    }

    @Test
    fun `a file without an extension has an empty one`() {
        assertEquals("", FileNames.extensionOf("README"))
        assertEquals("", FileNames.extensionOf("README."))
        assertEquals("", FileNames.extensionOf(".nomedia"))
        assertEquals("", FileNames.extensionOf("."))
    }

    @Test
    fun `hidden means a leading dot on the entry or on any folder above it`() {
        assertTrue(FileNames.isHiddenPath("/r/.thumbnails/thumb1"))
        assertTrue(FileNames.isHiddenPath("/r/DCIM/.thumbnails"))
        assertTrue(FileNames.isHiddenPath("/r/photos/.nomedia"))
        assertTrue(FileNames.isHiddenPath("/r/DCIM/.trashed-1700000000-photo.jpg"))

        assertFalse(FileNames.isHiddenPath("/r/DCIM/photo.jpg"))
        assertFalse(FileNames.isHiddenPath("/r/a..b/file.txt"))
        assertFalse(FileNames.isHiddenPath("/r/DCIM/my.photo.jpg"))
    }
}

class ScanExclusionsTest {

    private val exclusions = ScanExclusions()
    private val root = "/storage/emulated/0"

    @Test
    fun `other apps' private folders are excluded wherever the storage root lies`() {
        assertTrue(exclusions.isExcluded("$root/Android/data", root))
        assertTrue(exclusions.isExcluded("$root/Android/obb/com.game/main.obb", root))
        assertTrue(exclusions.isExcluded("/storage/1234-5678/Android/data/com.app/files/db", "/storage/1234-5678"))
    }

    @Test
    fun `messaging media and names that merely look similar are not excluded`() {
        assertFalse(exclusions.isExcluded("$root/Android/media/com.whatsapp/WhatsApp/Media/x.jpg", root))
        assertFalse(exclusions.isExcluded("$root/Android", root))
        assertFalse(exclusions.isExcluded("$root/Documents/Android/data.docx", root))
        assertFalse(exclusions.isExcluded("$root/Android/database/notes.db", root))
        assertFalse(exclusions.isExcluded("$root/Android/media", root))
    }

    @Test
    fun `a path outside the given root is never matched`() {
        assertFalse(exclusions.isExcluded("/storage/9999/Android/data/com.app/x", root))
        assertFalse(exclusions.isExcluded("$root/other/Android/data", root))
    }

    @Test
    fun `the filesystem root works as a storage root`() {
        assertTrue(exclusions.isExcluded("/Android/data/com.app/x", "/"))
    }
}

class EmptyFolderRulesTest {

    @Test
    fun `the storage root and its direct children are never suggested`() {
        assertFalse(EmptyFolderRules.isSuggestable("/r", depthBelowRoot = 0))
        assertFalse(EmptyFolderRules.isSuggestable("/r/Whatever", depthBelowRoot = 1))
        assertTrue(EmptyFolderRules.isSuggestable("/r/Whatever/Inside", depthBelowRoot = 2))
    }

    @Test
    fun `folders with a system name are never suggested, at any depth and in any case`() {
        for (name in listOf("DCIM", "download", "Pictures", "SCREENSHOTS", "Lost.Dir", "Android")) {
            assertFalse("$name must be protected", EmptyFolderRules.isSuggestable("/r/a/b/$name", depthBelowRoot = 3))
        }
    }

    @Test
    fun `hidden folders are never suggested`() {
        assertFalse(EmptyFolderRules.isSuggestable("/r/a/.cache", depthBelowRoot = 2))
        assertFalse(EmptyFolderRules.isSuggestable("/r/a/b/.thumbnails/Leftovers", depthBelowRoot = 4))
    }

    @Test
    fun `of an empty tree only its top is kept, and look-alike prefixes are respected`() {
        val kept = EmptyFolderRules.topmost(
            listOf("/r/a/b/c", "/r/a/b", "/r/ab", "/r/x/y", "/r/x/y/z"),
        )

        assertEquals(listOf("/r/a/b", "/r/ab", "/r/x/y"), kept)
    }

    @Test
    fun `a folder the user plausibly made is suggested`() {
        assertTrue(EmptyFolderRules.isSuggestable("/r/Download/Holiday 2023", depthBelowRoot = 2))
        assertTrue(EmptyFolderRules.isSuggestable("/r/My Photos/Unsorted", depthBelowRoot = 2))
    }
}
