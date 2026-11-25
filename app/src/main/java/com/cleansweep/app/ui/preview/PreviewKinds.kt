package com.cleansweep.app.ui.preview

import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.IndexedFile

/** What can be shown of a file, and so how it is shown. */
enum class PreviewKind {
    IMAGE,
    VIDEO,
    AUDIO,
    PDF,
    TEXT,

    /** Nothing can be shown of this kind of file. Its details are shown instead. */
    NONE,
}

/** Decides how a file can be shown before the user decides about it (PRD 4.5). */
object PreviewKinds {

    /** Text is read up to this much: enough to recognise a file, and no more than a screen can hold. */
    const val TEXT_READ_LIMIT_BYTES: Long = 64L * 1024

    /** A file larger than this is not opened as text, whatever it is called. */
    const val TEXT_MAX_FILE_BYTES: Long = 1024L * 1024

    private val TEXT_EXTENSIONS = setOf(
        "txt", "log", "csv", "json", "xml", "md", "rtf", "tsv", "ini", "cfg", "conf", "yaml", "yml", "kt", "java", "py",
    )

    fun of(
        file: IndexedFile,
    ): PreviewKind {
        if (file.isDirectory) {
            return PreviewKind.NONE
        }
        return when {
            file.extension == "pdf" -> PreviewKind.PDF
            file.category == FileCategory.IMAGE -> PreviewKind.IMAGE
            file.category == FileCategory.VIDEO -> PreviewKind.VIDEO
            file.category == FileCategory.AUDIO -> PreviewKind.AUDIO
            file.extension in TEXT_EXTENSIONS && file.sizeBytes <= TEXT_MAX_FILE_BYTES -> PreviewKind.TEXT
            else -> PreviewKind.NONE
        }
    }
}
