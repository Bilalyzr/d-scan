package com.cleansweep.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FileCategoryTest {

    @Test
    fun `formats listed in the PRD map to their category`() {
        val expected = mapOf(
            "pdf" to FileCategory.DOCUMENT,
            "docx" to FileCategory.DOCUMENT,
            "xlsx" to FileCategory.DOCUMENT,
            "pptx" to FileCategory.DOCUMENT,
            "txt" to FileCategory.DOCUMENT,
            "csv" to FileCategory.DOCUMENT,
            "rtf" to FileCategory.DOCUMENT,
            "epub" to FileCategory.DOCUMENT,
            "jpg" to FileCategory.IMAGE,
            "heic" to FileCategory.IMAGE,
            "gif" to FileCategory.IMAGE,
            "mp4" to FileCategory.VIDEO,
            "mkv" to FileCategory.VIDEO,
            "mov" to FileCategory.VIDEO,
            "avi" to FileCategory.VIDEO,
            "mp3" to FileCategory.AUDIO,
            "m4a" to FileCategory.AUDIO,
            "aac" to FileCategory.AUDIO,
            "apk" to FileCategory.APK,
            "xapk" to FileCategory.APK,
            "zip" to FileCategory.ARCHIVE,
            "rar" to FileCategory.ARCHIVE,
            "7z" to FileCategory.ARCHIVE,
        )
        expected.forEach { (extension, category) ->
            assertEquals("extension $extension", category, FileCategory.fromExtension(extension))
        }
    }

    @Test
    fun `matching ignores case and a leading dot`() {
        assertEquals(FileCategory.DOCUMENT, FileCategory.fromExtension("PDF"))
        assertEquals(FileCategory.IMAGE, FileCategory.fromExtension("Jpg"))
    }

    @Test
    fun `log, tmp, unknown and empty extensions are OTHER so they are handled by junk detection instead`() {
        listOf("log", "tmp", "bak", "nomedia", "xyz", "").forEach { extension ->
            assertEquals("extension '$extension'", FileCategory.OTHER, FileCategory.fromExtension(extension))
        }
    }
}
