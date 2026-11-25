package com.cleansweep.app.domain.scanner

/** Lists the folders directly inside a folder, for the picker in Settings. */
fun interface FolderLister {

    /**
     * Canonical paths of the folders inside [path], by name. Hidden folders are left out, as folders that belong
     * to apps. Empty when there are none, or when the folder cannot be read.
     */
    fun listFolders(
        path: String,
    ): List<String>
}
