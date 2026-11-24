package com.cleansweep.app.domain.model

/**
 * Coarse content bucket of a file, derived from its extension.
 *
 * Drives the dashboard breakdown (PRD 4.6), selective scan filters (PRD 4.1) and the stale-entry sweep of
 * incremental scans. Stored in `file_index.category` by name, so renaming or removing a constant is a schema
 * change. Anything unlisted falls into [OTHER]; empty-folder placeholder rows are stored as [OTHER] as well
 * (see [IndexedFile.isDirectory]).
 */
enum class FileCategory {
    DOCUMENT,
    IMAGE,
    VIDEO,
    AUDIO,
    APK,
    ARCHIVE,
    OTHER,
    ;

    companion object {
        private val DOCUMENT_EXTENSIONS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "epub")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp")
        private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "amr")
        private val APK_EXTENSIONS = setOf("apk", "xapk", "apks")
        private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst")

        /** The formats listed in PRD 4.1 plus the other extensions commonly left behind by messaging and camera apps. */
        fun fromExtension(extension: String): FileCategory = when (val key = extension.lowercase()) {
            in DOCUMENT_EXTENSIONS -> DOCUMENT
            in IMAGE_EXTENSIONS -> IMAGE
            in VIDEO_EXTENSIONS -> VIDEO
            in AUDIO_EXTENSIONS -> AUDIO
            in APK_EXTENSIONS -> APK
            in ARCHIVE_EXTENSIONS -> ARCHIVE
            else -> OTHER
        }
    }
}
