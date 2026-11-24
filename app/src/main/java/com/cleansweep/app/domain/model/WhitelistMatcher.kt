package com.cleansweep.app.domain.model

import com.cleansweep.app.domain.util.StoragePaths

/**
 * Immutable snapshot of every whitelist rule, built once so a scan can test paths without touching the database.
 *
 * All paths handed in and tested must already be canonical (see [StoragePaths.canonicalize]); matching is an
 * exact, case-sensitive comparison on whole path segments.
 */
class WhitelistMatcher(
    files: Collection<String>,
    folders: Collection<String>,
) {
    private val files: Set<String> = files.toSet()
    private val folders: List<String> = folders.map { it.trimEnd('/') }.distinct()

    /**
     * True if [path] is a whitelisted file, a whitelisted folder itself, or anything below a whitelisted folder.
     * An empty path is never whitelisted, even by a rule for the filesystem root.
     *
     * The folder test is [StoragePaths.isSameOrDescendant], the same rule the SQL purge uses, so the matcher and
     * the database always agree on what a folder rule covers.
     */
    fun matches(path: String): Boolean =
        path.isNotEmpty() && (path in files || folders.any { StoragePaths.isSameOrDescendant(path, it) })

    companion object {
        fun from(rules: List<WhitelistRule>): WhitelistMatcher = WhitelistMatcher(
            files = rules.filter { it.type == WhitelistRuleType.FILE }.map { it.path },
            folders = rules.filter { it.type == WhitelistRuleType.FOLDER }.map { it.path },
        )
    }
}
