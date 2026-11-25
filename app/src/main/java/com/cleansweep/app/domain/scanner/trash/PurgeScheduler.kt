package com.cleansweep.app.domain.scanner.trash

/**
 * Has the expired entries of Safe Trash deleted for good, again and again in the background.
 *
 * Deleting is forever, so it must not depend on the user opening the app. WorkManager does it in the app.
 */
interface PurgeScheduler {
    /** Makes sure a recurring purge is planned. Calling it again before [cancel] changes nothing. */
    fun schedule()

    fun cancel()
}

/** Tells the system's media index to look at these paths again. */
interface MediaScanner {
    /**
     * Called after entries were moved to or from Safe Trash. A photo that was trashed leaves the gallery at
     * once, and one that was restored comes back, without waiting for the next full media scan.
     */
    fun scan(paths: Collection<String>)
}
