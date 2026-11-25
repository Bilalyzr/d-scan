package com.cleansweep.app.data.scanner

import com.cleansweep.app.domain.scanner.FolderLister
import com.cleansweep.app.domain.util.StoragePaths
import java.io.File

/** [FolderLister] on the file system. A folder that cannot be read simply has no folders to show. */
class NioFolderLister : FolderLister {

    override fun listFolders(
        path: String,
    ): List<String> = try {
        File(path)
            .listFiles { entry -> entry.isDirectory && !entry.isHidden && !entry.name.startsWith(".") }
            ?.mapNotNull { folder -> runCatching { StoragePaths.canonicalize(folder.absolutePath) }.getOrNull() }
            ?.sortedBy { folder -> StoragePaths.nameOf(folder).lowercase() }
            .orEmpty()
    } catch (refused: SecurityException) {
        emptyList()
    }
}
