package com.cleansweep.app.domain.scanner.whitelist

import com.cleansweep.app.domain.model.WhitelistRule
import com.cleansweep.app.domain.model.WhitelistRuleType
import com.cleansweep.app.domain.repository.WhitelistRepository
import com.cleansweep.app.domain.scanner.StorageRootsProvider
import com.cleansweep.app.domain.scanner.walker.FileMetadataReader
import com.cleansweep.app.domain.util.StoragePaths
import kotlinx.coroutines.flow.Flow

/** How a request to protect a path ended. */
sealed interface ProtectionResult {
    /** The path was not protected before, and is now. */
    data class Added(
        val rule: WhitelistRule,
    ) : ProtectionResult

    /**
     * The path was protected already, so nothing changed. [rule] is the rule for that very path, or null when
     * a folder rule above it covers the path.
     */
    data class AlreadyProtected(
        val rule: WhitelistRule?,
    ) : ProtectionResult

    /** Nothing exists at the path, so there is nothing to protect. */
    data object NothingThere : ProtectionResult

    /**
     * The path is a storage root, or lies above one. Protecting it would hide the whole storage from every scan.
     */
    data object WouldExcludeEverything : ProtectionResult
}

/**
 * The user's view of the whitelist: which files and folders the app must leave alone (PRD 4.5).
 *
 * A rule for a folder covers everything below it. What a rule covers is decided by
 * [com.cleansweep.app.domain.model.WhitelistMatcher]; this class only decides what may become a rule, and
 * what kind it is.
 */
class WhitelistManager(
    private val whitelist: WhitelistRepository,
    private val roots: StorageRootsProvider,
    private val metadata: FileMetadataReader,
) {
    /** Every rule, newest first. */
    val rules: Flow<List<WhitelistRule>> = whitelist.observeRules()

    /** True when the file at [path] is protected itself or lies in a protected folder. */
    suspend fun isProtected(path: String): Boolean = whitelist.isWhitelisted(path)

    /**
     * Protects the file or folder at [path]. The kind of the rule follows what the path is on storage.
     *
     * Protecting takes effect at once: whatever was indexed below the path leaves the index, and with it every
     * suggestion, so nothing below the path can be deleted on the strength of an old suggestion.
     */
    suspend fun protect(path: String): ProtectionResult {
        val canonical = canonicalPath(path) ?: return ProtectionResult.NothingThere
        if (roots.roots().any { StoragePaths.isSameOrDescendant(it, canonical) }) {
            return ProtectionResult.WouldExcludeEverything
        }
        val entry = metadata.read(canonical) ?: return ProtectionResult.NothingThere

        // A path that a folder rule above already covers gets no rule of its own. A second rule would keep
        // the path protected after that folder rule is dropped, which is not what the user asked for.
        if (whitelist.matcher().matches(canonical)) {
            val own = whitelist.getRules().firstOrNull { it.path == canonical }
            return ProtectionResult.AlreadyProtected(own)
        }
        val rule = whitelist.addRule(
            path = canonical,
            type = if (entry.isDirectory) WhitelistRuleType.FOLDER else WhitelistRuleType.FILE,
        )
        return ProtectionResult.Added(rule)
    }

    /** Drops a rule. The files below it are scanned and suggested again from the next scan on. */
    suspend fun unprotect(rule: WhitelistRule) {
        whitelist.removeRule(rule.id)
    }

    private fun canonicalPath(path: String): String? = try {
        StoragePaths.canonicalize(path)
    } catch (rejected: IllegalArgumentException) {
        // A blank path names nothing.
        null
    }
}
