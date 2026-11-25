package com.cleansweep.app.domain.scanner.junk

import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory

/**
 * Every junk detector, run over a list of files. One place, so that a scan and a later look at a single group of
 * duplicates always agree on what a file is.
 *
 * Only files the detectors report come back. Which of those may be offered is decided by the caller: the
 * guard, the whitelist and the Original Lock of a group all come after this.
 */
class JunkDetectorSuite(
    private val installedApks: InstalledApkOracle,
) {

    suspend fun detect(
        files: List<IndexedFile>,
        context: DetectionContext,
    ): List<Finding> {
        val findings = ArrayList<Finding>()
        findings += TempFileDetector().detect(files, context)
        findings += ZeroByteDetector().detect(files, context)
        findings += LargeForgottenFileDetector().detect(files, context)
        findings += ObsoleteApkDetector(installedApks).detect(files, context)
        for (placeholder in files) {
            if (placeholder.isDirectory) {
                val reasons = JunkRules.reasonsFor(ReasonCode.EMPTY_FOLDER, placeholder, context.now)
                findings += Finding(placeholder, SuggestionCategory.EMPTY_FOLDER, reasons)
            }
        }
        return findings
    }
}
