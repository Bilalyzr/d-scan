package com.cleansweep.app.domain.scanner.walker

object FileNames {

    /**
     * Lowercase extension without the dot. Empty for a file without an extension and for a dot-file such as
     * `.nomedia`, whose leading dot belongs to the name rather than separating an extension.
     */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) {
            return ""
        }
        return name.substring(dot + 1).lowercase()
    }

    fun isHiddenName(name: String): Boolean = name.length > 1 && name.startsWith('.')

    /**
     * True when the entry itself or any folder on the way to it is hidden, as in `.thumbnails/thumb1`.
     *
     * Hidden entries are managed by apps or by the system rather than by the user (`.nomedia`, `.trashed-*`,
     * pending downloads), so most detectors leave them alone.
     */
    fun isHiddenPath(path: String): Boolean = path.split('/').any { isHiddenName(it) }
}
