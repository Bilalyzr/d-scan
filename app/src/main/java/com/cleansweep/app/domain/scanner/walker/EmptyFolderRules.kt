package com.cleansweep.app.domain.scanner.walker

/**
 * Which empty folders may be suggested for deletion.
 *
 * "Empty" alone is not enough. The system recreates its standard folders, and an app that is about to save a
 * file often creates the folder for it first. So only folders the user plausibly created themselves qualify.
 */
object EmptyFolderRules {
    /** The storage root itself is depth 0, its direct children are depth 1. */
    const val MIN_DEPTH_BELOW_ROOT: Int = 2

    /**
     * Folder names Android and its apps create on their own, compared case-insensitively.
     */
    val SYSTEM_FOLDER_NAMES: Set<String> = setOf(
        "alarms", "android", "audiobooks", "camera", "dcim", "documents", "download", "downloads",
        "lost.dir", "movies", "music", "notifications", "photos", "pictures", "podcasts", "records",
        "recordings", "ringtones", "screenshot", "screenshots", "videos",
    )

    /** True when a folder may be suggested at all, judged by [path] and by how far below the root it lies. */
    fun isSuggestable(
        path: String,
        depthBelowRoot: Int,
    ): Boolean = depthBelowRoot >= MIN_DEPTH_BELOW_ROOT && hasSuggestableName(path)

    /** The part of the rule that does not need the depth: not hidden and not a system folder. */
    fun hasSuggestableName(path: String): Boolean {
        if (FileNames.isHiddenPath(path)) {
            return false
        }
        val name = path.substringAfterLast('/').lowercase()
        return name.isNotEmpty() && name !in SYSTEM_FOLDER_NAMES
    }

    /** Of a set of empty folders, keeps only those that sit at the top of an empty tree. */
    fun topmost(folders: List<String>): List<String> {
        val kept = ArrayList<String>()
        for (folder in folders.sorted()) {
            if (kept.none { folder.startsWith("$it/") }) {
                kept += folder
            }
        }
        return kept
    }
}
