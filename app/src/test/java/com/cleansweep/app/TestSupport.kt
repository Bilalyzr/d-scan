package com.cleansweep.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cleansweep.app.data.local.db.CleanSweepDatabase
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.IndexedFile

/** An in-memory CleanSweep database backed by Robolectric's SQLite. */
internal fun inMemoryDatabase(): CleanSweepDatabase =
    Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        CleanSweepDatabase::class.java,
    ).allowMainThreadQueries().build()

/** Builds a file row. Name and extension are derived from the path. Hashes are unset unless given. */
internal fun file(
    path: String,
    sizeBytes: Long = 100L,
    lastModified: Long = 1_000L,
    category: FileCategory = FileCategory.OTHER,
    scanTimestamp: Long = 10_000L,
    partialHash: String? = null,
    fullHash: String? = null,
    isOriginalLocked: Boolean = false,
): IndexedFile {
    val name = path.substringAfterLast('/')
    return IndexedFile(
        path = path,
        name = name,
        extension = name.substringAfterLast('.', ""),
        mimeType = null,
        category = category,
        sizeBytes = sizeBytes,
        lastModified = lastModified,
        createdAt = null,
        partialHash = partialHash,
        fullHash = fullHash,
        scanTimestamp = scanTimestamp,
        isOriginalLocked = isOriginalLocked,
    )
}

/** Builds an empty-folder placeholder row for [path]. */
internal fun folder(path: String, scanTimestamp: Long = 10_000L): IndexedFile = IndexedFile(
    path = path,
    name = path.substringAfterLast('/'),
    extension = "",
    mimeType = null,
    category = FileCategory.OTHER,
    sizeBytes = 0L,
    lastModified = 1_000L,
    createdAt = null,
    scanTimestamp = scanTimestamp,
    isDirectory = true,
)
