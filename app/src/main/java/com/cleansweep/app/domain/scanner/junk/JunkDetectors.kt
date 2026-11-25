package com.cleansweep.app.domain.scanner.junk

import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory

/**
 * Answers whether an installer has nothing left to offer because the app in it is already on the device.
 *
 * An interface, so that detectors can be tested without installing anything. The app's implementation reads the
 * installer with `PackageManager`.
 */
fun interface InstalledApkOracle {
    /**
     * True when the app inside the installer at [apkPath] is installed at the installer's version or a newer
     * one. False when it cannot tell: a damaged file, a package that is not installed, an older installed version.
     */
    suspend fun isSupersededByInstalledApp(apkPath: String): Boolean

    companion object {
        val NONE: InstalledApkOracle = InstalledApkOracle { false }
    }
}

/** `.tmp`, `.log` and `.bak` files, and the contents of `.thumbnails` folders (PRD 4.3). */
class TempFileDetector {
    fun detect(
        files: List<IndexedFile>,
        context: DetectionContext,
    ): List<Finding> = files.mapNotNull { file ->
        if (file.isDirectory) {
            return@mapNotNull null
        }
        val defining = when {
            file.extension in JunkRules.TEMPORARY_EXTENSIONS -> ReasonCode.TEMP_OR_LOG
            file.path.contains(JunkRules.THUMBNAIL_CACHE_MARKER) -> ReasonCode.THUMBNAIL_CACHE
            else -> return@mapNotNull null
        }
        Finding(file, SuggestionCategory.TEMP_FILE, JunkRules.reasonsFor(defining, file, context.now))
    }
}

/** Files of exactly 0 bytes (PRD 4.3). */
class ZeroByteDetector {
    fun detect(
        files: List<IndexedFile>,
        context: DetectionContext,
    ): List<Finding> = files
        .filter { !it.isDirectory && it.sizeBytes == 0L }
        .map { Finding(it, SuggestionCategory.ZERO_BYTE, JunkRules.reasonsFor(ReasonCode.ZERO_BYTE, it, context.now)) }
}

/** Files at or above the large-file threshold that were not modified for the whole stale window (PRD 4.3). */
class LargeForgottenFileDetector {
    fun detect(
        files: List<IndexedFile>,
        context: DetectionContext,
    ): List<Finding> = files.mapNotNull { file ->
        if (file.isDirectory) {
            return@mapNotNull null
        }
        val settings = context.settings
        val forgotten = file.sizeBytes >= settings.largeFileThresholdBytes &&
            JunkRules.ageInDays(file.lastModified, context.now) >= settings.largeFileStaleDays
        if (!forgotten) {
            return@mapNotNull null
        }
        // The stale signal is added by the suggestion engine, like for every other finding.
        Finding(file, SuggestionCategory.LARGE_FORGOTTEN, listOf(ReasonCode.LARGE_FILE))
    }
}

/**
 * Installers that are no longer needed (PRD 4.3): the app in them is already installed at that version or
 * newer, or the installer has been sitting untouched for more than a week.
 *
 * The two cases are not equally safe. An installed app's installer is certainly redundant. An old installer of
 * an app that is not installed may be the only copy the user has, so it carries a weaker signal.
 */
class ObsoleteApkDetector(
    private val installed: InstalledApkOracle,
) {
    suspend fun detect(
        files: List<IndexedFile>,
        context: DetectionContext,
    ): List<Finding> {
        val findings = ArrayList<Finding>()
        for (file in files) {
            if (file.isDirectory || file.extension !in JunkRules.INSTALLER_EXTENSIONS) {
                continue
            }
            val installedAlready = file.extension == JunkRules.INSPECTABLE_INSTALLER_EXTENSION &&
                installed.isSupersededByInstalledApp(file.path)
            val defining = when {
                installedAlready -> ReasonCode.INSTALLED_APK
                JunkRules.ageInDays(file.lastModified, context.now) >= JunkRules.OLD_INSTALLER_DAYS ->
                    ReasonCode.OLD_INSTALLER

                else -> continue
            }
            findings += Finding(file, SuggestionCategory.OBSOLETE_INSTALLER, listOf(defining))
        }
        return findings
    }
}
