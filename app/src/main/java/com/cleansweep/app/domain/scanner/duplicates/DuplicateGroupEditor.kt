package com.cleansweep.app.domain.scanner.duplicates

import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.WhitelistMatcher
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.repository.SettingsRepository
import com.cleansweep.app.domain.repository.SuggestionRepository
import com.cleansweep.app.domain.repository.WhitelistRepository
import com.cleansweep.app.domain.scanner.SuggestionGuard
import com.cleansweep.app.domain.scanner.junk.DetectionContext
import com.cleansweep.app.domain.scanner.junk.Finding
import com.cleansweep.app.domain.scanner.junk.InstalledApkOracle
import com.cleansweep.app.domain.scanner.junk.JunkDetectorSuite
import com.cleansweep.app.domain.scanner.scoring.SuggestionEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Clock

/** How a request to keep another copy of a group ended. */
sealed interface KeepResult {
    /** The copy is kept now, and the other copies of its group are offered for deletion. */
    data object Kept : KeepResult

    data object AlreadyKept : KeepResult

    /** The file is not one of a group of identical files, or no longer is. */
    data object NotADuplicate : KeepResult

    data object FileGone : KeepResult
}

/** How a request to stop keeping a copy ended. */
sealed interface ReleaseResult {
    /** The copy is no longer kept by choice. The group keeps a copy by its usual rule. */
    data object Released : ReleaseResult

    data object NotKept : ReleaseResult

    data object NotADuplicate : ReleaseResult

    data object FileGone : ReleaseResult
}

/**
 * Changes which copy of a group of identical files is kept, and makes the suggestions follow at once.
 *
 * A scan offers every copy for deletion except the one [OriginalLockPolicy] keeps. When the user decides to keep
 * another copy instead, the lock changes here, and the suggestions of the group are worked out again, so the
 * copy that was kept until then can be removed without waiting for the next scan.
 *
 * The suggestions come from the same detectors and the same rules a scan uses ([JunkDetectorSuite] and
 * [SuggestionEngine]), so a copy is offered for the same reasons, with the same score, as after a scan.
 */
class DuplicateGroupEditor(
    private val fileIndex: FileIndexRepository,
    private val suggestions: SuggestionRepository,
    private val whitelist: WhitelistRepository,
    private val installedApks: InstalledApkOracle,
    private val guard: SuggestionGuard,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /** Keeps [copyId] as the copy of its group that stays. Other copies the user kept stay kept as well. */
    suspend fun keepCopy(copyId: Long): KeepResult = withContext(ioDispatcher) {
        val chosen = fileIndex.getById(copyId) ?: return@withContext KeepResult.FileGone
        if (chosen.isOriginalLocked) {
            return@withContext KeepResult.AlreadyKept
        }
        val copies = groupOf(chosen) ?: return@withContext KeepResult.NotADuplicate

        fileIndex.setOriginalLocked(copyId, locked = true)
        refresh(copies.map { if (it.id == copyId) it.copy(isOriginalLocked = true) else it })
        KeepResult.Kept
    }

    /** Stops keeping [copyId] by choice. The group keeps exactly one copy by its usual rule, whatever happens. */
    suspend fun releaseCopy(copyId: Long): ReleaseResult = withContext(ioDispatcher) {
        val chosen = fileIndex.getById(copyId) ?: return@withContext ReleaseResult.FileGone
        if (!chosen.isOriginalLocked) {
            return@withContext ReleaseResult.NotKept
        }
        val copies = groupOf(chosen) ?: return@withContext ReleaseResult.NotADuplicate

        fileIndex.setOriginalLocked(copyId, locked = false)
        refresh(copies.map { if (it.id == copyId) it.copy(isOriginalLocked = false) else it })
        ReleaseResult.Released
    }

    /** Every indexed copy of the file's content, or null when it is not one of a group. */
    private suspend fun groupOf(
        file: IndexedFile,
    ): List<IndexedFile>? {
        val hash = file.fullHash ?: return null
        val copies = fileIndex.getCopiesWithHash(hash)
        return copies.takeIf { it.size >= 2 && it.any { copy -> copy.id == file.id } }
    }

    /**
     * Works the suggestions of [copies] out again and replaces theirs, and no others: every copy but the one the
     * policy keeps is offered as an exact duplicate, and whatever the junk detectors have to say about a copy is
     * added to that.
     */
    private suspend fun refresh(
        copies: List<IndexedFile>,
    ) {
        val original = OriginalLockPolicy.selectOriginal(copies)
        val context = DetectionContext(clock.millis(), settings.current())

        val findings = ArrayList<Finding>()
        for (copy in copies) {
            if (copy.id != original.id) {
                findings += Finding(copy, SuggestionCategory.EXACT_DUPLICATE, listOf(ReasonCode.EXACT_COPY))
            }
        }
        findings += JunkDetectorSuite(installedApks).detect(copies, context)

        val matcher: WhitelistMatcher = whitelist.matcher()
        val eligible = findings.filter { finding ->
            !matcher.matches(finding.file.path) && guard.isEligible(finding.file, finding.category)
        }
        suggestions.replaceForFiles(copies.map { it.id }, SuggestionEngine().build(eligible, context))
    }
}
