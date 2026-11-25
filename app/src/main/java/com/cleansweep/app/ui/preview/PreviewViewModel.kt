package com.cleansweep.app.ui.preview

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleansweep.app.di.IoDispatcher
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.repository.SuggestionRepository
import com.cleansweep.app.domain.scanner.ScanControl
import com.cleansweep.app.domain.scanner.ScanState
import com.cleansweep.app.domain.scanner.StorageAccess
import com.cleansweep.app.domain.scanner.clean.CleanSelectionStore
import com.cleansweep.app.domain.scanner.duplicates.DuplicateGroupEditor
import com.cleansweep.app.domain.scanner.duplicates.KeepResult
import com.cleansweep.app.domain.scanner.duplicates.OriginalLockPolicy
import com.cleansweep.app.domain.scanner.duplicates.ReleaseResult
import com.cleansweep.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Reads the beginning of a text file, to show what it holds. */
fun interface TextPreviewReader {
    /** The first bytes of the file at [path] as text. Throws when it cannot be read. */
    fun readStartOf(
        path: String,
    ): String
}

/** The beginning of a text file as the platform decodes it. Bytes that are not text read as the replacement sign. */
internal class FileTextPreviewReader : TextPreviewReader {

    override fun readStartOf(
        path: String,
    ): String {
        val file = File(path)
        val wanted = minOf(file.length(), PreviewKinds.TEXT_READ_LIMIT_BYTES).toInt()
        val bytes = ByteArray(wanted)
        val read = file.inputStream().use { input ->
            var total = 0
            while (total < wanted) {
                val count = input.read(bytes, total, wanted - total)
                if (count < 0) break
                total += count
            }
            total
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }
}

/** What came of a decision on the preview, for the message that reports it. */
sealed interface PreviewEvent {
    data object Kept : PreviewEvent

    data object Released : PreviewEvent

    /** The copy is kept by its group's rule, so there is nothing to undo. */
    data object KeptByRule : PreviewEvent

    data class Ignored(
        val fileId: Long,
    ) : PreviewEvent

    data class Unignored(
        val fileId: Long,
    ) : PreviewEvent

    data object CouldNotChange : PreviewEvent
}

/** How showing the content of the file is going. */
enum class ContentState {
    LOADING,
    READY,
    FAILED,
}

data class PreviewUiState(
    val loading: Boolean = true,
    val file: IndexedFile? = null,
    val kind: PreviewKind = PreviewKind.NONE,

    /** The file is one of a group of identical files. */
    val isDuplicate: Boolean = false,

    /** The copy the group keeps by its rule. It is never offered for deletion. */
    val isOriginal: Boolean = false,

    /** Kept by the user on purpose, which can be undone. */
    val isKeptByUser: Boolean = false,

    /** The file has a suggestion the user has not ignored, and is not a kept copy: so it can be marked. */
    val isOffered: Boolean = false,

    /** The suggestion for this file was ignored. */
    val isIgnored: Boolean = false,
    val isSelected: Boolean = false,
    val storageAccessGranted: Boolean = true,
    val isScanning: Boolean = false,
    val busy: Boolean = false,
    val content: ContentState = ContentState.LOADING,
) {
    val isKept: Boolean
        get() = isOriginal || isKeptByUser

    /** "Keep this copy" is on when the file is kept in either way. */
    val isKeptEitherWay: Boolean
        get() = isKept || isIgnored

    val canMark: Boolean
        get() = isOffered && storageAccessGranted && !isScanning && !busy

    val isGone: Boolean
        get() = !loading && file == null
}

/** The file's place in its group, as far as the preview needs to know it. */
private data class Place(
    val isDuplicate: Boolean = false,
    val isOriginal: Boolean = false,
)

/** What the preview has been told meanwhile, beyond what the index and the suggestions say. */
private data class Controls(
    val accessGranted: Boolean,
    val busy: Boolean,
    val failed: Boolean,
    val shownText: String?,
    val pageCount: Int,
)

/** The file's row as the index has it: not read yet, there, or gone. */
private sealed interface FileSnapshot {
    data object NotReadYet : FileSnapshot

    data class Found(
        val file: IndexedFile,
    ) : FileSnapshot

    data object Gone : FileSnapshot
}

/**
 * Screen 6, the preview of one file (PRD screen 6): what is in it, as far as the app can show it, and the
 * choice between keeping the copy and marking it for deletion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val fileIndex: FileIndexRepository,
    private val suggestions: SuggestionRepository,
    scans: ScanControl,
    private val storageAccess: StorageAccess,
    private val selectionStore: CleanSelectionStore,
    private val editor: DuplicateGroupEditor,
    private val textReader: TextPreviewReader,
    private val pdfPages: PdfPages,
    private val screenWidth: ScreenWidth,
    savedState: SavedStateHandle,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val fileId: Long = savedState.get<String>(Routes.ARG_FILE_ID)?.toLongOrNull() ?: 0L

    /** The beginning of a text file, when the file is shown as text. */
    private val text = MutableStateFlow<String?>(null)

    /** The first pages of a PDF, when the file is a PDF. Pictures have no place in a data class: they are large. */
    private val pages = MutableStateFlow<List<Bitmap>>(emptyList())
    private val contentFailed = MutableStateFlow(false)
    private val accessGranted = MutableStateFlow(storageAccess.canReadStorage())
    private val busy = MutableStateFlow(false)

    /** The suggestion the file has now, through which it can be marked. */
    @Volatile
    private var currentSuggestion: Suggestion? = null

    private val events = Channel<PreviewEvent>(Channel.BUFFERED)
    val eventFlow: Flow<PreviewEvent> = events.receiveAsFlow()

    private val file: Flow<FileSnapshot> = fileIndex.observeById(fileId)
        .map { indexed: IndexedFile? ->
            if (indexed == null) FileSnapshot.Gone else FileSnapshot.Found(indexed)
        }
        .onStart { emit(FileSnapshot.NotReadYet) }

    /** Whether the file is one of a group of identical files, and whether it is the copy that group keeps. */
    private val place: Flow<Place> = file.flatMapLatest { snapshot ->
        val indexed = (snapshot as? FileSnapshot.Found)?.file
        val hash = indexed?.fullHash
        if (indexed == null || hash == null) {
            flowOf(Place())
        } else {
            fileIndex.observeCopiesWithHash(hash).map { copies ->
                if (copies.size < 2) {
                    Place()
                } else {
                    Place(
                        isDuplicate = true,
                        isOriginal = indexed.isOriginalLocked ||
                            OriginalLockPolicy.selectOriginal(copies).id == indexed.id,
                    )
                }
            }
        }
    }

    val textPreview: StateFlow<String?> = text.asStateFlow()

    val pdfPagesShown: StateFlow<List<Bitmap>> = pages.asStateFlow()

    val uiState: StateFlow<PreviewUiState> = combine(
        combine(file, place) { snapshot, where -> snapshot to where },
        suggestions.observeSuggestions(category = null, includeDismissed = true),
        selectionStore.selection,
        scans.state,
        combine(accessGranted, busy, contentFailed, text, pages) { granted, working, failed, shownText, shownPages ->
            Controls(granted, working, failed, shownText, shownPages.size)
        },
    ) { (snapshot, where), allSuggestions, selection, scan, controls ->
        val indexed = (snapshot as? FileSnapshot.Found)?.file
        // A kept copy is never offered, whatever other suggestion it may have.
        val suggestion = if (indexed == null || where.isOriginal) null else allSuggestions.firstOrNull { it.fileId == fileId }
        currentSuggestion = suggestion?.takeIf { !it.isDismissed }
        val kind = indexed?.let(PreviewKinds::of) ?: PreviewKind.NONE
        PreviewUiState(
            loading = snapshot is FileSnapshot.NotReadYet,
            file = indexed,
            kind = kind,
            isDuplicate = where.isDuplicate,
            isOriginal = where.isOriginal,
            isKeptByUser = indexed?.isOriginalLocked == true,
            isOffered = currentSuggestion != null,
            isIgnored = suggestion?.isDismissed == true,
            isSelected = currentSuggestion?.let { selection.isSelected(it) } == true,
            storageAccessGranted = controls.accessGranted,
            isScanning = scan is ScanState.Running,
            busy = controls.busy,
            content = when {
                controls.failed -> ContentState.FAILED
                // Text and PDFs are read in the background; the other kinds are shown by the screen itself.
                kind == PreviewKind.TEXT && controls.shownText == null -> ContentState.LOADING
                kind == PreviewKind.PDF && controls.pageCount == 0 -> ContentState.LOADING
                else -> ContentState.READY
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PreviewUiState(storageAccessGranted = accessGranted.value),
    )

    /** The path whose content was last asked for, so that the same file is not read again at every index change. */
    private var loadedPath: String? = null

    init {
        // The content is read as soon as the file is known, whether or not a screen is looking yet.
        viewModelScope.launch {
            file.collect { snapshot ->
                val indexed = (snapshot as? FileSnapshot.Found)?.file ?: return@collect
                val path = indexed.path
                if (path == loadedPath) return@collect
                loadedPath = path
                contentFailed.value = false
                text.value = null
                pages.value = emptyList()
                when (PreviewKinds.of(indexed)) {
                    PreviewKind.TEXT -> load { text.value = textReader.readStartOf(path) }
                    PreviewKind.PDF -> load { pages.value = pdfPages.firstPagesOf(path, screenWidth.pixels()) }
                    else -> Unit
                }
            }
        }
    }

    /** Runs [block] off the main thread. A failure is shown as one, and never takes the app down. */
    private fun load(
        block: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                contentFailed.value = true
            }
        }
    }

    /** The system settings may have changed whether storage may be read. */
    fun onResume() {
        accessGranted.value = storageAccess.canReadStorage()
    }

    /** Ticks or unticks the file for deletion, if it is offered. */
    fun mark(
        selected: Boolean,
    ) {
        val suggestion = currentSuggestion ?: return
        if (!uiState.value.canMark) return
        selectionStore.set(suggestion, selected)
    }

    /**
     * Keeps this copy: for one of a group of identical files by making it the copy the group keeps from now on,
     * for any other file by taking its suggestion off the list.
     */
    fun keep() {
        val state = uiState.value
        if (state.busy) return
        if (state.isDuplicate) {
            change {
                when (editor.keepCopy(fileId)) {
                    KeepResult.Kept, KeepResult.AlreadyKept -> PreviewEvent.Kept
                    else -> PreviewEvent.CouldNotChange
                }
            }
        } else {
            viewModelScope.launch {
                suggestions.setDismissed(listOf(fileId), dismissed = true)
                events.send(PreviewEvent.Ignored(fileId))
            }
        }
    }

    /** Stops keeping this copy: by ending the ignoring, or by giving up the user's choice for a copy of a group. */
    fun release() {
        val state = uiState.value
        if (state.busy) return
        when {
            state.isIgnored -> viewModelScope.launch {
                suggestions.setDismissed(listOf(fileId), dismissed = false)
                events.send(PreviewEvent.Unignored(fileId))
            }

            state.isKeptByUser -> change {
                when (editor.releaseCopy(fileId)) {
                    ReleaseResult.Released -> PreviewEvent.Released
                    else -> PreviewEvent.CouldNotChange
                }
            }

            else -> viewModelScope.launch { events.send(PreviewEvent.KeptByRule) }
        }
    }

    private fun change(
        action: suspend () -> PreviewEvent,
    ) {
        viewModelScope.launch {
            busy.value = true
            try {
                events.send(action())
            } finally {
                busy.value = false
            }
        }
    }
}
