package com.cleansweep.app.domain.model

/**
 * A single explainable signal that contributed to a deletion confidence score (PRD 4.4).
 *
 * The scoring weights live with the scoring engine (Phase 2); this enum is only the vocabulary that is persisted,
 * so a score can always be explained without recomputing it. Stored by name, which makes renaming or removing a
 * constant a schema change.
 */
enum class ReasonCode {
    /** Exact byte-for-byte copy of another file. */
    EXACT_COPY,

    /** Temporary, log or backup file. */
    TEMP_OR_LOG,

    /** Empty (0-byte) file. */
    ZERO_BYTE,

    /** Folder that contains no files, not even in its subfolders. */
    EMPTY_FOLDER,

    /** Installer for an app that is already installed. */
    INSTALLED_APK,

    /** Located in a Downloads folder. */
    IN_DOWNLOADS,

    /** Located in the Sent folder of a messaging app. */
    IN_MESSAGING_SENT,

    /** Not modified for 90 days or more. */
    STALE_90_DAYS,

    /** Blurry or very low-resolution photo. */
    BLURRY_MEDIA,
}
