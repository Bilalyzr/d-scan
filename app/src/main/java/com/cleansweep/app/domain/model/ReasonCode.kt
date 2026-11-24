package com.cleansweep.app.domain.model

/**
 * A single explainable signal that contributed to a deletion confidence score (PRD 4.4).
 *
 * The scoring weights live with the scoring engine (`domain.scanner.scoring`); this enum is only the vocabulary
 * that is persisted, so a score can always be explained without recomputing it. Stored by name, which makes
 * renaming or removing a constant a schema change. Adding one is safe.
 */
enum class ReasonCode {
    // ---- Defining signals: they say what the item is and decide its suggestion category. ----

    /** Exact byte-for-byte copy of another file. */
    EXACT_COPY,

    /** Temporary, log or backup file. */
    TEMP_OR_LOG,

    /** Thumbnail cache left behind by a gallery app or the media store. */
    THUMBNAIL_CACHE,

    /** Empty (0-byte) file. */
    ZERO_BYTE,

    /** Installer for an app that is already installed at this version or newer. */
    INSTALLED_APK,

    /** Installer older than a week for an app that is not installed. */
    OLD_INSTALLER,

    /** Folder with no files, not even in its subfolders. */
    EMPTY_FOLDER,

    /** At or above the large-file threshold. */
    LARGE_FILE,

    // ---- Modifying signals: they raise or lower the confidence of a suggestion. ----

    /** Located in a Downloads folder. */
    IN_DOWNLOADS,

    /** Located in the Sent folder of a messaging app. */
    IN_MESSAGING_SENT,

    /** Not modified for 90 days or more. */
    STALE_90_DAYS,

    /** Blurry or very low-resolution photo. */
    BLURRY_MEDIA,

    /** Modified within the last few days, so something may still be using it. */
    RECENT,
}
