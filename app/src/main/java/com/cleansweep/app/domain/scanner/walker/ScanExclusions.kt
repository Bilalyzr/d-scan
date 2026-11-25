package com.cleansweep.app.domain.scanner.walker

/**
 * Storage that is never walked, whoever whitelists what.
 *
 * [com.cleansweep.app.domain.model.WhitelistMatcher] carries the user's exclusions. This class carries the ones
 * that are not the user's to change: other apps' private storage, which the app has no business reading.
 */
class ScanExclusions(
    private val excludedRelativeToRoot: Set<String> = DEFAULT,
) {
    /** True when [canonicalPath] is one of the excluded folders below the storage [root], or lies inside one. */
    fun isExcluded(canonicalPath: String, root: String): Boolean {
        val prefix = root.trimEnd('/') + "/"
        if (!canonicalPath.startsWith(prefix)) {
            return false
        }
        val relative = canonicalPath.removePrefix(prefix)
        return excludedRelativeToRoot.any { relative == it || relative.startsWith("$it/") }
    }

    companion object {
        /**
         * `Android/data` and `Android/obb` belong to other apps. `Android/media` is deliberately absent:
         * WhatsApp and Telegram keep their media there, and finding duplicates in it is a PRD requirement.
         */
        val DEFAULT: Set<String> = setOf("Android/data", "Android/obb")
    }
}
