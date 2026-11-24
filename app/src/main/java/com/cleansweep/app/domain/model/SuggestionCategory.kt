package com.cleansweep.app.domain.model

/**
 * Why an item is suggested: the junk buckets of PRD 4.3 plus exact duplicates.
 *
 * Stored in `suggestion_items.category` by name, so renaming or removing a constant is a schema change.
 */
enum class SuggestionCategory {
    /** A non-original copy of a group of byte-identical files. */
    EXACT_DUPLICATE,

    /** An .apk/.xapk that is already installed, or older than 7 days. */
    OBSOLETE_INSTALLER,

    /** A .tmp/.log/.bak file or thumbnail cache. */
    TEMP_FILE,

    /** A zero-byte file. */
    ZERO_BYTE,

    /** An empty folder, referenced through its placeholder row in `file_index`. */
    EMPTY_FOLDER,

    /** Above the large-file threshold and untouched for longer than the stale window. */
    LARGE_FORGOTTEN,
}
