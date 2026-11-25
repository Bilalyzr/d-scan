package com.cleansweep.app.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.repository.SuggestionRepository
import com.cleansweep.app.domain.scanner.ScanControl
import com.cleansweep.app.domain.scanner.ScanState
import com.cleansweep.app.domain.scanner.StorageAccess
import com.cleansweep.app.domain.scanner.clean.CleanSelection
import com.cleansweep.app.domain.scanner.clean.CleanSelectionStore
import com.cleansweep.app.domain.scanner.duplicates.AutoSelectRule
import com.cleansweep.app.domain.scanner.duplicates.DuplicateGroupEditor
import com.cleansweep.app.domain.scanner.duplicates.KeepResult
import com.cleansweep.app.domain.scanner.duplicates.OriginalLockPolicy
import com.cleansweep.app.domain.scanner.duplicates.ReleaseResult
import com.cleansweep.app.domain.scanner.trash.SafeTrash
import com.cleansweep.app.ui.clean.CleanOutcome
import com.cleansweep.app.ui.clean.Cleaner
import com.cleansweep.app.ui.clean.CleanSummary
import com.cleansweep.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One copy of the group as its screen shows it. */
data class CopyView(
    val file: IndexedFile,

    /** The copy the group keeps by its rule now. It is shown first and is never offered. */
    val isOriginal: Boolean,

    /** Kept by the user on purpose, which stays kept whatever the rule would pick. */
    val isKeptByUser: Boolean,

    /** The suggestion through which this copy may be moved to the trash. Null for a copy that is kept. */
    val suggestion: Suggestion?,

    val isSelected: Boolean,
) {
    val canMark: Boolean
        get() = suggestion != null && !suggestion.isDismissed
}

/** What happened on the group's screen, for the message that reports it. */
sealed interface GroupEvent {
    data class MovedToTrash(
        val outcome: CleanOutcome,
    ) : GroupEvent

    data class Restored(
        val count: Int,
    ) : GroupEvent

    data object NothingToRestore : GroupEvent

    data class Ignored(
        val fileId: Long,
    ) : GroupEvent

    data class Unignored(
        val fileId: Long,
    ) : GroupEvent

    data object CopyKept : GroupEvent

    data object CopyReleased : GroupEvent

    data class RuleApplied(
        val rule: AutoSelectRule,
    ) : GroupEvent

    /** The copy is the one the group keeps by its rule, so there is nothing to undo. */
    data object KeptByRule : GroupEvent

    data object CouldNotChange : GroupEvent
}

data class GroupUiState(
    val loading: Boolean = true,
    val copies: List<CopyView> = emptyList(),
    val storageAccessGranted: Boolean = true,
    val isScanning: Boolean = false,
    val busy: Boolean = false,
    val confirming: Boolean = false,
) {
    /** The files are no longer two identical copies, or are gone. The index is behind what is on storage. */
    val isGone: Boolean
        get() = !loading && copies.size < 2

    val name: String
        get() = copies.firstOrNull()?.file?.name.orEmpty()

    /** Copies that may be moved to the trash: every copy that is not kept. */
    val offered: List<CopyView>
        get() = copies.filter { it.suggestion != null && !it.suggestion.isDismissed }

    val reclaimableBytes: Long
        get() = offered.sumOf { it.file.sizeBytes }

    val selected: List<CopyView>
        get() = copies.filter { it.isSelected }

    val summary: CleanSummary
        get() = CleanSummary.of(selected.mapNotNull { it.suggestion })

    /** False while a scan runs, or without access to storage: what is offered is only certain once the scan is done. */
    val canMark: Boolean
        get() = storageAccessGranted && !isScanning && !busy

    val canClean: Boolean
        get() = canMark && !confirming && selected.isNotEmpty()
}

/**
 * Screen 5, one group of identical files (PRD screen 5): every copy side by side, with which one is kept, a
 * way to keep another one instead, and a rule to tick the rest.
 */
@HiltViewModel
class GroupViewModel @Inject constructor(
    private val fileIndex: FileIndexRepository,
    private val suggestions: SuggestionRepository,
    scans: ScanControl,
    private val storageAccess: StorageAccess,
    private val selectionStore: CleanSelectionStore,
    private val editor: DuplicateGroupEditor,
    private val trash: SafeTrash,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val fullHash: String = savedState.get<String>(Routes.ARG_HASH).orEmpty()
    private val accessGranted = MutableStateFlow(storageAccess.canReadStorage())
    private val busy = MutableStateFlow(false)
    private val confirming = MutableStateFlow(false)
    private var lastOutcome: CleanOutcome? = null

    private val events = Channel<GroupEvent>(Channel.BUFFERED)
    val eventFlow: Flow<GroupEvent> = events.receiveAsFlow()

    private val cleaner = Cleaner(trash, selectionStore)

    /** The copies of the group as the index has them now: not read yet, there, or no longer a group. */
    private sealed interface GroupSnapshot {
        data object NotReadYet : GroupSnapshot

        data class Found(
            val copies: List<IndexedFile>,
        ) : GroupSnapshot

        data object Gone : GroupSnapshot
    }

    private val group: Flow<GroupSnapshot> = fileIndex.observeCopiesWithHash(fullHash)
        .map { copies: List<IndexedFile> ->
            if (copies.size >= 2) GroupSnapshot.Found(copies) else GroupSnapshot.Gone
        }
        .onStart { emit(GroupSnapshot.NotReadYet) }

    val uiState: StateFlow<GroupUiState> = combine(
        group,
        suggestions.observeSuggestions(category = null, includeDismissed = true),
        selectionStore.selection,
        scans.state,
        combine(accessGranted, busy, confirming) { granted, working, asked -> Triple(granted, working, asked) },
    ) { group, allSuggestions, selection, scan, controls ->
        GroupUiState(
            loading = group is GroupSnapshot.NotReadYet,
            copies = (group as? GroupSnapshot.Found)?.let { viewsOf(it.copies, allSuggestions, selection) }.orEmpty(),
            storageAccessGranted = controls.first,
            isScanning = scan is ScanState.Running,
            busy = controls.second,
            confirming = controls.third,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GroupUiState(storageAccessGranted = accessGranted.value),
    )

    /** The kept copy first, then the others from oldest to newest. */
    private fun viewsOf(
        group: List<IndexedFile>,
        allSuggestions: List<Suggestion>,
        selection: CleanSelection,
    ): List<CopyView> {
        val original = OriginalLockPolicy.selectOriginal(group)
        val ordered = listOf(original) +
            group.filter { it.id != original.id }.sortedWith(compareBy({ it.lastModified }, { it.path }))
        return ordered.map { copy ->
            val isOriginal = copy.id == original.id
            val kept = isOriginal || copy.isOriginalLocked
            // A kept copy is never offered, whatever other suggestion it may have.
            val suggestion = if (kept) null else allSuggestions.firstOrNull { it.fileId == copy.id }
            CopyView(
                file = copy,
                isOriginal = isOriginal,
                isKeptByUser = copy.isOriginalLocked,
                suggestion = suggestion,
                isSelected = suggestion != null && selection.isSelected(suggestion) && !suggestion.isDismissed,
            )
        }
    }

    /** The system settings may have changed whether storage may be read. */
    fun onResume() {
        accessGranted.value = storageAccess.canReadStorage()
    }

    /** Ticks or unticks a copy. A kept copy cannot be ticked; the message says why. */
    fun toggle(
        copy: CopyView,
    ) {
        val suggestion = copy.suggestion
        if (suggestion == null) {
            viewModelScope.launch { events.send(GroupEvent.KeptByRule) }
            return
        }
        if (!uiState.value.canMark) return
        selectionStore.set(suggestion, !copy.isSelected)
    }

    /** Keeps [copy] on purpose from now on. The copy that was kept until then is offered in its place. */
    fun keep(
        copy: CopyView,
    ) {
        change {
            when (editor.keepCopy(copy.file.id)) {
                KeepResult.Kept -> GroupEvent.CopyKept
                KeepResult.AlreadyKept -> GroupEvent.CopyKept
                else -> GroupEvent.CouldNotChange
            }
        }
    }

    /** Stops keeping [copy] on purpose. The group keeps a copy by its usual rule from then on. */
    fun release(
        copy: CopyView,
    ) {
        if (!copy.isKeptByUser) {
            viewModelScope.launch { events.send(GroupEvent.KeptByRule) }
            return
        }
        change {
            when (editor.releaseCopy(copy.file.id)) {
                ReleaseResult.Released -> GroupEvent.CopyReleased
                else -> GroupEvent.CouldNotChange
            }
        }
    }

    /** Ticks the copies by a rule: all but the copy the rule keeps, or none at all (PRD 4.5). */
    fun applyRule(
        rule: AutoSelectRule,
    ) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                val offeredNow = uiState.value.offered.mapNotNull { it.suggestion }
                if (rule == AutoSelectRule.UNSELECT_ALL) {
                    selectionStore.setAll(offeredNow, selected = false)
                    events.send(GroupEvent.RuleApplied(rule))
                    return@launch
                }
                val group = fileIndex.getCopiesWithHash(fullHash)
                val keeper = rule.keeperOf(group) ?: return@launch
                if (keeper.id != OriginalLockPolicy.selectOriginal(group).id) {
                    editor.keepCopy(keeper.id)
                }
                // The suggestions of the group were made anew; read them again before ticking.
                val fresh = fileIndex.getCopiesWithHash(fullHash)
                val kept = fresh.filter { it.isOriginalLocked }.map { it.id }.toSet() +
                    OriginalLockPolicy.selectOriginal(fresh).id
                val offered = suggestions.observeSuggestions(category = null, includeDismissed = false)
                    .first()
                    .filter { it.category == SuggestionCategory.EXACT_DUPLICATE && it.file.fullHash == fullHash }
                selectionStore.setAll(offered.filter { it.fileId in kept }, selected = false)
                selectionStore.setAll(offered.filter { it.fileId !in kept }, selected = true)
                events.send(GroupEvent.RuleApplied(rule))
            } finally {
                busy.value = false
            }
        }
    }

    private fun change(
        action: suspend () -> GroupEvent,
    ) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                events.send(action())
            } finally {
                busy.value = false
            }
        }
    }

    /** Hides the suggestion of a copy. The file is not touched, and it stays hidden across scans. */
    fun ignore(
        copy: CopyView,
    ) {
        val suggestion = copy.suggestion ?: return
        viewModelScope.launch {
            suggestions.setDismissed(listOf(suggestion.fileId), dismissed = true)
            selectionStore.forget(listOf(suggestion.fileId))
            events.send(GroupEvent.Ignored(suggestion.fileId))
        }
    }

    fun unignore(
        fileId: Long,
    ) {
        viewModelScope.launch {
            suggestions.setDismissed(listOf(fileId), dismissed = false)
            events.send(GroupEvent.Unignored(fileId))
        }
    }

    /** Opens the sheet that asks for confirmation. */
    fun requestClean() {
        if (uiState.value.canClean) confirming.value = true
    }

    fun cancelClean() {
        confirming.value = false
    }

    /** Moves the ticked copies of this group to Safe Trash, after the sheet was confirmed. */
    fun clean() {
        val marked = uiState.value.selected.mapNotNull { it.suggestion?.fileId }
        if (marked.isEmpty() || busy.value) return
        viewModelScope.launch {
            confirming.value = false
            busy.value = true
            try {
                val outcome = cleaner.clean(marked)
                lastOutcome = outcome
                events.send(GroupEvent.MovedToTrash(outcome))
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
                    if (restored.restoredPaths.isEmpty()) GroupEvent.NothingToRestore else GroupEvent.Restored(restored.restoredPaths.size),
                )
            } finally {
                busy.value = false
            }
        }
    }
}
