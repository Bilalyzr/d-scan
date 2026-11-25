package com.cleansweep.app.domain.scanner.duplicates

import com.cleansweep.app.file
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000
private const val NOW: Long = 1_800_000_000_000L

class LocationHeuristicsTest {

    @Test
    fun `any folder called Download or Downloads on the way counts, whatever its case`() {
        assertTrue(LocationHeuristics.isInDownloads("/storage/emulated/0/Download/a.pdf"))
        assertTrue(LocationHeuristics.isInDownloads("/storage/emulated/0/downloads/pdfs/DEEP/a.pdf"))
        assertTrue(LocationHeuristics.isInDownloads("/storage/emulated/0/DOWNLOAD/a.pdf"))
        assertTrue(LocationHeuristics.isInDownloads("/storage/1234-5678/Download/a.pdf"))
    }

    @Test
    fun `a name that merely contains the word is not a Downloads folder`() {
        assertFalse(LocationHeuristics.isInDownloads("/storage/emulated/0/Documents/MyDownloads/a.pdf"))
        assertFalse(LocationHeuristics.isInDownloads("/storage/emulated/0/Downloaded.bin"))
        assertFalse(LocationHeuristics.isInDownloads("/storage/emulated/0/Documents/a.pdf"))
    }

    @Test
    fun `the Sent folder of WhatsApp and Telegram media counts, received media does not`() {
        val whatsapp = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media"
        assertTrue(LocationHeuristics.isInMessagingSentFolder("$whatsapp/WhatsApp Sent/IMG-1.jpg"))
        assertTrue(LocationHeuristics.isInMessagingSentFolder("$whatsapp/WhatsApp Documents/Sent/notes.pdf"))

        val telegram = "/storage/emulated/0/Android/media/org.telegram.messenger/Telegram"
        assertTrue(LocationHeuristics.isInMessagingSentFolder("$telegram/Telegram Images/Sent/photo.jpg"))
        assertFalse(LocationHeuristics.isInMessagingSentFolder("$telegram/Telegram Images/photo.jpg"))
        assertFalse(LocationHeuristics.isInMessagingSentFolder("$whatsapp/WhatsApp Images/IMG-2.jpg"))
        assertFalse(LocationHeuristics.isInMessagingSentFolder("$whatsapp/IMG-3.jpg")) // directly in the app's folder
    }

    @Test
    fun `media WhatsApp left behind before apps had their own folders still counts`() {
        assertTrue(
            LocationHeuristics.isInMessagingSentFolder("/storage/emulated/0/WhatsApp/Media/WhatsApp Sent/IMG-9.jpg"),
        )
    }

    @Test
    fun `a folder called Sent outside a messaging app's media means nothing`() {
        assertFalse(LocationHeuristics.isInMessagingSentFolder("/storage/emulated/0/Documents/Sent/letter.pdf"))
        assertFalse(LocationHeuristics.isInMessagingSentFolder("/storage/emulated/0/WhatsApp Notes/Sent/a.txt"))
        assertFalse(LocationHeuristics.isInMessagingSentFolder("/storage/emulated/0/Download/Telegram tutorials/Sent/a.mp4"))
        assertFalse(LocationHeuristics.isInMessagingSentFolder("/storage/emulated/0/Android/media/com.other.app/files/sent.bin"))
    }
}

class OriginalLockPolicyTest {

    private fun copy(
        path: String,
        modifiedDaysAgo: Long,
        locked: Boolean = false,
    ) = file(path, lastModified = NOW - modifiedDaysAgo * DAY_MILLIS, isOriginalLocked = locked)

    @Test
    fun `the oldest copy outside Downloads is the Original`() {
        val oldest = copy("/storage/emulated/0/Documents/notes.pdf", modifiedDaysAgo = 400)
        val newer = copy("/storage/emulated/0/Backup/notes (1).pdf", modifiedDaysAgo = 10)

        assertSame(oldest, OriginalLockPolicy.selectOriginal(listOf(newer, oldest)))
    }

    @Test
    fun `a copy outside Downloads beats an older copy inside it`() {
        val inDownloads = copy("/storage/emulated/0/Download/notes.pdf", modifiedDaysAgo = 400)
        val elsewhere = copy("/storage/emulated/0/Documents/notes.pdf", modifiedDaysAgo = 2)

        assertSame(elsewhere, OriginalLockPolicy.selectOriginal(listOf(inDownloads, elsewhere)))
    }

    @Test
    fun `a copy in a messaging Sent folder counts as the worse location`() {
        val sent = copy("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Sent/v.mp4", 400)
        val received = copy("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/v.mp4", 2)

        assertSame(received, OriginalLockPolicy.selectOriginal(listOf(sent, received)))
    }

    @Test
    fun `a copy the user locked beats every other, wherever it lies and however new`() {
        val locked = copy("/storage/emulated/0/Download/notes.pdf", modifiedDaysAgo = 0, locked = true)
        val oldClean = copy("/storage/emulated/0/Documents/notes.pdf", modifiedDaysAgo = 400)

        assertSame(locked, OriginalLockPolicy.selectOriginal(listOf(oldClean, locked)))
    }

    @Test
    fun `copies that look the same are settled by path length and then alphabetically, always the same way`() {
        val longName = copy("/storage/emulated/0/a/notes-with-a-long-name.pdf", 10)
        val shortName = copy("/storage/emulated/0/bbbb/x.pdf", 10)

        assertSame(shortName, OriginalLockPolicy.selectOriginal(listOf(longName, shortName)))

        val z = copy("/storage/emulated/0/z/x.pdf", 10)
        val a = copy("/storage/emulated/0/a/x.pdf", 10)
        assertSame(a, OriginalLockPolicy.selectOriginal(listOf(z, a)))
        assertSame(a, OriginalLockPolicy.selectOriginal(listOf(a, z)))
    }

    @Test
    fun `the choice does not depend on the order the copies are given in`() {
        val first = copy("/storage/emulated/0/a/x.pdf", 10)
        val second = copy("/storage/emulated/0/b/y.pdf", 10)

        assertSame(
            OriginalLockPolicy.selectOriginal(listOf(first, second)),
            OriginalLockPolicy.selectOriginal(listOf(second, first)),
        )
    }

    @Test
    fun `a single copy is its own Original`() {
        val only = copy("/storage/emulated/0/x.pdf", 10)

        assertSame(only, OriginalLockPolicy.selectOriginal(listOf(only)))
        assertTrue(OriginalLockPolicy.redundantCopies(listOf(only)).isEmpty())
    }

    @Test
    fun `the redundant copies are everything except the Original, also before the rows have ids`() {
        val original = copy("/storage/emulated/0/Documents/notes.pdf", 400)
        val copyOne = copy("/storage/emulated/0/Download/notes.pdf", 300)
        val copyTwo = copy("/storage/emulated/0/Backup/notes.pdf", 200)
        listOf(original, copyOne, copyTwo).forEach { assertEquals(0L, it.id) }

        val redundant = OriginalLockPolicy.redundantCopies(listOf(copyTwo, original, copyOne))

        assertEquals(listOf(copyTwo, copyOne), redundant)
    }

    @Test
    fun `a group without copies is a bug rather than an Original`() {
        assertThrows(IllegalArgumentException::class.java) { OriginalLockPolicy.selectOriginal(emptyList()) }
    }
}
