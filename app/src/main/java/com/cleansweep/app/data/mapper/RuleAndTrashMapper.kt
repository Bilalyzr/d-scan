package com.cleansweep.app.data.mapper

import com.cleansweep.app.data.local.db.entity.TrashManifestEntity
import com.cleansweep.app.data.local.db.entity.WhitelistRuleEntity
import com.cleansweep.app.domain.model.TrashEntry
import com.cleansweep.app.domain.model.WhitelistRule

fun WhitelistRuleEntity.toDomain(): WhitelistRule = WhitelistRule(
    id = id,
    path = pathPattern,
    type = ruleType,
    createdAt = createdAt,
)

fun TrashManifestEntity.toDomain(): TrashEntry = TrashEntry(
    id = id,
    originalPath = originalPath,
    trashPath = trashPath,
    sizeBytes = sizeBytes,
    deletedAt = deletedAt,
    expiresAt = expiresAt,
)

fun TrashEntry.toEntity(): TrashManifestEntity = TrashManifestEntity(
    id = id,
    originalPath = originalPath,
    trashPath = trashPath,
    sizeBytes = sizeBytes,
    deletedAt = deletedAt,
    expiresAt = expiresAt,
)
