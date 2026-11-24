package com.cleansweep.app.domain.model

/** Whether a whitelist rule protects a single file or a whole folder tree. Stored by name. */
enum class WhitelistRuleType {
    FOLDER,
    FILE,
}

/**
 * A user-defined exclusion (PRD 4.5). Whitelisted paths are skipped by scans and never suggested for deletion.
 *
 * @property path Canonical absolute path (see [com.cleansweep.app.domain.util.StoragePaths]). Despite the column
 *   name `path_pattern`, this is an exact path rather than a glob: a [WhitelistRuleType.FILE] rule matches exactly
 *   this path, a [WhitelistRuleType.FOLDER] rule also covers everything below it.
 * @property createdAt Creation time in epoch millis.
 */
data class WhitelistRule(
    val id: Long,
    val path: String,
    val type: WhitelistRuleType,
    val createdAt: Long,
)
