package com.cleansweep.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cleansweep.app.domain.model.WhitelistRuleType

/**
 * `whitelist_rules`: user-protected files and folders (PRD schema, unchanged).
 *
 * Despite the column name, `path_pattern` is a canonical absolute path, not a glob. A path can only be protected
 * once, hence the unique index.
 */
@Entity(
    tableName = "whitelist_rules",
    indices = [Index(value = ["path_pattern"], unique = true)],
)
data class WhitelistRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "path_pattern")
    val pathPattern: String,
    @ColumnInfo(name = "rule_type")
    val ruleType: WhitelistRuleType,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
