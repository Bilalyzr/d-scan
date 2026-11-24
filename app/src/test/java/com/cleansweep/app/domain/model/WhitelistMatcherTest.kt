package com.cleansweep.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitelistMatcherTest {

    private val matcher = WhitelistMatcher(
        files = listOf("/storage/emulated/0/Download/keep.pdf"),
        folders = listOf("/storage/emulated/0/Documents/Tax", "/storage/1234-5678"),
    )

    @Test
    fun `a file rule matches exactly that file`() {
        assertTrue(matcher.matches("/storage/emulated/0/Download/keep.pdf"))
        assertFalse(matcher.matches("/storage/emulated/0/Download/keep.pdf.bak"))
        assertFalse(matcher.matches("/storage/emulated/0/Download/keep"))
    }

    @Test
    fun `a folder rule matches the folder and everything below it`() {
        assertTrue(matcher.matches("/storage/emulated/0/Documents/Tax"))
        assertTrue(matcher.matches("/storage/emulated/0/Documents/Tax/2024/return.pdf"))
        assertTrue(matcher.matches("/storage/1234-5678/DCIM/IMG_0001.jpg"))
    }

    @Test
    fun `a folder rule never matches a sibling whose name merely starts the same way`() {
        assertFalse(matcher.matches("/storage/emulated/0/Documents/Taxes/return.pdf"))
        assertFalse(matcher.matches("/storage/emulated/0/Documents"))
        assertFalse(matcher.matches("/storage/emulated/0/Download/other.pdf"))
    }

    @Test
    fun `a trailing separator on a rule does not change what it covers`() {
        val withSlash = WhitelistMatcher(files = emptyList(), folders = listOf("/storage/emulated/0/Music/"))
        assertTrue(withSlash.matches("/storage/emulated/0/Music"))
        assertTrue(withSlash.matches("/storage/emulated/0/Music/song.mp3"))
        assertFalse(withSlash.matches("/storage/emulated/0/Music Videos/clip.mp4"))
    }

    @Test
    fun `the filesystem root covers everything`() {
        val root = WhitelistMatcher(files = emptyList(), folders = listOf("/"))
        assertTrue(root.matches("/storage/emulated/0/a.txt"))
        assertTrue(root.matches("/storage"))
        assertFalse(root.matches(""))
    }

    @Test
    fun `an empty matcher matches nothing`() {
        val empty = WhitelistMatcher(files = emptyList(), folders = emptyList())
        assertFalse(empty.matches("/storage/emulated/0/a.txt"))
    }

    @Test
    fun `builds from persisted rules`() {
        val fromRules = WhitelistMatcher.from(
            listOf(
                WhitelistRule(1L, "/storage/emulated/0/keep.txt", WhitelistRuleType.FILE, 0L),
                WhitelistRule(2L, "/storage/emulated/0/Work", WhitelistRuleType.FOLDER, 0L),
            ),
        )
        assertTrue(fromRules.matches("/storage/emulated/0/keep.txt"))
        assertTrue(fromRules.matches("/storage/emulated/0/Work/deep/file.docx"))
        assertFalse(fromRules.matches("/storage/emulated/0/keep.txt.orig"))
    }

    @Test
    fun `matching is case sensitive because Android storage is`() {
        assertFalse(matcher.matches("/storage/emulated/0/documents/tax/return.pdf"))
    }
}
