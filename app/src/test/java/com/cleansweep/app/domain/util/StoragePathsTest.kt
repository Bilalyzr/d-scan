package com.cleansweep.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class StoragePathsTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `canonicalize strips trailing separators, dot segments and uses forward slashes`() {
        val base = StoragePaths.canonicalize(temporaryFolder.root.absolutePath)
        val messy = temporaryFolder.root.absolutePath + File.separator + "child" + File.separator + ".." + File.separator + "tax" + File.separator
        assertEquals("$base/tax", StoragePaths.canonicalize(messy))
        assertEquals(base, StoragePaths.canonicalize(base))
    }

    @Test
    fun `canonicalize follows symlinks so an alias collapses onto the real path`() {
        val real = temporaryFolder.newFolder("real")
        val link = File(temporaryFolder.root, "link")
        try {
            Files.createSymbolicLink(link.toPath(), real.toPath())
        } catch (e: IOException) {
            // Creating symlinks needs a privilege this machine may not grant (e.g. Windows without Developer Mode).
            assumeNoException(e)
        }
        val expected = StoragePaths.canonicalize(real.absolutePath) + "/inside.txt"
        assertEquals(expected, StoragePaths.canonicalize(link.absolutePath + File.separator + "inside.txt"))
    }

    @Test
    fun `canonicalize rejects a blank path rather than resolving it to the working directory`() {
        assertThrows(IllegalArgumentException::class.java) { StoragePaths.canonicalize("   ") }
    }

    @Test
    fun `descendantPrefix drives the SQL prefix test`() {
        assertEquals("/a/b/", StoragePaths.descendantPrefix("/a/b"))
        assertEquals("/a/b/", StoragePaths.descendantPrefix("/a/b/"))
        assertEquals("/", StoragePaths.descendantPrefix("/"))
    }

    @Test
    fun `isSameOrDescendant matches the root and paths below it but not look-alikes`() {
        val root = "/storage/emulated/0/Download"
        assertTrue(StoragePaths.isSameOrDescendant("/storage/emulated/0/Download", root))
        assertTrue(StoragePaths.isSameOrDescendant("/storage/emulated/0/Download/a.zip", root))
        assertTrue(StoragePaths.isSameOrDescendant("/storage/emulated/0/Download/deep/a.zip", root))
        assertFalse(StoragePaths.isSameOrDescendant("/storage/emulated/0/Downloads/a.zip", root))
        assertFalse(StoragePaths.isSameOrDescendant("/storage/emulated/0", root))

        val filesystemRoot = "/"
        assertTrue(StoragePaths.isSameOrDescendant("/storage/emulated/0/Download", filesystemRoot))
    }

    @Test
    fun `a path is split into its folder and its own name`() {
        assertEquals("notes.pdf", StoragePaths.nameOf("/storage/emulated/0/Documents/notes.pdf"))
        assertEquals("/storage/emulated/0/Documents", StoragePaths.parentOf("/storage/emulated/0/Documents/notes.pdf"))
        assertEquals("/storage/emulated/0", StoragePaths.parentOf("/storage/emulated/0/Documents"))
        assertEquals("/", StoragePaths.parentOf("/storage"))
        assertNull(StoragePaths.parentOf("notes.pdf"))
        assertEquals("", StoragePaths.nameOf("/"))
    }

    @Test
    fun `joining adds exactly one separator`() {
        assertEquals("/a/b/c.txt", StoragePaths.join("/a/b", "c.txt"))
        assertEquals("/c.txt", StoragePaths.join("/", "c.txt"))
        assertEquals("/a/b/c.txt", StoragePaths.join("/a/b/", "c.txt"))
    }
}
