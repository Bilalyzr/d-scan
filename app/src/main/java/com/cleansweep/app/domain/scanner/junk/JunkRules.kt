package com.cleansweep.app.domain.scanner.junk

import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.ReasonCode

/** The thresholds and names the junk detectors share. Time-based rules live here so that there is one clock. */
object JunkRules {
    private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

    val TEMPORARY_EXTENSIONS: Set<String> = setOf("tmp", "log", "bak")
    val INSTALLER_EXTENSIONS: Set<String> = setOf("apk", "xapk")

    /** Only a plain APK can be inspected for the app inside it. An XAPK is a zip of several files. */
    const val INSPECTABLE_INSTALLER_EXTENSION: String = "apk"

    /** An installer that is not installed counts as old after this many days (PRD 4.3). */
    const val OLD_INSTALLER_DAYS: Long = 7

    /** "Untouched" in the scoring rules means this many days without a modification (PRD 4.4). */
    const val STALE_DAYS: Long = 90

    /**
     * An entry modified this recently may still be in use: a download in progress, a folder an app has just
     * created for a file it is about to write. Such entries are never pre-selected for deletion.
     */
    const val RECENT_DAYS: Long = 7

    /** Thumbnail caches sit in a hidden folder of this name, on the same storage as the pictures. */
    const val THUMBNAIL_CACHE_MARKER: String = "/.thumbnails/"

    /** Whole days since [lastModified], never negative, for when a clock on the device ran ahead. */
    fun ageInDays(
        lastModified: Long,
        now: Long,
    ): Long = (now - lastModified).coerceAtLeast(0L) / DAY_MILLIS

    fun isStale(
        lastModified: Long,
        now: Long,
    ): Boolean = ageInDays(lastModified, now) >= STALE_DAYS

    fun isRecent(
        lastModified: Long,
        now: Long,
    ): Boolean = ageInDays(lastModified, now) < RECENT_DAYS

    /** Every extension the junk candidates are fetched for, so that each detector finds its files in that list. */
    fun candidateExtensions(): Set<String> = TEMPORARY_EXTENSIONS + INSTALLER_EXTENSIONS

    /** The defining signal of a finding, followed by [ReasonCode.RECENT] when the entry was modified lately. */
    fun reasonsFor(
        defining: ReasonCode,
        file: IndexedFile,
        now: Long,
    ): List<ReasonCode> =
        if (isRecent(file.lastModified, now)) listOf(defining, ReasonCode.RECENT) else listOf(defining)
}
