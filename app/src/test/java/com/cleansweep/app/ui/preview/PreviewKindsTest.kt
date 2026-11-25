package com.cleansweep.app.ui.preview

import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.file
import org.junit.Assert.assertEquals
import org.junit.Test

/** How each kind of file can be shown. */
class PreviewKindsTest {

    private fun fileOf(
        name: String,
        category: FileCategory,
        size: Long = 1_000L,
    ) = file("/p/$name", sizeBytes = size, category = category)

    @Test
    fun `pictures, films, music and PDFs are shown as what they are`() {
        assertEquals(PreviewKind.IMAGE, PreviewKinds.of(fileOf("IMG_1.jpg", FileCategory.IMAGE, size = 40_000_000L)))
        assertEquals(PreviewKind.IMAGE, PreviewKinds.of(fileOf("portrait.webp", FileCategory.IMAGE)))
        assertEquals(PreviewKind.VIDEO, PreviewKinds.of(fileOf("clip.mp4", FileCategory.VIDEO)))
        assertEquals(PreviewKind.AUDIO, PreviewKinds.of(fileOf("song.mp3", FileCategory.AUDIO)))
        assertEquals(PreviewKind.PDF, PreviewKinds.of(fileOf("report.pdf", FileCategory.DOCUMENT, size = 9_000_000L)))
    }

    @Test
    fun `a text file is shown as text, up to a size`() {
        assertEquals(PreviewKind.TEXT, PreviewKinds.of(fileOf("notes.txt", FileCategory.DOCUMENT)))
        assertEquals(PreviewKind.TEXT, PreviewKinds.of(fileOf("setup.log", FileCategory.OTHER)))
        assertEquals(PreviewKind.TEXT, PreviewKinds.of(fileOf("data.json", FileCategory.OTHER)))
        assertEquals(PreviewKind.TEXT, PreviewKinds.of(fileOf("big.txt", FileCategory.DOCUMENT, size = PreviewKinds.TEXT_MAX_FILE_BYTES)))
        assertEquals(
            "Too large to be read as text",
            PreviewKind.NONE,
            PreviewKinds.of(fileOf("huge.txt", FileCategory.DOCUMENT, size = PreviewKinds.TEXT_MAX_FILE_BYTES + 1)),
        )
    }

    @Test
    fun `anything else has no preview`() {
        assertEquals(PreviewKind.NONE, PreviewKinds.of(fileOf("table.xlsx", FileCategory.DOCUMENT)))
        assertEquals(PreviewKind.NONE, PreviewKinds.of(fileOf("app.apk", FileCategory.APK)))
        assertEquals(PreviewKind.NONE, PreviewKinds.of(fileOf("photos.zip", FileCategory.ARCHIVE)))
        assertEquals(PreviewKind.NONE, PreviewKinds.of(fileOf("bin.dat", FileCategory.OTHER)))
        assertEquals(PreviewKind.NONE, PreviewKinds.of(com.cleansweep.app.folder("/p/Empty")))
    }
}
