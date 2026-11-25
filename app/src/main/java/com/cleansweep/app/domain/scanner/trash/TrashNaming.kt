package com.cleansweep.app.domain.scanner.trash

import com.cleansweep.app.domain.util.StoragePaths
import java.util.UUID

/**
 * The names under which entries rest in Safe Trash.
 *
 * A trashed entry stays in the folder it was in and only changes its name to
 * `.cleansweep-trashed-<token>-<original name>`. The leading dot hides it from MediaStore, so a trashed photo
 * leaves the gallery at once, and from file managers that do not show hidden files. This is how Android's own
 * system trash works as well.
 *
 * The entry is not copied anywhere. Copying it into the app's private storage would take as much free internal
 * storage as the entry is large, which is exactly what a phone that needs cleaning does not have, and it would
 * take minutes for a long video. Renaming within a folder takes no space and no time.
 */
object TrashNaming {
    /** Marks a file or folder on storage as lying in Safe Trash. */
    const val PREFIX: String = ".cleansweep-trashed-"

    /** The longest file name the file systems Android uses accept, in UTF-8 bytes. */
    const val MAX_NAME_BYTES: Int = 255

    private const val TOKEN_LENGTH: Int = 16

    /** A random token that keeps every trashed entry's name unique, whatever was moved in that folder before. */
    fun newToken(): String = UUID.randomUUID().toString().replace("-", "").take(TOKEN_LENGTH)

    /** The hidden name under which an entry called [originalName] rests in Safe Trash. */
    fun trashedName(
        token: String,
        originalName: String,
    ): String {
        val mark = "$PREFIX$token-"
        return mark + shortened(originalName, MAX_NAME_BYTES - utf8Length(mark))
    }

    /** The path of an entry that rests in Safe Trash: the same folder as [originalPath], under a hidden name. */
    fun trashedPath(
        originalPath: String,
        token: String,
    ): String {
        val name = trashedName(token, StoragePaths.nameOf(originalPath))
        val parent = StoragePaths.parentOf(originalPath) ?: return name
        return StoragePaths.join(parent, name)
    }

    /** True when [name] carries the mark of Safe Trash. The bare mark alone is not a trashed entry. */
    fun isTrashedName(name: String): Boolean = name.startsWith(PREFIX) && name.length > PREFIX.length

    /** The name for an entry that cannot take its own name back because something else has it now. */
    fun restoredName(name: String): String = withSuffix(name, " (restored)")

    /** The name for the [attempt]-th entry that has to share [name]. */
    fun restoredName(
        name: String,
        attempt: Int,
    ): String = withSuffix(name, " (restored $attempt)")

    private fun withSuffix(
        name: String,
        suffix: String,
    ): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        val roomForBase = MAX_NAME_BYTES - utf8Length(suffix) - utf8Length(extension)
        return when {
            // The suffix is what keeps the name apart from the file that has the original name, so it is the
            // base that gives way when the name is too long.
            roomForBase > 0 -> shortened(base, roomForBase) + suffix + extension
            extension.isNotEmpty() -> shortened(name, MAX_NAME_BYTES - utf8Length(suffix)) + suffix
            else -> shortened(name, MAX_NAME_BYTES)
        }
    }

    /** The longest start of [name], whole characters only, that fits in [maxBytes]. */
    private fun shortened(
        name: String,
        maxBytes: Int,
    ): String {
        if (utf8Length(name) <= maxBytes) {
            return name
        }
        // Keep the extension when there is room for it and for at least one character before it.
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0) name.substring(dot) else ""
        val roomForBase = maxBytes - utf8Length(extension)
        if (extension.isNotEmpty() && roomForBase > 0) {
            return startOf(name.substring(0, dot), roomForBase) + extension
        }
        return startOf(name, maxBytes)
    }

    private fun startOf(
        text: String,
        maxBytes: Int,
    ): String {
        var end = text.length
        while (end > 0 && utf8Length(text.substring(0, end)) > maxBytes) {
            end--
        }
        // Never cut between the two halves of a character such as an emoji.
        if (end in 1 until text.length && Character.isHighSurrogate(text[end - 1])) {
            end--
        }
        return text.substring(0, end)
    }

    private fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size
}
