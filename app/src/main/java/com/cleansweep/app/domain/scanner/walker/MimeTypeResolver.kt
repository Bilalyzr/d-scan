package com.cleansweep.app.domain.scanner.walker

/** Resolves a lowercase extension to a MIME type, or null when the platform does not know it. */
fun interface MimeTypeResolver {
    fun mimeTypeFor(lowercaseExtension: String): String?

    companion object {
        val NONE: MimeTypeResolver = MimeTypeResolver { null }
    }
}
