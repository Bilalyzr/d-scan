package com.cleansweep.app.domain.util

import java.io.File
import java.io.IOException

/**
 * Path helpers shared by the index, the whitelist and the scanner. Every path stored anywhere in the app goes
 * through [canonicalize] first, so comparisons are always between canonical paths.
 */
object StoragePaths {

    /**
     * Resolves a path to its canonical absolute form with `/` separators: symlinks are followed, `.` and `..`
     * collapse and trailing separators are dropped. On Android, `/sdcard`, `/storage/self/primary` and
     * `/storage/emulated/0` all collapse to the same key, so one physical file can never be indexed twice.
     */
    fun canonicalize(path: String): String {
        val trimmed = path.trim()
        require(trimmed.isNotEmpty()) { "Path must not be blank" }
        val file = File(trimmed)
        val resolved = try {
            file.canonicalPath
        } catch (e: IOException) {
            // Resolution failed (e.g. an unreachable volume). Fall back to the lexical absolute form.
            file.absoluteFile.normalize().path
        }
        return resolved.replace(File.separatorChar, '/')
    }

    /**
     * Prefix that every path below [root] starts with. The SQL side compares it with
     * `substr(path, 1, length(prefix)) = prefix`, which, unlike LIKE, cannot be fooled by `%` or `_` in a name.
     */
    fun descendantPrefix(root: String): String = root.trimEnd('/') + "/"

    /** True if [path] is [root] itself or anything below it. Both must be canonical. */
    fun isSameOrDescendant(path: String, root: String): Boolean {
        val prefix = descendantPrefix(root)
        return path == root.trimEnd('/') || path.startsWith(prefix)
    }
}
