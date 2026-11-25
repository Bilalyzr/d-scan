package com.cleansweep.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleansweep.app.domain.model.TrashEntry
import com.cleansweep.app.domain.model.TrashPolicy
import com.cleansweep.app.domain.scanner.StorageAccess
import com.cleansweep.app.domain.scanner.trash.PurgeResult
import com.cleansweep.app.domain.scanner.trash.RestoreResult
import com.cleansweep.app.domain.scanner.trash.SafeTrash
import com.cleansweep.app.domain.util.StoragePaths
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/** One entry of Safe Trash as its screen shows it. */
data class TrashRow(
    val entry: TrashEntry,
    val name: String,

    /** The folder the entry came from, as a person would name it. */
    val originalFolder: String?,

    /** Whole days left before the entry is deleted for good; 0 once it is due. */
    val daysLeft: Int,
) {
    val isDue: Boolean
        get() = daysLeft <= 0
}

data class TrashUiState(
    val rows: List<TrashRow> = emptyList(),
    val totalBytes: Long = 0L,
    val storageAccessGranted: Boolean = true,
    val busy: Boolean = false,

    /** The question whether the bin may be emptied is on screen. */
    val confirmingEmpty: Boolean = false,
) {
    val isEmpty: Boolean
        get() = rows.isEmpty()

    /** Days until the first entry goes, or null when the bin is empty. */
    val nextPurgeDays: Int?
        get() = rows.minOfOrNull { it.daysLeft }
}

/** What came of an action in the bin, for the message that reports it. */
sealed interface TrashEvent {
    data class Restored(
        val count: Int,
        val nothingLeft: Int,
        val failed: Int,
    ) : TrashEvent

    data class Emptied(
        val result: PurgeResult,
    ) : TrashEvent
}

/**
 * Screen 7, Safe Trash (PRD screen 7): what was taken out of the way, for how long it stays, and the two ways
 * it can leave: put back, or deleted for good.
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trash: SafeTrash,
    private val storageAccess: StorageAccess,
    private val clock: Clock,
) : ViewModel() {

    private val accessGranted = MutableStateFlow(storageAccess.canReadStorage())
    private val busy = MutableStateFlow(false)
    private val confirmingEmpty = MutableStateFlow(false)

    /** Set again whenever the screen comes to the foreground, so that the days left are counted from now. */
    private val now = MutableStateFlow(clock.millis())

    private val events = Channel<TrashEvent>(Channel.BUFFERED)
    val eventFlow: Flow<TrashEvent> = events.receiveAsFlow()

    val uiState: StateFlow<TrashUiState> = combine(
        trash.observeEntries(),
        trash.observeTotalBytes(),
        now,
        combine(accessGranted, busy, confirmingEmpty) { granted, working, asked -> Triple(granted, working, asked) },
    ) { entries, bytes, currentTime, controls ->
        TrashUiState(
            rows = entries.map { entry -> rowOf(entry, currentTime) },
            totalBytes = bytes,
            storageAccessGranted = controls.first,
            busy = controls.second,
            confirmingEmpty = controls.third,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrashUiState(storageAccessGranted = accessGranted.value),
    )

    private fun rowOf(
        entry: TrashEntry,
        currentTime: Long,
    ) = TrashRow(
        entry = entry,
        name = StoragePaths.nameOf(entry.originalPath),
        originalFolder = StoragePaths.parentOf(entry.originalPath),
        daysLeft = TrashPolicy.daysRemaining(entry.expiresAt, currentTime),
    )

    /** The screen is in the foreground again: access may have changed, and a day may have passed. */
    fun onResume() {
        accessGranted.value = storageAccess.canReadStorage()
        now.value = clock.millis()
    }

    /** Puts one entry back where it came from. */
    fun restore(
        entryId: Long,
    ) = restoreAll(listOf(entryId))

    /** Puts every entry back. */
    fun restoreEverything() {
        viewModelScope.launch { restoreAll(uiState.value.rows.map { it.entry.id }) }
    }

    private fun restoreAll(
        entryIds: Collection<Long>,
    ) {
        if (entryIds.isEmpty()) return
        viewModelScope.launch {
            busy.value = true
            try {
                events.send(report(trash.restore(entryIds)))
            } finally {
                busy.value = false
            }
        }
    }

    private fun report(
        result: RestoreResult,
    ) = TrashEvent.Restored(result.restoredPaths.size, result.nothingLeft.size, result.failures.size)

    /** Opens the question whether the bin may be emptied. Nothing is deleted by this. */
    fun requestEmpty() {
        if (!uiState.value.isEmpty && !busy.value) confirmingEmpty.value = true
    }

    fun cancelEmpty() {
        confirmingEmpty.value = false
    }

    /**
     * Deletes every entry for good. The one call that cannot be undone: it is made only after the question on
     * screen was answered with yes.
     */
    fun emptyTrashPermanently() {
        if (busy.value) return
        viewModelScope.launch {
            confirmingEmpty.value = false
            busy.value = true
            try {
                events.send(TrashEvent.Emptied(trash.emptyTrashPermanently()))
            } finally {
                busy.value = false
            }
        }
    }
}
