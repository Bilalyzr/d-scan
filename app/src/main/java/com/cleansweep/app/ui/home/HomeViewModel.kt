package com.cleansweep.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleansweep.app.di.IoDispatcher
import com.cleansweep.app.domain.model.CategoryTotal
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.domain.model.TierTotal
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.repository.SuggestionRepository
import com.cleansweep.app.domain.repository.TrashRepository
import com.cleansweep.app.domain.scanner.ScanControl
import com.cleansweep.app.domain.scanner.ScanState
import com.cleansweep.app.domain.scanner.StorageAccess
import com.cleansweep.app.domain.scanner.StorageSpaceReader
import com.cleansweep.app.domain.scanner.VolumeSpace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject

/** Everything the dashboard shows (PRD screen 2). */
data class HomeUiState(
    /** False when the user has not granted access to storage, or has taken it back. */
    val storageAccessGranted: Boolean = true,

    /** False while the volumes are being measured, and when none of them can be measured. */
    val hasStorageSizes: Boolean = false,
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,

    /** Indexed bytes per kind of file, only the kinds that have files. */
    val categoryTotals: List<CategoryTotal> = emptyList(),
    val indexedFiles: Int = 0,

    /** Null when nothing of that tier is suggested. */
    val safeToClean: TierTotal? = null,
    val reviewRecommended: TierTotal? = null,

    val trashEntries: Int = 0,
    val trashBytes: Long = 0L,

    /** When a scan last wrote to the index. Null until the first scan. */
    val lastScanAt: Long? = null,

    val scan: ScanState = ScanState.Idle,

    /** For "last scanned" lines. Injected so that tests do not depend on the wall clock. */
    val now: Long = 0L,
) {
    val usedBytes: Long
        get() = (totalBytes - freeBytes).coerceAtLeast(0L)

    /** The share of the storage that is in use, between 0 and 1. */
    val usedFraction: Float
        get() = if (totalBytes > 0L) (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val isScanning: Boolean
        get() = scan is ScanState.Running

    val hasScanned: Boolean
        get() = lastScanAt != null
}

/** What is indexed, and what is offered. Grouped to stay within what `combine` takes at once. */
private data class Indexed(
    val categories: List<CategoryTotal>,
    val files: Int,
    val lastScanAt: Long?,
)

private data class Offered(
    val safe: TierTotal?,
    val review: TierTotal?,
    val trashEntries: Int,
    val trashBytes: Long,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val fileIndex: FileIndexRepository,
    suggestions: SuggestionRepository,
    trash: TrashRepository,
    private val scans: ScanControl,
    private val storageAccess: StorageAccess,
    private val storageSpace: StorageSpaceReader,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val accessGranted = MutableStateFlow(storageAccess.canReadStorage())
    private val volumes = MutableStateFlow<List<VolumeSpace>>(emptyList())

    val uiState: StateFlow<HomeUiState> = combine(
        combine(
            fileIndex.observeCategoryTotals(),
            fileIndex.observeFileCount(),
            fileIndex.observeLastScanTimestamp(),
        ) { categories, files, lastScanAt -> Indexed(categories, files, lastScanAt) },
        combine(
            suggestions.observeTierTotals(),
            trash.observeEntryCount(),
            trash.observeTotalBytes(),
        ) { tiers, entries, bytes ->
            Offered(
                safe = tiers.firstOrNull { it.tier == SuggestionTier.SAFE_TO_CLEAN },
                review = tiers.firstOrNull { it.tier == SuggestionTier.REVIEW_RECOMMENDED },
                trashEntries = entries,
                trashBytes = bytes,
            )
        },
        scans.state,
        accessGranted,
        volumes,
    ) { indexed, offered, scan, granted, storage ->
        HomeUiState(
            storageAccessGranted = granted,
            hasStorageSizes = storage.isNotEmpty(),
            totalBytes = storage.sumOf { it.totalBytes },
            freeBytes = storage.sumOf { it.freeBytes },
            categoryTotals = indexed.categories.sortedByDescending { it.totalBytes },
            indexedFiles = indexed.files,
            safeToClean = offered.safe,
            reviewRecommended = offered.review,
            trashEntries = offered.trashEntries,
            trashBytes = offered.trashBytes,
            lastScanAt = indexed.lastScanAt,
            scan = scan,
            now = clock.millis(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(
            storageAccessGranted = accessGranted.value,
            scan = scans.state.value,
            now = clock.millis(),
        ),
    )

    init {
        onResume()
    }

    /**
     * The dashboard is in the foreground again. Access may have been granted or taken back in the system
     * settings, and a volume may have been inserted or removed.
     */
    fun onResume() {
        accessGranted.value = storageAccess.canReadStorage()
        if (accessGranted.value) refreshStorage()
    }

    /** Measures the storage volumes again. */
    fun refreshStorage() {
        viewModelScope.launch {
            val measured = withContext(ioDispatcher) {
                try {
                    storageSpace.volumes()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // A volume that cannot be measured now is not an error worth stopping for.
                    emptyList()
                }
            }
            volumes.value = measured
        }
    }

    /** Starts a scan. The scan screen shows what happens next, including a missing permission. */
    fun startScan(): Boolean = scans.start()
}
