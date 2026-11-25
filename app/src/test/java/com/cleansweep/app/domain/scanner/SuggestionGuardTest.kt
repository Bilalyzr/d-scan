package com.cleansweep.app.domain.scanner

import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.file
import com.cleansweep.app.folder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionGuardTest {

    private val guard = SuggestionGuard()

    private fun rejection(
        path: String,
        category: SuggestionCategory,
        linkCount: Int = 1,
        locked: Boolean = false,
        sizeBytes: Long = 100L,
    ): Rejection? = guard.rejectionFor(file(path, sizeBytes = sizeBytes, isOriginalLocked = locked), category, linkCount)

    @Test
    fun `a copy the user locked is never suggested, whatever a detector says`() {
        for (category in SuggestionCategory.entries) {
            assertEquals("$category", Rejection.LOCKED_ORIGINAL, rejection("/r/Documents/a.pdf", category, locked = true))
        }
    }

    @Test
    fun `hidden entries are offered only as temporary junk`() {
        assertEquals(Rejection.HIDDEN_ENTRY, rejection("/r/DCIM/.nomedia", SuggestionCategory.ZERO_BYTE, sizeBytes = 0))
        assertEquals(Rejection.HIDDEN_ENTRY, rejection("/r/.thumbnails/a.jpg", SuggestionCategory.EXACT_DUPLICATE))
        assertEquals(Rejection.HIDDEN_ENTRY, rejection("/r/DCIM/.trashed-1700000000-photo.jpg", SuggestionCategory.LARGE_FORGOTTEN))
        assertEquals(Rejection.HIDDEN_ENTRY, rejection("/r/.cache/empty", SuggestionCategory.EMPTY_FOLDER))

        assertNull(rejection("/r/.thumbnails/thumb1", SuggestionCategory.TEMP_FILE))
        assertNull(rejection("/r/Download/.partial.tmp", SuggestionCategory.TEMP_FILE))
    }

    @Test
    fun `an empty folder with a system name is never suggested, a folder of the user's own may be`() {
        assertEquals(Rejection.SYSTEM_FOLDER, rejection("/r/a/b/DCIM", SuggestionCategory.EMPTY_FOLDER))
        assertEquals(Rejection.SYSTEM_FOLDER, rejection("/r/a/b/download", SuggestionCategory.EMPTY_FOLDER))
        assertNull(rejection("/r/a/b/Old Project", SuggestionCategory.EMPTY_FOLDER))

        // The system name of a folder means nothing for a file inside it.
        assertNull(rejection("/r/a/b/DCIM", SuggestionCategory.TEMP_FILE))
    }

    @Test
    fun `an entry that lies in Safe Trash is never suggested, whatever a detector says`() {
        val inTrash = ".cleansweep-trashed-0123456789abcdef-notes.log"

        assertEquals(Rejection.IN_TRASH, rejection(inTrash, SuggestionCategory.TEMP_FILE))
        assertEquals(Rejection.IN_TRASH, rejection("/r/Download/$inTrash", SuggestionCategory.EXACT_DUPLICATE))
        assertNull(rejection("/r/Download/notes.log", SuggestionCategory.TEMP_FILE))
    }

    @Test
    fun `an entry with several links to its content is never suggested`() {
        assertEquals(Rejection.HARD_LINKED, rejection("/r/Documents/shared.pdf", SuggestionCategory.EXACT_DUPLICATE, linkCount = 2))
        assertNull(rejection("/r/Documents/shared.pdf", SuggestionCategory.EXACT_DUPLICATE, linkCount = 1))
    }

    @Test
    fun `eligibility combines the rules with the link count read from storage`() {
        val hardLinked = SuggestionGuard(HardLinkReader { 4 })
        val path = "/r/Documents/a.pdf"

        assertTrue(guard.isEligible(file(path), SuggestionCategory.TEMP_FILE))
        assertFalse(hardLinked.isEligible(file(path), SuggestionCategory.TEMP_FILE))
        assertFalse(guard.isEligible(folder("/r/a/DCIM"), SuggestionCategory.EMPTY_FOLDER))
    }
}

class PauseGateTest {

    @Test
    fun `the gate is open unless it is closed`() {
        val gate = PauseGate()

        assertTrue(gate.isOpen)
        gate.close()
        assertFalse(gate.isOpen)
        gate.open()
        assertTrue(gate.isOpen)
    }

    @Test
    fun `a closed gate holds its caller until the gate is opened`() = runBlocking {
        val gate = PauseGate()
        gate.close()

        val waiting = async(Dispatchers.Default) {
            gate.awaitOpen()
            "through"
        }
        delay(150)
        assertFalse("the gate must still be holding", waiting.isCompleted)

        gate.open()

        assertEquals("through", withTimeout(5_000) { waiting.await() })
    }

    @Test
    fun `a gate that was opened before anyone waited lets the first caller straight through`() = runBlocking {
        val gate = PauseGate(initiallyOpen = false)
        gate.open()

        withTimeout(1_000) { gate.awaitOpen() }

        assertTrue(gate.isOpen)
    }
}
