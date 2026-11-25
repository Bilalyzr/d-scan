package com.cleansweep.app.domain.repository

import com.cleansweep.app.domain.model.WhitelistMatcher
import com.cleansweep.app.domain.model.WhitelistRule
import com.cleansweep.app.domain.model.WhitelistRuleType
import kotlinx.coroutines.flow.Flow

/**
 * User-defined exclusions. A whitelisted path is skipped by every scan and never suggested for deletion.
 * All methods are main-safe.
 */
interface WhitelistRepository {

    /** Newest rule first. */
    fun observeRules(): Flow<List<WhitelistRule>>

    /** Every rule at once, for a check that needs them only once. */
    suspend fun getRules(): List<WhitelistRule>

    fun observeRuleCount(): Flow<Int>

    /**
     * Protects [path] and returns the resulting rule.
     *
     * The path is canonicalised, so a rule added through `/sdcard/...` also covers `/storage/emulated/0/...`.
     * A [WhitelistRuleType.FILE] rule protects exactly that file; a [WhitelistRuleType.FOLDER] rule also protects
     * every file below it. The rule takes effect at once: anything already indexed under the path is removed
     * together with its suggestions, in the same transaction. Adding a path that is already whitelisted returns
     * the existing rule and changes nothing.
     */
    suspend fun addRule(path: String, type: WhitelistRuleType): WhitelistRule

    suspend fun removeRule(id: Long)

    /** A snapshot of every rule, to test many paths during one scan without querying the database each time. */
    suspend fun matcher(): WhitelistMatcher

    /** Convenience for one-off checks (e.g. right before a deletion); canonicalises [path] first. */
    suspend fun isWhitelisted(path: String): Boolean
}
