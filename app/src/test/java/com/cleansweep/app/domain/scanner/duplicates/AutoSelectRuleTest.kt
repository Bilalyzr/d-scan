package com.cleansweep.app.domain.scanner.duplicates

import com.cleansweep.app.file
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which copy of a group each rule keeps. */
class AutoSelectRuleTest {

    private val oldest = file("/p/Download/report.pdf", lastModified = 100L, fullHash = "h")
    private val newest = file("/p/Documents/report.pdf", lastModified = 300L, fullHash = "h")
    private val shortest = file("/p/a.pdf", lastModified = 200L, fullHash = "h")
    private val copies = listOf(newest, shortest, oldest)

    @Test
    fun `each rule keeps the copy it names`() {
        assertEquals(oldest, AutoSelectRule.KEEP_OLDEST.keeperOf(copies))
        assertEquals(newest, AutoSelectRule.KEEP_NEWEST.keeperOf(copies))
        assertEquals(shortest, AutoSelectRule.KEEP_SHORTEST_PATH.keeperOf(copies))
    }

    @Test
    fun `copies of the same age are told apart by the shortest path, then by name`() {
        val one = file("/p/b.pdf", lastModified = 100L)
        val other = file("/p/a.pdf", lastModified = 100L)

        assertEquals("The same age, so the name that comes first", other, AutoSelectRule.KEEP_OLDEST.keeperOf(listOf(one, other)))
        assertEquals(other, AutoSelectRule.KEEP_NEWEST.keeperOf(listOf(one, other)))

        val nested = file("/p/deep/a.pdf", lastModified = 100L)
        assertEquals("The same age and name, so the shorter path", other, AutoSelectRule.KEEP_OLDEST.keeperOf(listOf(other, nested)))
    }

    @Test
    fun `paths of the same length are told apart by name`() {
        val one = file("/p/b.pdf", lastModified = 100L)
        val other = file("/p/a.pdf", lastModified = 300L)

        assertEquals(other, AutoSelectRule.KEEP_SHORTEST_PATH.keeperOf(listOf(one, other)))
    }

    @Test
    fun `the order of the copies does not matter`() {
        assertEquals(oldest, AutoSelectRule.KEEP_OLDEST.keeperOf(copies.reversed()))
        assertEquals(shortest, AutoSelectRule.KEEP_SHORTEST_PATH.keeperOf(copies.shuffled(java.util.Random(7))))
    }

    @Test
    fun `unticking all keeps no copy in particular`() {
        assertNull(AutoSelectRule.UNSELECT_ALL.keeperOf(copies))
    }

    @Test
    fun `a rule can be applied to a single copy`() {
        assertEquals(oldest, AutoSelectRule.KEEP_NEWEST.keeperOf(listOf(oldest)))
    }
}
