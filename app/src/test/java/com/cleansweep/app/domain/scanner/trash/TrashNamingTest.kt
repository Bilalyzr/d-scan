package com.cleansweep.app.domain.scanner.trash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashNamingTest {

    @Test
    fun `a trashed entry keeps its own name behind a hidden mark`() {
        val name = TrashNaming.trashedName("0123456789abcdef", "notes.log")

        assertEquals(".cleansweep-trashed-0123456789abcdef-notes.log", name)
        assertTrue(TrashNaming.isTrashedName(name))
    }

    @Test
    fun `only a name that starts with the whole mark is a trashed entry`() {
        val notTrashed = listOf(
            "",
            "notes.log",
            "report.cleansweep-trashed-1.pdf",
            ".cleansweep-trashed-",
            "cleansweep-trashed-1-notes.log",
        )
        for (name in notTrashed) {
            assertFalse("'$name'", TrashNaming.isTrashedName(name))
        }
    }

    @Test
    fun `a trashed entry rests in the folder it was deleted from`() {
        assertEquals(
            "/storage/emulated/0/Download/.cleansweep-trashed-1-notes.log",
            TrashNaming.trashedPath("/storage/emulated/0/Download/notes.log", token = "1"),
        )
    }

    @Test
    fun `tokens never repeat, so two entries of the same name never clash in one folder`() {
        val tokens = (1..2_000).map { TrashNaming.newToken() }

        assertEquals(tokens.size, tokens.toSet().size)
        assertTrue(tokens.all { it.length == 16 && it.all { character -> character in "0123456789abcdef" } })
    }

    @Test
    fun `a name that would be too long is shortened and keeps its extension`() {
        val name = TrashNaming.trashedName("0123456789abcdef", "x".repeat(250) + ".pdf")

        assertTrue(name.toByteArray(Charsets.UTF_8).size <= TrashNaming.MAX_NAME_BYTES)
        assertTrue(name.endsWith(".pdf"))
        assertTrue(TrashNaming.isTrashedName(name))
        assertTrue("the base must keep at least one character", !name.contains("-abcdef-.pdf"))
    }

    @Test
    fun `shortening never cuts a character in half`() {
        val name = TrashNaming.trashedName("0123456789abcdef", "😀".repeat(200) + ".jpg")

        assertTrue(name.toByteArray(Charsets.UTF_8).size <= TrashNaming.MAX_NAME_BYTES)
        assertTrue(name.endsWith(".jpg"))
        // An unpaired half of an emoji would not survive being turned into bytes and back.
        assertEquals(String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8), name)
    }

    @Test
    fun `a name without an extension is shortened as a whole`() {
        val name = TrashNaming.trashedName("0123456789abcdef", "y".repeat(250))

        assertTrue(name.toByteArray(Charsets.UTF_8).size <= TrashNaming.MAX_NAME_BYTES)
        assertTrue(name.length > TrashNaming.PREFIX.length + 17)
        assertNotEquals(".cleansweep-trashed-0123456789abcdef-", name)
    }

    @Test
    fun `an entry that cannot take its old name back gets one next to it`() {
        assertEquals("notes (restored).log", TrashNaming.restoredName("notes.log"))
        assertEquals("archive.tar (restored).gz", TrashNaming.restoredName("archive.tar.gz"))
        assertEquals("README (restored)", TrashNaming.restoredName("README"))
        assertEquals(".nomedia (restored)", TrashNaming.restoredName(".nomedia"))
        assertEquals("notes (restored 3).log", TrashNaming.restoredName("notes.log", attempt = 3))
    }

    @Test
    fun `a restored name is shortened too when it would be too long`() {
        val name = TrashNaming.restoredName("x".repeat(250) + ".pdf")

        assertTrue(name.toByteArray(Charsets.UTF_8).size <= TrashNaming.MAX_NAME_BYTES)
        assertTrue(name.endsWith(".pdf"))
        assertTrue(name.contains(" (restored)"))
    }
}
