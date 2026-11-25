package com.cleansweep.app.domain.scanner

import com.cleansweep.app.domain.model.DuplicateGroup
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionDraft
import com.cleansweep.app.domain.model.UserPreferences
import com.cleansweep.app.domain.model.WhitelistMatcher
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.repository.SettingsRepository
import com.cleansweep.app.domain.repository.SuggestionRepository
import com.cleansweep.app.domain.repository.WhitelistRepository
import com.cleansweep.app.domain.scanner.duplicates.DuplicatePipeline
import com.cleansweep.app.domain.scanner.duplicates.OriginalLockPolicy
import com.cleansweep.app.domain.scanner.hashing.DeviceStateReader
import com.cleansweep.app.domain.scanner.hashing.HashingConcurrency
import com.cleansweep.app.domain.scanner.junk.DetectionContext
import com.cleansweep.app.domain.scanner.junk.Finding
import com.cleansweep.app.domain.scanner.junk.InstalledApkOracle
import com.cleansweep.app.domain.scanner.junk.JunkRules
import com.cleansweep.app.domain.scanner.junk.LargeForgottenFileDetector
import com.cleansweep.app.domain.scanner.junk.ObsoleteApkDetector
import com.cleansweep.app.domain.scanner.junk.TempFileDetector
import com.cleansweep.app.domain.scanner.junk.ZeroByteDetector
import com.cleansweep.app.domain.scanner.scoring.SuggestionEngine
import com.cleansweep.app.domain.scanner.walker.FileSink
import com.cleansweep.app.domain.scanner.walker.FileWalker
import com.cleansweep.app.domain.scanner.walker.WalkRequest
import com.cleansweep.app.domain.scanner.walker.WalkResult
import com.cleansweep.app.domain.util.StoragePaths
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * Runs one scan from end to end, whatever starts it: the user from the dashboard, or a background job.
 *
 * 1. **Walk and index.** Every file below the requested volumes is written to the index with the time the scan
 *    started. Files the scan did not see again below the volumes it could read are dropped.
 * 2. **Hash.** The [DuplicatePipeline] brings the partial and full hashes up to date.
 * 3. **Analyse.** Duplicates and junk are turned into suggestions, which replace the previous ones.
 *
 * The runner holds no state between scans and starts none on its own; starting, pausing and cancelling are the
 * job of [ScanController]. It never throws for a file or folder it cannot deal with: those are counted as errors
 * and the scan goes on. It ends early only when its coroutine is cancelled.
 */
class ScanRunner(
    private val rootsProvider: StorageRootsProvider,
    private val walker: FileWalker,
    private val fileIndex: FileIndexRepository,
    private val whitelist: WhitelistRepository,
    private val suggestionRepository: SuggestionRepository,
    private val pipeline: DuplicatePipeline,
    private val guard: SuggestionGuard,
    private val installedApks: InstalledApkOracle,
    private val settings: SettingsRepository,
    private val deviceState: DeviceStateReader,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun run(
        request: ScanRequest,
        gate: PauseGate,
        listener: ScanProgressListener,
    ): ScanSummary {
        val startedAt = clock.millis()
        val userSettings = settings.current()
        val roots = request.roots ?: rootsProvider.roots()
        if (roots.isEmpty()) {
            throw ScanException(ScanFailure.NO_STORAGE_TO_SCAN, "No storage volume is mounted")
        }
        val matcher = whitelist.matcher()
        val tracker = ScanTracker(listener)
        val context = DetectionContext(startedAt, userSettings)

        val walk = withContext(ioDispatcher) {
            index(roots, request, matcher, userSettings, startedAt, gate, tracker)
        }

        val workers = HashingConcurrency.workersFor(deviceState.currentLoad())
        val groups = pipeline.run(workers, gate, tracker)

        tracker.enterPhase(ScanPhase.ANALYSING)
        val drafts = withContext(ioDispatcher) {
            analyse(groups, walk.rootsWalked, matcher, context)
        }
        suggestionRepository.replaceAll(drafts)

        return ScanSummary(
            rootsScanned = walk.rootsWalked,
            filesIndexed = walk.filesIndexed,
            bytesScanned = walk.bytesSeen,
            foldersScanned = walk.foldersSeen,
            emptyFolders = walk.emptyFolders.size,
            duplicateGroups = groups.size,
            duplicateBytes = groups.sumOf { it.wastedBytes },
            suggestions = drafts.groupingBy { it.category }.eachCount(),
            errors = tracker.current.errors,
        )
    }

    /** Walks the volumes, writes what is found to the index, and removes what is no longer there. */
    private suspend fun index(
        roots: List<String>,
        request: ScanRequest,
        matcher: WhitelistMatcher,
        userSettings: UserPreferences,
        startedAt: Long,
        gate: PauseGate,
        tracker: ScanTracker,
    ): WalkResult {
        val categories = if (request.isFullScan) FileCategory.entries.toSet() else request.categories
        val pending = ArrayList<IndexedFile>(INDEX_BATCH_SIZE)

        suspend fun flush() {
            if (pending.isNotEmpty()) {
                fileIndex.applyScanBatch(pending, startedAt)
                pending.clear()
            }
        }

        val walk = walker.walk(
            WalkRequest(roots, matcher, userSettings.minFileSizeBytes, categories, request.isFullScan),
            gate,
            object : FileSink {
                override fun onFileSeen(sizeBytes: Long) = tracker.fileScanned(sizeBytes)

                override suspend fun onFile(file: IndexedFile) {
                    pending += file
                    if (pending.size >= INDEX_BATCH_SIZE) {
                        flush()
                    }
                }

                override fun onFolder(path: String) = tracker.folderEntered(path)
            },
        )
        flush()

        // A volume the walk could not read says nothing about its files. An ejected SD card keeps its index
        // until it is back, and does not have to be hashed all over again then.
        for (root in walk.rootsWalked) {
            fileIndex.deleteStaleFiles(startedAt, root, categories)
        }
        repeat(walk.unreadableRoots + walk.unreadableFolders) { tracker.error() }

        if (request.isFullScan) {
            fileIndex.replaceEmptyFolderPlaceholders(walk.emptyFolders, startedAt)
        }
        return walk
    }

    /** Turns the duplicate groups and the junk candidates into the suggestions that replace the stored ones. */
    private suspend fun analyse(
        groups: List<DuplicateGroup>,
        rootsWalked: List<String>,
        matcher: WhitelistMatcher,
        context: DetectionContext,
    ): List<SuggestionDraft> {
        val findings = ArrayList<Finding>()

        // Every duplicate group keeps one copy, the Original; only the other copies are offered.
        for (group in groups) {
            for (copy in OriginalLockPolicy.redundantCopies(group.copies)) {
                findings += Finding(copy, SuggestionCategory.EXACT_DUPLICATE, listOf(ReasonCode.EXACT_COPY))
            }
        }

        val candidates = fileIndex.getJunkCandidates(
            largeFileThresholdBytes = context.settings.largeFileThresholdBytes,
            junkExtensions = JunkRules.candidateExtensions(),
        )
        findings += TempFileDetector().detect(candidates, context)
        findings += ZeroByteDetector().detect(candidates, context)
        findings += LargeForgottenFileDetector().detect(candidates, context)
        findings += ObsoleteApkDetector(installedApks).detect(candidates, context)
        for (placeholder in candidates) {
            if (placeholder.isDirectory) {
                val reasons = JunkRules.reasonsFor(ReasonCode.EMPTY_FOLDER, placeholder, context.now)
                findings += Finding(placeholder, SuggestionCategory.EMPTY_FOLDER, reasons)
            }
        }

        val eligible = findings.filter { finding ->
            // Below a volume this scan read, not whitelisted meanwhile, and allowed by the guard.
            rootsWalked.any { StoragePaths.isSameOrDescendant(finding.file.path, it) } &&
                !matcher.matches(finding.file.path) &&
                guard.isEligible(finding.file, finding.category)
        }
        return SuggestionEngine().build(eligible, context)
    }

    private companion object {
        /** Files are written to the index this many at a time: few transactions, little memory held. */
        const val INDEX_BATCH_SIZE = 500
    }
}
