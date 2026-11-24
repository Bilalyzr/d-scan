package com.cleansweep.app.data.mapper

import com.cleansweep.app.data.local.db.entity.FileIndexEntity
import com.cleansweep.app.domain.model.IndexedFile

fun FileIndexEntity.toDomain(): IndexedFile = IndexedFile(
    id = id,
    path = path,
    name = name,
    extension = extension,
    mimeType = mimeType,
    category = category,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    createdAt = createdAt,
    partialHash = partialHash,
    fullHash = fullHash,
    scanTimestamp = scanTimestamp,
    isDirectory = isDirectory,
    isOriginalLocked = isOriginalLocked,
)

fun IndexedFile.toEntity(): FileIndexEntity = FileIndexEntity(
    id = id,
    path = path,
    name = name,
    extension = extension,
    mimeType = mimeType,
    category = category,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    createdAt = createdAt,
    partialHash = partialHash,
    fullHash = fullHash,
    scanTimestamp = scanTimestamp,
    isDirectory = isDirectory,
    isOriginalLocked = isOriginalLocked,
)
