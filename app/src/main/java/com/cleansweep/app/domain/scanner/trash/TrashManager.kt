package com.cleansweep.app.domain.scanner.trash

import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.TrashEntry
import com.cleansweep.app.domain.model.TrashPolicy
import com.cleansweep.app.domain.model.WhitelistMatcher
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.repository.SuggestionRepository
import com.cleansweep.app.domain.repository.TrashRepository
import com.cleansweep.app.domain.repository.WhitelistRepository
import com.cleansweep.app.domain.scanner.duplicates.OriginalLockPolicy
import com.cleansweep.app.domain.util.StoragePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * Safe Trash: the app's recycle bin. Nothing the user deletes is ever gone at once.
 *
 * Trashing an entry only renames it, in its own folder, to a hidden name (see [TrashNaming]). The entry can be
 * put back for 30 days, and is deleted for good afterwards by [purgeExpired], or at once by
 * [emptyTrashPermanently]. The trash manifest records every entry, and it is always written before an entry is
 * touched, so there is never a trashed entry that the app does not know about.
 *
 * ## What is checked before anything is trashed
 *
 * The suggestions in the database only say what was true when the last scan ran. Each file is checked again
 * before it is moved:
 *
 * - it is still suggested, and the user has not dismissed the suggestion;
 * - it is not whitelisted;
 * - it is not a locked Original, and not the copy the Original Lock policy keeps for its duplicate group
 *   among the copies that are still on storage, so a group never loses every copy at once;
 * - it still has the size and modification time the suggestion was based on. A file that changed since is left
 *   alone until a new scan has looked at it.
 *
 * The manager moves files, never directories with something in them. Every call returns what happened to each
 * entry; nothing is ever dropped silently, and a refusal never touches the file.
 *
 * All methods are main-safe, and all of them that change the trash take turns.
 */
class TrashManager(
    private val trash: TrashRepository,
    private val fileIndex: FileIndexRepository,
    private val suggestions: SuggestionRepository,
    private val whitelist: WhitelistRepository,
    private val storage: TrashStorage,
    private val mediaScanner: MediaScanner,
    private val purgeScheduler: PurgeScheduler,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val writeLock = Mutex()

    /** Whether this process has asked for the recurring purge, so that it asks and cancels only on a change. */
    @Volatile
    private var purgeIsScheduled: Boolean = false

    /** Every entry in Safe Trash, newest first. */
    fun observeEntries(): Flow<List<TrashEntry>> = trash.observeEntries()

    /** Space that emptying Safe Trash would free. */
    fun observeTotalBytes(): Flow<Long> = trash.observeTotalBytes()

    /** Moves indexed files to Safe Trash, after checking each one. */
    suspend fun trash(fileIds: Collection<Long>): TrashResult = underWriteLock { moveUnlocked(fileIds) }

    /** Puts trashed entries back where they came from. */
    suspend fun restore(entryIds: Collection<Long>): RestoreResult = underWriteLock { restoreUnlocked(entryIds) }

    /** Deletes every entry for good whose 30 days are over. */
    suspend fun purgeExpired(): PurgeResult = underWriteLock { purgeUnlocked(trash.getExpired(now())) }

    /**
     * Deletes every entry for good, whatever its age. This is the only call that cannot be undone, so the UI
     * must have the user's explicit confirmation first.
     */
    suspend fun emptyTrashPermanently(): PurgeResult = underWriteLock { purgeUnlocked(trash.getAll()) }

    /**
     * Brings the manifest back in step with storage and purges what has expired. Meant for every app start.
     * Never throws: a failed check is reported, and the next start-up simply tries again.
     */
    suspend fun maintain(): MaintenanceResult = underWriteLock {
        try {
            val forgotten = forgetRowsWithoutEntry()
            MaintenanceResult(purgeUnlocked(trash.getExpired(now())), forgotten)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            MaintenanceResult(PurgeResult.NOTHING, 0, failure)
        }
    }

    // ---- Moving to the trash ----

    private suspend fun moveUnlocked(fileIds: Collection<Long>): TrashResult {
        val ids = fileIds.distinct()
        if (ids.isEmpty()) {
            return TrashResult.NOTHING
        }
        val files = fileIndex.getByIds(ids).associateBy { it.id }
        val suggested = suggestions.getActiveFileIds(ids)
        val matcher = whitelist.matcher()
        val keptOriginals = defaultOriginalIds()

        val trashed = ArrayList<TrashEntry>()
        val refused = ArrayList<Refusal>()

        // Every file that is no longer where the index says leaves the index, and with it its suggestion.
        val goneFromIndex = ArrayList<String>()
        val rescan = ArrayList<String>()
        for (id in ids) {
            val file = files[id]
            if (file == null) {
                refused += Refusal(id, path = "", reason = TrashRefusal.NOT_INDEXED)
                continue
            }
            val refusal = refusalFor(file, id in suggested, matcher, keptOriginals)
            if (refusal != null) {
                if (refusal == TrashRefusal.GONE) {
                    goneFromIndex += file.path
                }
                refused += Refusal(id, file.path, refusal)
                continue
            }
            when (val outcome = moveToTrash(file)) {
                is MoveOne.Moved -> {
                    trashed += outcome.entry
                    goneFromIndex += file.path
                    rescan += file.path
                    rescan += outcome.entry.trashPath
                }

                is MoveOne.Refused -> {
                    refused += Refusal(id, file.path, outcome.reason)
                    if (outcome.reason == TrashRefusal.GONE) {
                        goneFromIndex += file.path
                    }
                }
            }
        }
        // Should the app stop before this point, the next scan drops what is left of these rows on its own.
        fileIndex.removeFiles(goneFromIndex)
        if (rescan.isNotEmpty()) {
            mediaScanner.scan(rescan)
        }
        updatePurgeSchedule()
        return TrashResult(trashed, refused)
    }

    /** The first reason not to touch [file], or null when it may be trashed. */
    private fun refusalFor(
        file: IndexedFile,
        suggested: Boolean,
        matcher: WhitelistMatcher,
        keptOriginals: Set<Long>,
    ): TrashRefusal? {
        if (!suggested) {
            return TrashRefusal.NOT_SUGGESTED
        }
        if (matcher.matches(file.path)) {
            return TrashRefusal.WHITELISTED
        }
        if (file.isOriginalLocked || file.id in keptOriginals) {
            return TrashRefusal.PROTECTED_ORIGINAL
        }
        val stamp = storage.stamp(file.path) ?: return TrashRefusal.GONE
        return if (isStillAsSuggested(file, stamp)) null else TrashRefusal.CHANGED_SINCE_SUGGESTED
    }

    /**
     * Whether the entry on storage is still what the scan suggested. For a folder that means empty: a folder that
     * received a file since the scan is something else now, and moving it would hide that file too.
     */
    private fun isStillAsSuggested(
        file: IndexedFile,
        stamp: TrashStamp,
    ): Boolean = if (file.isDirectory) {
        stamp.isDirectory && stamp.lastModified == file.lastModified && storage.isEmptyDirectory(file.path)
    } else {
        !stamp.isDirectory && stamp.sizeBytes == file.sizeBytes && stamp.lastModified == file.lastModified
    }

    private suspend fun moveToTrash(file: IndexedFile): MoveOne {
        val deletedAt = now()
        val entry = trash.record(
            TrashEntry(
                originalPath = file.path,
                trashPath = TrashNaming.trashedPath(file.path, TrashNaming.newToken()),
                sizeBytes = file.sizeBytes,
                deletedAt = deletedAt,
                expiresAt = TrashPolicy.expiresAt(deletedAt),
            ),
        )
        // The row is in the database before the entry is touched. If the app dies before or during the move,
        // the row describes a move that did not happen, which the next start-up detects and forgets. The
        // other way round, a moved entry without a row, cannot occur.
        val outcome = when (val moved = storage.move(file.path, entry.trashPath)) {
            MoveResult.Moved -> MoveOne.Moved(entry)
            MoveResult.SourceMissing -> MoveOne.Refused(TrashRefusal.GONE)
            MoveResult.ReadOnly -> MoveOne.Refused(TrashRefusal.STORAGE_READ_ONLY)
            MoveResult.TargetExists, is MoveResult.Failed -> MoveOne.Refused(TrashRefusal.COULD_NOT_MOVE)
        }
        if (outcome !is MoveOne.Moved) {
            trash.removeByIds(listOf(entry.id))
        }
        return outcome
    }

    /**
     * For every duplicate group, the copy that the Original Lock policy keeps, judged by the copies that are
     * still on storage.
     *
     * The index can be behind: a copy may have been removed by another app since the last scan. Going by the
     * index would then protect a copy that is gone, and leave the copy that took its place unprotected. Asked
     * for every remaining copy at once, that would have deleted the whole group.
     */
    private suspend fun defaultOriginalIds(): Set<Long> =
        fileIndex.getDuplicateGroups()
            .map { group -> group.copies.filter { storage.stamp(it.path) != null } }
            .filter { it.isNotEmpty() }
            .mapTo(HashSet()) { OriginalLockPolicy.selectOriginal(it).id }

    // ---- Restoring ----

    private suspend fun restoreUnlocked(entryIds: Collection<Long>): RestoreResult {
        val restored = ArrayList<String>()
        val nothingLeft = ArrayList<String>()
        val failures = ArrayList<RestoreFailureItem>()
        for (entry in trash.getByIds(entryIds.distinct())) {
            when (val outcome = putBack(entry)) {
                is PutBack.Back -> {
                    trash.removeByIds(listOf(entry.id))
                    restored += outcome.path
                    mediaScanner.scan(listOf(outcome.path))
                }

                is PutBack.NothingLeft -> {
                    trash.removeByIds(listOf(entry.id))
                    nothingLeft += entry.originalPath
                }

                is PutBack.Not -> failures += RestoreFailureItem(entry.originalPath, outcome.reason)
            }
        }
        updatePurgeSchedule()
        return RestoreResult(restored, nothingLeft, failures)
    }

    private fun putBack(entry: TrashEntry): PutBack {
        // Only an entry that carries the mark of this app's trash is ever moved. A row that points at anything
        // else is not ours: that file is left alone and the row is forgotten.
        if (!TrashNaming.isTrashedName(StoragePaths.nameOf(entry.trashPath))) {
            return PutBack.NothingLeft
        }
        if (storage.stamp(entry.trashPath) == null) {
            return PutBack.NothingLeft
        }
        val parent = StoragePaths.parentOf(entry.originalPath) ?: return PutBack.Not(RestoreFailure.STORAGE_UNAVAILABLE)
        if (!storage.ensureDirectory(parent)) {
            return PutBack.Not(RestoreFailure.STORAGE_UNAVAILABLE)
        }
        val target = pathToReturnTo(entry.originalPath) ?: return PutBack.Not(RestoreFailure.COULD_NOT_MOVE)
        return when (val moved = storage.move(entry.trashPath, target)) {
            MoveResult.Moved -> PutBack.Back(target)
            MoveResult.ReadOnly -> PutBack.Not(RestoreFailure.STORAGE_READ_ONLY)
            MoveResult.SourceMissing -> PutBack.NothingLeft
            MoveResult.TargetExists, is MoveResult.Failed -> PutBack.Not(RestoreFailure.COULD_NOT_MOVE)
        }
    }

    /** The entry's own path, or the first free `name (restored)` next to it when that one is taken. */
    private fun pathToReturnTo(originalPath: String): String? {
        if (storage.stamp(originalPath) == null) {
            return originalPath
        }
        val parent = StoragePaths.parentOf(originalPath) ?: return null
        val name = StoragePaths.nameOf(originalPath)
        var candidate = StoragePaths.join(parent, TrashNaming.restoredName(name))
        var attempt = 2
        while (storage.stamp(candidate) != null) {
            if (attempt > MAX_NAMES_NEXT_TO_ORIGINAL) {
                return null
            }
            candidate = StoragePaths.join(parent, TrashNaming.restoredName(name, attempt))
            attempt++
        }
        return candidate
    }

    // ---- Deleting for good ----

    private suspend fun purgeUnlocked(entries: List<TrashEntry>): PurgeResult {
        var deleted = 0
        var bytesFreed = 0L
        var missing = 0
        var foldersNoLongerEmpty = 0
        var failed = 0
        var forgotten = 0
        val finished = ArrayList<Long>()

        for (entry in entries) {
            if (!TrashNaming.isTrashedName(StoragePaths.nameOf(entry.trashPath))) {
                // Not an entry of this trash. Never deleted, only forgotten.
                forgotten++
                finished += entry.id
                continue
            }
            val stamp = storage.stamp(entry.trashPath)
            val result = when {
                stamp == null -> DeleteResult.Missing
                stamp.isDirectory -> storage.deleteEmptyDirectory(entry.trashPath)
                else -> storage.deleteFile(entry.trashPath)
            }
            when (result) {
                DeleteResult.Deleted -> {
                    deleted++
                    bytesFreed += entry.sizeBytes
                    finished += entry.id
                }

                DeleteResult.Missing -> {
                    missing++
                    finished += entry.id
                }

                // A folder with something in it was not put there by the trash. Nothing in it is touched, and
                // the entry stays so that the user can see it and restore it.
                DeleteResult.NotEmpty -> foldersNoLongerEmpty++

                DeleteResult.ReadOnly, is DeleteResult.Failed -> failed++
            }
        }
        trash.removeByIds(finished)
        updatePurgeSchedule()
        return PurgeResult(deleted, bytesFreed, missing, foldersNoLongerEmpty, failed, forgotten)
    }

    // ---- Housekeeping ----

    /**
     * Forgets the manifest rows whose entry is not where the row says it rests. A row is written before its entry
     * is moved, so a row without an entry means the app stopped in between: the file is still where it always was
     * and the next scan treats it like any other file. It also covers an entry that something else removed.
     */
    private suspend fun forgetRowsWithoutEntry(): Int {
        val orphans = trash.getAll().filter { storage.stamp(it.trashPath) == null }.map { it.id }
        trash.removeByIds(orphans)
        return orphans.size
    }

    /** Plans the recurring purge as long as the trash holds something, and drops the plan once it is empty. */
    private suspend fun updatePurgeSchedule() {
        try {
            if (trash.count() > 0) {
                if (!purgeIsScheduled) {
                    purgeScheduler.schedule()
                    purgeIsScheduled = true
                }
            } else if (purgeIsScheduled) {
                purgeScheduler.cancel()
                purgeIsScheduled = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The entries are safe without the plan: the purge also runs at every app start.
            purgeIsScheduled = false
        }
    }

    private suspend fun <T> underWriteLock(block: suspend () -> T): T =
        writeLock.withLock {
            withContext(ioDispatcher) { block() }
        }

    private fun now(): Long = clock.millis()

    private sealed interface MoveOne {
        data class Moved(
            val entry: TrashEntry,
        ) : MoveOne

        data class Refused(
            val reason: TrashRefusal,
        ) : MoveOne
    }

    private sealed interface PutBack {
        data class Back(
            val path: String,
        ) : PutBack

        /** Nothing is left in the trash to put back. */
        data object NothingLeft : PutBack

        data class Not(
            val reason: RestoreFailure,
        ) : PutBack
    }

    private companion object {
        /** How many `name (restored n)` variants are tried before an entry is left in the trash. */
        const val MAX_NAMES_NEXT_TO_ORIGINAL: Int = 100
    }
}
