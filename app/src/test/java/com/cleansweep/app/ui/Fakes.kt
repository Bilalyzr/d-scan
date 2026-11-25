package com.cleansweep.app.ui

import com.cleansweep.app.domain.model.CategoryTotal
import com.cleansweep.app.domain.model.DuplicateGroup
import com.cleansweep.app.domain.model.FileHashUpdate
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionCategoryTotal
import com.cleansweep.app.domain.model.SuggestionDraft
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.domain.model.TierTotal
import com.cleansweep.app.domain.model.TrashEntry
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.repository.SuggestionRepository
import com.cleansweep.app.domain.repository.TrashRepository
import com.cleansweep.app.domain.scanner.ScanControl
import com.cleansweep.app.domain.scanner.ScanProgress
import com.cleansweep.app.domain.scanner.ScanRequest
import com.cleansweep.app.domain.scanner.ScanState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/*
 * In-memory stand-ins for what the screens talk to, so that ViewModels can be tested on the plain JVM, without
 * Android and without a database. They keep the behaviour the screens rely on: what a Flow answers, and what a
 * call changes. The scanning and hashing half of the index is not a screen's business, and refuses to be used.
 */

internal class FakeFileIndexRepository : FileIndexRepository {

    /** Everything indexed, in one list. Change it and every observer hears of it. */
    val files = MutableStateFlow<List<IndexedFile>>(emptyList())
    val duplicateGroups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    private val lastScan = MutableStateFlow<Long?>(null)

    /** Every lock the screens set, in order. */
    val lockChanges = ArrayList<Pair<Long, Boolean>>()

    var lastScanAt: Long?
        get() = lastScan.value
        set(value) {
            lastScan.value = value
        }

    fun set(
        vararg indexed: IndexedFile,
    ) {
        files.value = indexed.toList()
    }

    override suspend fun getById(id: Long): IndexedFile? = files.value.firstOrNull { it.id == id }

    override suspend fun getByPath(path: String): IndexedFile? = files.value.firstOrNull { it.path == path }

    override suspend fun getByIds(ids: Collection<Long>): List<IndexedFile> = files.value.filter { it.id in ids }

    override fun observeById(id: Long): Flow<IndexedFile?> = files.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getCopiesWithHash(fullHash: String): List<IndexedFile> = files.value
        .filter { it.fullHash == fullHash && !it.isDirectory }
        .sortedWith(compareBy<IndexedFile>({ it.lastModified }, { it.path }))

    override suspend fun getDuplicateGroups(): List<DuplicateGroup> = duplicateGroups.value

    override fun observeDuplicateGroups(): Flow<List<DuplicateGroup>> = duplicateGroups

    override suspend fun setOriginalLocked(
        fileId: Long,
        locked: Boolean,
    ) {
        lockChanges += fileId to locked
        files.value = files.value.map { if (it.id == fileId) it.copy(isOriginalLocked = locked) else it }
    }

    override fun observeFileCount(): Flow<Int> = files.map { list -> list.count { !it.isDirectory } }

    override fun observeCategoryTotals(): Flow<List<CategoryTotal>> = files.map { list ->
        list.filter { !it.isDirectory }
            .groupBy { it.category }
            .map { (category, group) -> CategoryTotal(category, group.size, group.sumOf { it.sizeBytes }) }
    }

    override fun observeLastScanTimestamp(): Flow<Long?> = lastScan

    override suspend fun removeFiles(paths: Collection<String>) {
        files.value = files.value.filterNot { it.path in paths }
    }

    override suspend fun applyScanBatch(
        observed: List<IndexedFile>,
        scanTimestamp: Long,
    ): List<IndexedFile> = unused("applyScanBatch")

    override suspend fun deleteStaleFiles(
        scanStartedAt: Long,
        root: String,
        categories: Collection<FileCategory>,
    ): Int = unused("deleteStaleFiles")

    override suspend fun replaceEmptyFolderPlaceholders(
        directories: List<IndexedFile>,
        analysisTimestamp: Long,
    ): List<IndexedFile> = unused("replaceEmptyFolderPlaceholders")

    override suspend fun getSizeCollisionCandidates(minSizeBytes: Long): List<IndexedFile> =
        unused("getSizeCollisionCandidates")

    override suspend fun updatePartialHashes(updates: List<FileHashUpdate>): Unit = unused("updatePartialHashes")

    override suspend fun getPartialHashCollisionCandidates(): List<IndexedFile> =
        unused("getPartialHashCollisionCandidates")

    override suspend fun updateFullHashes(updates: List<FileHashUpdate>): Unit = unused("updateFullHashes")

    override suspend fun getJunkCandidates(
        largeFileThresholdBytes: Long,
        junkExtensions: Collection<String>,
    ): List<IndexedFile> = unused("getJunkCandidates")

    override suspend fun clearIndex(): Unit = unused("clearIndex")

    private fun unused(
        name: String,
    ): Nothing = throw UnsupportedOperationException("A screen never calls $name")
}

/** Keeps suggestions as the database would: hidden when dismissed, and put back on the next rebuild. */
internal class FakeSuggestionRepository : SuggestionRepository {

    val items = MutableStateFlow<List<Suggestion>>(emptyList())

    fun set(
        vararg suggestions: Suggestion,
    ) {
        items.value = suggestions.toList()
    }

    fun add(
        vararg suggestions: Suggestion,
    ) {
        items.value = items.value + suggestions
    }

    override fun observeSuggestions(
        category: SuggestionCategory?,
        includeDismissed: Boolean,
    ): Flow<List<Suggestion>> = items.map { list ->
        list.asSequence()
            .filter { category == null || it.category == category }
            .filter { includeDismissed || !it.isDismissed }
            .sortedWith(compareByDescending<Suggestion> { it.confidenceScore }.thenByDescending { it.file.sizeBytes }.thenBy { it.file.path })
            .toList()
    }

    override fun observeTierTotals(): Flow<List<TierTotal>> = items.map { list ->
        list.filter { !it.isDismissed }
            .groupBy { it.tier }
            .map { (tier, group) -> TierTotal(tier, group.size, group.sumOf { it.file.sizeBytes }) }
    }

    override fun observeCategoryTotals(): Flow<List<SuggestionCategoryTotal>> = items.map { list ->
        list.filter { !it.isDismissed }
            .groupBy { it.category }
            .map { (category, group) -> SuggestionCategoryTotal(category, group.size, group.sumOf { it.file.sizeBytes }) }
    }

    override fun observeActiveCount(): Flow<Int> = items.map { list -> list.count { !it.isDismissed } }

    override suspend fun replaceAll(
        suggestions: List<SuggestionDraft>,
    ): Unit = unused("replaceAll")

    override suspend fun setDismissed(
        fileIds: Collection<Long>,
        dismissed: Boolean,
    ) {
        items.value = items.value.map { if (it.fileId in fileIds) it.copy(isDismissed = dismissed) else it }
    }

    override suspend fun deleteForFiles(
        fileIds: Collection<Long>,
    ) {
        items.value = items.value.filterNot { it.fileId in fileIds }
    }

    override suspend fun getActiveFileIds(
        fileIds: Collection<Long>,
    ): Set<Long> = items.value.filter { it.fileId in fileIds && !it.isDismissed }.map { it.fileId }.toSet()

    override suspend fun clearSuggestions(): Unit = unused("clearSuggestions")

    private fun unused(
        name: String,
    ): Nothing = throw UnsupportedOperationException("This test never calls $name")
}

internal class FakeTrashRepository : TrashRepository {

    val entries = MutableStateFlow<List<TrashEntry>>(emptyList())
    private var nextId = 1L

    fun set(
        vararg trash: TrashEntry,
    ) {
        entries.value = trash.map { it.copy(id = if (it.id == 0L) nextId++ else it.id) }
    }

    override fun observeEntries(): Flow<List<TrashEntry>> = entries.map { list -> list.sortedByDescending { it.deletedAt } }

    override fun observeEntryCount(): Flow<Int> = entries.map { it.size }

    override fun observeTotalBytes(): Flow<Long> = entries.map { list -> list.sumOf { it.sizeBytes } }

    override suspend fun getAll(): List<TrashEntry> = entries.value

    override suspend fun getByIds(ids: Collection<Long>): List<TrashEntry> = entries.value.filter { it.id in ids }

    override suspend fun getById(id: Long): TrashEntry? = entries.value.firstOrNull { it.id == id }

    override suspend fun count(): Int = entries.value.size

    override suspend fun getByOriginalPath(originalPath: String): TrashEntry? =
        entries.value.firstOrNull { it.originalPath == originalPath }

    override suspend fun record(
        entry: TrashEntry,
    ): TrashEntry = entry.copy(id = nextId++).also { recorded -> entries.value = entries.value + recorded }

    override suspend fun getExpired(
        now: Long,
    ): List<TrashEntry> = entries.value.filter { it.expiresAt <= now }

    override suspend fun removeByIds(
        ids: Collection<Long>,
    ) {
        entries.value = entries.value.filterNot { it.id in ids }
    }

    override suspend fun clearManifest(): Unit = unused("clearManifest")

    private fun unused(
        name: String,
    ): Nothing = throw UnsupportedOperationException("This test never calls $name")
}

/** The scan service as the screens see it. The test decides what each started scan turns into. */
internal class FakeScanControl : ScanControl {

    override val state = MutableStateFlow<ScanState>(ScanState.Idle)

    /** What a started scan reports first. */
    var onStart: () -> ScanState = { ScanState.Running(ScanProgress(), isPaused = false) }

    var starts: Int = 0
        private set

    var pauses: Int = 0
        private set

    var resumes: Int = 0
        private set

    val requests = ArrayList<ScanRequest>()

    override val isRunning: Boolean
        get() = state.value is ScanState.Running

    override fun start(
        request: ScanRequest,
    ): Boolean {
        starts++
        requests += request
        state.value = onStart()
        return true
    }

    override fun pause(): Boolean {
        pauses++
        return setPaused(true)
    }

    override fun resume(): Boolean {
        resumes++
        return setPaused(false)
    }

    private fun setPaused(
        paused: Boolean,
    ): Boolean {
        val running = state.value as? ScanState.Running ?: return false
        state.value = running.copy(isPaused = paused)
        return true
    }

    override fun cancel(): Boolean {
        val running = state.value as? ScanState.Running ?: return false
        state.value = ScanState.Cancelled(running.progress)
        return true
    }

    override fun reset() {
        state.value = ScanState.Idle
    }
}
