package com.cleansweep.app.domain.scanner.duplicates

/**
 * Places on storage that say something about a file, judged from its canonical path.
 *
 * Downloads is where copies pile up: the same file saved twice, installers after the install, attachments that
 * exist again in the chat. The "Sent" folders of messaging apps hold the copies the app keeps of what the user
 * sent, next to the originals the user picked to send.
 */
object LocationHeuristics {
    private val DOWNLOADS_FOLDER_NAMES: Set<String> = setOf("download", "downloads")

    /** The folders under which apps keep their media, followed by the package name. */
    private const val APP_MEDIA_FOLDER: String = "/android/media/"

    /** Where WhatsApp kept its media before apps had folders of their own; still found on upgraded phones. */
    private const val LEGACY_WHATSAPP_MEDIA_FOLDER: String = "/whatsapp/media/"

    /** True when any folder on the way to the file is called "Download" or "Downloads". */
    fun isInDownloads(path: String): Boolean = path.split('/').any { it.lowercase() in DOWNLOADS_FOLDER_NAMES }

    /**
     * True when the file sits in the Sent folder of WhatsApp or Telegram media, for example in
     * `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Sent/`.
     *
     * Only real app media folders count. A folder of the user's called "WhatsApp Notes" with a "Sent" folder in
     * it is not a messaging app's business.
     */
    fun isInMessagingSentFolder(path: String): Boolean {
        val lowered = path.lowercase()
        val folders = when {
            lowered.contains(APP_MEDIA_FOLDER) -> {
                val belowMedia = lowered.substringAfter(APP_MEDIA_FOLDER)
                if (isMessagingApp(belowMedia.substringBefore('/'))) {
                    belowMedia.substringAfter('/', missingDelimiterValue = "").split('/')
                } else {
                    emptyList()
                }
            }

            // The old layout has no package name in it: /WhatsApp/Media/<folder>/file.
            lowered.contains(LEGACY_WHATSAPP_MEDIA_FOLDER) ->
                lowered.substringAfter(LEGACY_WHATSAPP_MEDIA_FOLDER).split('/')

            else -> emptyList()
        }
        return folders.any { it == "sent" || it.endsWith(" sent") }
    }

    /** True when the file is in either kind of place. */
    fun isInDownloadsLikeLocation(path: String): Boolean = isInDownloads(path) || isInMessagingSentFolder(path)

    private fun isMessagingApp(packageName: String): Boolean =
        packageName == "com.whatsapp" ||
            packageName.startsWith("com.whatsapp.") ||
            packageName.contains("telegram")
}
