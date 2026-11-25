package com.cleansweep.app.ui.suggestions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleansweep.app.di.IoDispatcher
import com.cleansweep.app.domain.model.DuplicateGroup
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.domain.model.TierTotal
import com.cleansweep.app.domain.model.WhitelistRule
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.repository.SuggestionRepository
import com.cleansweep.app.domain.scanner.ScanControl
import com.cleansweep.app.domain.scanner.ScanState
import com.cleansweep.app.domain.scanner.StorageAccess
import com.cleansweep.app.domain.scanner.clean.CleanSelectionStore
import com.cleansweep.app.domain.scanner.duplicates.AutoSelectRule
import com.cleansweep.app.domain.scanner.duplicates.DuplicateGroupEditor
import com.cleansweep.app.domain.scanner.duplicates.KeepResult
import com.cleansweep.app.domain.scanner.duplicates.OriginalLockPolicy
import com.cleansweep.app.domain.scanner.trash.SafeTrash
import com.cleansweep.app.domain.scanner.whitelist.ProtectionResult
import com.cleansweep.app.domain.scanner.whitelist.WhitelistManager
import com.cleansweep.app.ui.navigation.Routes
import com.cleansweep.app.ui.clean.CleanOutcome
import com.cleansweep.app.ui.clean.Cleaner
import com.cleansweep.app.ui.clean.CleanSummary
import kotlinx.coroutines.CoroutineDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The three tabs of the hub (PRD screen 4). */
enum class HubTab {
    SAFE_TO_CLEAN,
    REVIEW_RECOMMENDED,
    ALL_DUPLICATES,
}

/** What the list is narrowed down to. Both parts are optional; the dashboard's tiles set the second. */
data class HubFilter(
    val category: SuggestionCategory? = null,
    val type: FileCategory? = null,
) {
    val isEmpty: Boolean
        get() = category == null && type == null

    fun matches(
        suggestion: Suggestion,
    ): Boolean = (category == null || suggestion.category == category) &&
        (type == null || suggestion.file.category == type)
}

/** A group of identical files as the Duplicates tab lists it. */
data class DuplicateGroupRow(
    val group: DuplicateGroup,
    /** The copy the group keeps: it is named first, and it never leaves by itself. */
    val original: IndexedFile,
) {
    val name: String
        get() = original.name

    val category: FileCategory
        get() = original.category

    /** Bytes that would be freed if every copy but the kept ones were removed. */
    val reclaimableBytes: Long
        get() = group.copies
            .filter { it.id != original.id && !it.isOriginalLocked }
            .sumOf { it.sizeBytes }
}

/** What happened in the hub, for the message that reports it. Each is shown once. */
sealed interface HubEvent {
    data class MovedToTrash(
        val outcome: CleanOutcome,
    ) : HubEvent

    data class Restored(
        val count: Int,
    ) : HubEvent

    data object NothingToRestore : HubEvent

    data class Ignored(
        val fileId: Long,
    ) : HubEvent

    data class Unignored(
        val fileId: Long,
    ) : HubEvent

    data class Excluded(
        val rule: WhitelistRule,
    ) : HubEvent

    data class RuleApplied(
        val rule: AutoSelectRule,
        val groupsChanged: Int,
    ) : HubEvent
}

data class HubUiState(
    val tab: HubTab = HubTab.SAFE_TO_CLEAN,
    val filter: HubFilter = HubFilter(),
    val storageAccessGranted: Boolean = true,

    /** False until a scan has written to the index. */
    val hasScanned: Boolean = false,
    val safe: TierTotal? = null,
    val review: TierTotal? = null,

    /** What the open tab lists: suggestions in the two tier tabs, and nothing in the duplicates tab. */
    val items: List<Suggestion> = emptyList(),
    val groups: List<DuplicateGroupRow> = emptyList(),

    /** Everything that is marked for deletion, whichever tab it is on. */
    val selected: List<Suggestion> = emptyList(),
    val showIgnored: Boolean = false,
    val ignoredCount: Int = 0,
    val isScanning: Boolean = false,

    /** While files are being moved, or a rule is being applied. */
    val busy: Boolean = false,
    val confirming: Boolean = false,
) {
    val summary: CleanSummary
        get() = CleanSummary.of(selected)

    /** False while a scan runs: what it found is only complete once it has finished. */
    val canClean: Boolean
        get() = storageAccessGranted && !isScanning && !busy && selected.isNotEmpty()
}

/** Everything the index and the suggestions answer, before the open tab and the filter are applied. */
private data class Offer(
    val active: List<Suggestion>,
    val ignored: List<Suggestion>,
    val safe: TierTotal?,
    val review: TierTotal?,
    val lastScanAt: Long?,
    val groups: List<DuplicateGroupRow>,
)

private data class View(
    val tab: HubTab,
    val filter: HubFilter,
    val showIgnored: Boolean,
)

private data class Controls(
    val accessGranted: Boolean,
    val busy: Boolean,
    val confirming: Boolean,
)

/**
 * The Suggestions Hub (PRD screen 4): what may be removed, by how sure the app is, with marking, a rule to mark
 * by, and the way to Safe Trash.
 *
 * What is marked is kept in the [CleanSelectionStore], which the screen of a single group and the preview share.
 */
@HiltViewModel
class SuggestionsViewModel @Inject constructor(
    private val fileIndex: FileIndexRepository,
    private val suggestions: SuggestionRepository,
    private val scans: ScanControl,
    private val storageAccess: StorageAccess,
    private val selectionStore: CleanSelectionStore,
    private val editor: DuplicateGroupEditor,
    private val whitelist: WhitelistManager,
    trash: SafeTrash,
    savedState: SavedStateHandle,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val cleaner = Cleaner(trash, selectionStore)

    private val tab = MutableStateFlow(HubTab.SAFE_TO_CLEAN)
    private val showIgnored = MutableStateFlow(false)
    private val accessGranted = MutableStateFlow(storageAccess.canReadStorage())
    private val busy = MutableStateFlow(false)
    private val confirming = MutableStateFlow(false)

    /** The last move to the trash, for the Undo next to its message. */
    private var lastOutcome: CleanOutcome? = null

    private val events = Channel<HubEvent>(Channel.BUFFERED)
    val eventFlow: Flow<HubEvent> = events.receiveAsFlow()

    private val filter = MutableStateFlow(
        HubFilter(
            category = savedState.get<String>(Routes.ARG_CATEGORY)?.let(::categoryOf),
            type = savedState.get<String>(Routes.ARG_TYPE)?.let(::typeOf),
        ),
    )

    private val offer: Flow<Offer> = combine(
        suggestions.observeSuggestions(category = null, includeDismissed = true),
        suggestions.observeTierTotals(),
        fileIndex.observeLastScanTimestamp(),
        fileIndex.observeDuplicateGroups(),
    ) { all, tiers, lastScanAt, groups ->
        Offer(
            active = all.filter { !it.isDismissed },
            ignored = all.filter { it.isDismissed },
            safe = tiers.firstOrNull { it.tier == SuggestionTier.SAFE_TO_CLEAN },
            review = tiers.firstOrNull { it.tier == SuggestionTier.REVIEW_RECOMMENDED },
            lastScanAt = lastScanAt,
            groups = groups.map { DuplicateGroupRow(it, OriginalLockPolicy.selectOriginal(it.copies)) },
        )
    }

    private val view: Flow<View> = combine(tab, filter, showIgnored, ::View)

    private val controls: Flow<Controls> = combine(accessGranted, busy, confirming, ::Controls)

    val uiState: StateFlow<HubUiState> = combine(
        offer,
        selectionStore.selection,
        scans.state,
        view,
        controls,
    ) { offered, selection, scan, shown, controlled ->
        HubUiState(
            tab = shown.tab,
            filter = shown.filter,
            storageAccessGranted = controlled.accessGranted,
            hasScanned = offered.lastScanAt != null,
            safe = offered.safe,
            review = offered.review,
            items = when (shown.tab) {
                HubTab.SAFE_TO_CLEAN -> tierItems(offered, shown, SuggestionTier.SAFE_TO_CLEAN)
                HubTab.REVIEW_RECOMMENDED -> tierItems(offered, shown, SuggestionTier.REVIEW_RECOMMENDED)
                HubTab.ALL_DUPLICATES -> emptyList()
            },
            groups = if (shown.filter.category == null || shown.filter.category == SuggestionCategory.EXACT_DUPLICATE) {
                offered.groups.filter { shown.filter.type == null || it.category == shown.filter.type }
            } else {
                emptyList()
            },
            selected = selection.selectedAmong(offered.active),
            showIgnored = shown.showIgnored,
            ignoredCount = offered.ignored.size,
            isScanning = scan is ScanState.Running,
            busy = controlled.busy,
            confirming = controlled.confirming,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HubUiState(
            filter = filter.value,
            storageAccessGranted = accessGranted.value,
            isScanning = scans.isRunning,
        ),
    )

    private fun tierItems(
        offered: Offer,
        shown: View,
        tier: SuggestionTier,
    ): List<Suggestion> = offered.active
        .filter { it.tier == tier && shown.filter.matches(it) } +
        if (shown.showIgnored) offered.ignored.filter { shown.filter.matches(it) } else emptyList()

    /** The dashboard or the system settings may have changed whether storage may be read. */
    fun onResume() {
        accessGranted.value = storageAccess.canReadStorage()
    }

    fun selectTab(
        tab: HubTab,
    ) {
        this.tab.value = tab
    }

    fun setSelected(
        suggestion: Suggestion,
        selected: Boolean,
    ) {
        // An ignored suggestion is not offered, so there is nothing to mark.
        if (!suggestion.isDismissed) selectionStore.set(suggestion, selected)
    }

    fun selectAllSafe() {
        viewModelScope.launch {
            val active = activeSuggestions()
            selectionStore.setAll(active.filter { it.tier == SuggestionTier.SAFE_TO_CLEAN }, selected = true)
        }
    }

    fun unselectAll() {
        viewModelScope.launch { selectionStore.setAll(activeSuggestions(), selected = false) }
    }

    fun setShowIgnored(
        show: Boolean,
    ) {
        showIgnored.value = show
    }

    fun clearFilter() {
        filter.value = HubFilter()
    }

    /** Starts a scan from the hub, for when nothing has been scanned yet. */
    fun startScan(): Boolean = scans.start()

    /** Opens the sheet that asks for confirmation. */
    fun requestClean() {
        if (uiState.value.canClean) confirming.value = true
    }

    fun cancelClean() {
        confirming.value = false
    }

    /** Moves everything that is marked to Safe Trash, after the sheet was confirmed. */
    fun clean() {
        val marked = uiState.value.selected
        if (marked.isEmpty() || busy.value) return
        viewModelScope.launch {
            confirming.value = false
            busy.value = true
            try {
                val outcome = cleaner.clean(marked.map { it.fileId })
                lastOutcome = outcome
                events.send(HubEvent.MovedToTrash(outcome))
            } finally {
                busy.value = false
            }
        }
    }

    /** Puts the files of the last move back where they came from. */
    fun undoLastTrash() {
        val outcome = lastOutcome ?: return
        lastOutcome = null
        viewModelScope.launch {
            busy.value = true
            try {
                val restored = cleaner.undo(outcome)
                events.send(
                    if (restored.restoredPaths.isEmpty()) HubEvent.NothingToRestore else HubEvent.Restored(restored.restoredPaths.size),
                )
            } finally {
                busy.value = false
            }
        }
    }

    /** Hides a suggestion. The file is not touched, and it stays hidden across scans. */
    fun ignore(
        suggestion: Suggestion,
    ) {
        viewModelScope.launch {
            suggestions.setDismissed(listOf(suggestion.fileId), dismissed = true)
            selectionStore.forget(listOf(suggestion.fileId))
            events.send(HubEvent.Ignored(suggestion.fileId))
        }
    }

    fun unignore(
        fileId: Long,
    ) {
        viewModelScope.launch {
            suggestions.setDismissed(listOf(fileId), dismissed = false)
            events.send(HubEvent.Unignored(fileId))
        }
    }

    /** Adds the file to the exclusions: it is never suggested again, whatever a scan finds. */
    fun neverSuggest(
        suggestion: Suggestion,
    ) {
        viewModelScope.launch {
            // The whitelist manager looks the file up on storage, which is not main-thread work.
            val rule = withContext(ioDispatcher) {
                when (val result = whitelist.protect(suggestion.file.path)) {
                    is ProtectionResult.Added -> result.rule
                    // Already behind a folder rule: there is no rule of its own to take back.
                    else -> return@withContext null
                }
            } ?: return@launch
            selectionStore.forget(listOf(suggestion.fileId))
            events.send(HubEvent.Excluded(rule))
        }
    }

    fun allowAgain(
        rule: WhitelistRule,
    ) {
        viewModelScope.launch { withContext(ioDispatcher) { whitelist.unprotect(rule) } }
    }

    /**
     * Marks the copies of every group of duplicates by a rule: all but the copy the rule keeps (PRD 4.5, auto-mark
     * rules, used on everything at once). The copy to keep is locked as the Original of its group, and the copies
     * that are redundant from then on are marked, without waiting for a new scan.
     */
    fun applyAutoSelect(
        rule: AutoSelectRule,
    ) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                val groups = fileIndex.observeDuplicateGroups().first()
                if (rule == AutoSelectRule.UNSELECT_ALL) {
                    selectionStore.setAll(activeSuggestions(), selected = false)
                    events.send(HubEvent.RuleApplied(rule, groupsChanged = 0))
                    return@launch
                }

                var changed = 0
                for (group in groups) {
                    val keeper = rule.keeperOf(group.copies) ?: continue
                    if (keeper.id == OriginalLockPolicy.selectOriginal(group.copies).id) continue
                    if (editor.keepCopy(keeper.id) == KeepResult.Kept) changed++
                }

                // The groups and their suggestions were changed above; read both again before marking.
                val freshGroups = fileIndex.observeDuplicateGroups().first()
                val kept = HashSet<Long>()
                for (group in freshGroups) {
                    kept += OriginalLockPolicy.selectOriginal(group.copies).id
                    group.copies.filter { it.isOriginalLocked }.forEach { kept += it.id }
                }
                val freshSuggestions = activeSuggestions()
                val duplicates = freshSuggestions.filter { it.category == SuggestionCategory.EXACT_DUPLICATE }
                // A kept copy is never offered, so it is never marked. Every other copy of a group is.
                selectionStore.setAll(duplicates.filter { it.fileId in kept }, selected = false)
                selectionStore.setAll(duplicates.filter { it.fileId !in kept }, selected = true)
                events.send(HubEvent.RuleApplied(rule, changed))
            } finally {
                busy.value = false
            }
        }
    }

    private suspend fun activeSuggestions(): List<Suggestion> =
        suggestions.observeSuggestions(category = null, includeDismissed = false).first()

    private fun categoryOf(
        name: String,
    ): SuggestionCategory? = SuggestionCategory.entries.firstOrNull { it.name == name }

    private fun typeOf(
        name: String,
    ): FileCategory? = FileCategory.entries.firstOrNull { it.name == name }
}
