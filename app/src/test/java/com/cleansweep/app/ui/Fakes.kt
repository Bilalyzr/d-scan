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
import com.cleansweep.app.domain.model.WhitelistMatcher
import com.cleansweep.app.domain.model.WhitelistRule
import com.cleansweep.app.domain.model.WhitelistRuleType
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.repository.SuggestionRepository
import com.cleansweep.app.domain.repository.TrashRepository
import com.cleansweep.app.domain.repository.WhitelistRepository
import com.cleansweep.app.domain.scanner.ScanControl
import com.cleansweep.app.domain.scanner.ScanProgress
import com.cleansweep.app.domain.scanner.ScanRequest
import com.cleansweep.app.domain.scanner.ScanState
import com.cleansweep.app.domain.scanner.trash.PurgeResult
import com.cleansweep.app.domain.scanner.trash.Refusal
import com.cleansweep.app.domain.scanner.trash.RestoreResult
import com.cleansweep.app.domain.scanner.trash.SafeTrash
import com.cleansweep.app.domain.scanner.trash.TrashResult
import com.cleansweep.app.domain.util.StoragePaths
import com.cleansweep.app.file
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/*
 * In-memory stand-ins for what the screens talk to, so that ViewModels can be tested on the plain JVM, without
 * Android and without a database. They keep the behaviour the screens rely on: what a Flow answers, and what a
 * call changes. The scanning and hashing half of the index is not a screen's business, and refuses to be used.
 */

internal class FakeFileIndexRepository : FileIndexRepository {

    /** Everything indexed, in one list. Change it and every observer hears of it. */
    val files = MutableStateFlow<List<IndexedFile>>(emptyList())
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

    override suspend fun getDuplicateGroups(): List<DuplicateGroup> = observeDuplicateGroups().first()

    /** Worked out from the files, as the database works it out, so that a lock set through [setOriginalLocked] counts at once. */
    override fun observeDuplicateGroups(): Flow<List<DuplicateGroup>> = files.map { list ->
        val byHash = LinkedHashMap<String, MutableList<IndexedFile>>()
        for (indexed in list) {
            val hash = indexed.fullHash
            if (hash != null && !indexed.isDirectory) {
                byHash.getOrPut(hash) { ArrayList() } += indexed
            }
        }
        byHash.filterValues { it.size >= 2 }
            .map { (hash, copies) -> DuplicateGroup(hash, copies.sortedWith(compareBy({ it.lastModified }, { it.path }))) }
    }

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

/**
 * Keeps suggestions as the database would: hidden when dismissed, and put back as dismissed on a rebuild. A
 * rebuilt suggestion gets its file from [files], as the joined query would give it.
 */
internal class FakeSuggestionRepository(
    private val files: FakeFileIndexRepository,
) : SuggestionRepository {

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
    ) {
        val dismissed = items.value.filter { it.isDismissed }.map { it.fileId }.toSet()
        items.value = suggestions.map { it.stored(dismissed) }
    }

    override suspend fun replaceForFiles(
        fileIds: Collection<Long>,
        suggestions: List<SuggestionDraft>,
    ) {
        require(suggestions.all { it.fileId in fileIds })
        val dismissed = items.value.filter { it.fileId in fileIds && it.isDismissed }.map { it.fileId }.toSet()
        items.value = items.value.filterNot { it.fileId in fileIds } + suggestions.map { it.stored(dismissed) }
    }

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

    override suspend fun clearSuggestions() {
        items.value = emptyList()
    }

    private fun SuggestionDraft.stored(
        dismissed: Set<Long>,
    ): Suggestion = Suggestion(
        fileId = fileId,
        category = category,
        confidenceScore = confidenceScore,
        primaryReason = primaryReason,
        reasons = reasons,
        isDismissed = fileId in dismissed,
        file = files.files.value.firstOrNull { it.id == fileId } ?: file("/missing/$fileId"),
    )
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

/**
 * Safe Trash over the fake manifest. Moving a file to the trash takes it out of the index, with its suggestion,
 * as the real one does. A test can decide what a call refuses.
 */
internal class FakeSafeTrash(
    private val index: FakeFileIndexRepository,
    private val suggestions: FakeSuggestionRepository,
    private val manifest: FakeTrashRepository = FakeTrashRepository(),
) : SafeTrash {

    /** Every list of file ids the screens asked to trash, in order. */
    val askedToTrash = ArrayList<List<Long>>()

    /** Refusals the test wants back from the next call. Their files are left where they are. */
    var nextRefusals: List<Refusal> = emptyList()

    override fun observeEntries(): Flow<List<TrashEntry>> = manifest.observeEntries()

    override fun observeTotalBytes(): Flow<Long> = manifest.observeTotalBytes()

    override suspend fun trash(
        fileIds: Collection<Long>,
    ): TrashResult {
        val ids = fileIds.distinct()
        askedToTrash += ids
        val refusedIds = nextRefusals.map { it.fileId }.toSet()
        val moved = ArrayList<TrashEntry>()
        val movedIds = ArrayList<Long>()
        for (id in ids) {
            if (id in refusedIds) {
                continue
            }
            val file = index.files.value.firstOrNull { it.id == id } ?: continue
            moved += manifest.record(
                TrashEntry(
                    originalPath = file.path,
                    trashPath = StoragePaths.join(
                        StoragePaths.parentOf(file.path).orEmpty(),
                        ".cleansweep-trashed-test-${file.name}",
                    ),
                    sizeBytes = file.sizeBytes,
                    deletedAt = 1_000L + id,
                    expiresAt = 1_000L + id + 30L * 24 * 60 * 60 * 1000,
                ),
            )
            movedIds += id
        }
        index.removeFiles(moved.map { it.originalPath })
        suggestions.deleteForFiles(movedIds)
        val refusals = nextRefusals
        nextRefusals = emptyList()
        return TrashResult(moved, refusals)
    }

    override suspend fun restore(
        entryIds: Collection<Long>,
    ): RestoreResult {
        val restored = ArrayList<String>()
        val gone = ArrayList<String>()
        for (entry in manifest.getByIds(entryIds)) {
            manifest.removeByIds(listOf(entry.id))
            restored += entry.originalPath
        }
        return RestoreResult(restored, gone, emptyList())
    }

    override suspend fun emptyTrashPermanently(): PurgeResult {
        val all = manifest.entries.value
        manifest.entries.value = emptyList()
        return PurgeResult(all.size, all.sumOf { it.sizeBytes }, 0, 0, 0, 0)
    }
}

/** The rules the user made, matching as the real repository does. */
internal class FakeWhitelistRepository(
    private val index: FakeFileIndexRepository,
    private val suggestions: FakeSuggestionRepository,
) : WhitelistRepository {

    val rules = MutableStateFlow<List<WhitelistRule>>(emptyList())
    private var nextId = 1L

    override fun observeRules(): Flow<List<WhitelistRule>> = rules

    override suspend fun getRules(): List<WhitelistRule> = rules.value

    override fun observeRuleCount(): Flow<Int> = rules.map { it.size }

    override suspend fun addRule(
        path: String,
        type: WhitelistRuleType,
    ): WhitelistRule {
        rules.value.firstOrNull { it.path == path }?.let { return it }
        val rule = WhitelistRule(id = nextId++, path = path, type = type, createdAt = 0L)
        rules.value = listOf(rule) + rules.value
        // The index may hold a path in another shape than the rule (a Windows test host canonicalises to "C:/...").
        val matcher = matcher()
        val protected = index.files.value.filter { matcher.matches(StoragePaths.canonicalize(it.path)) }
        index.removeFiles(protected.map { it.path })
        suggestions.deleteForFiles(protected.map { it.id })
        return rule
    }

    override suspend fun removeRule(
        id: Long,
    ) {
        rules.value = rules.value.filterNot { it.id == id }
    }

    override suspend fun matcher(): WhitelistMatcher = WhitelistMatcher.from(rules.value)

    override suspend fun isWhitelisted(
        path: String,
    ): Boolean = matcher().matches(StoragePaths.canonicalize(path))
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
