package com.cleansweep.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleansweep.app.di.IoDispatcher
import com.cleansweep.app.domain.model.ThemeMode
import com.cleansweep.app.domain.model.UserPreferences
import com.cleansweep.app.domain.model.WhitelistRule
import com.cleansweep.app.domain.repository.SettingsRepository
import com.cleansweep.app.domain.scanner.FolderLister
import com.cleansweep.app.domain.scanner.StorageRootsProvider
import com.cleansweep.app.domain.scanner.whitelist.ProtectionResult
import com.cleansweep.app.domain.scanner.whitelist.WhitelistManager
import com.cleansweep.app.domain.util.StoragePaths
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The folder picker of the exclusions: one folder at a time.
 *
 * @property path The folder whose subfolders are listed. Null at the top, where the storages themselves are listed.
 * @property folders What can be entered from here: the storages at the top, the subfolders below.
 * @property canExclude False at the top and on a storage itself: excluding a whole storage would hide everything.
 */
data class FolderPickerState(
    val path: String? = null,
    val folders: List<String> = emptyList(),
    val loading: Boolean = true,
    val canExclude: Boolean = false,
)

data class SettingsUiState(
    val loading: Boolean = true,
    val preferences: UserPreferences = UserPreferences(),
    val rules: List<WhitelistRule> = emptyList(),
    val picker: FolderPickerState? = null,
    val busy: Boolean = false,
)

/** What came of a decision in Settings, for the message that reports it. */
sealed interface SettingsEvent {
    data class Excluded(
        val path: String,
    ) : SettingsEvent

    /** The folder lies within one that is excluded already, so nothing changed. */
    data class AlreadyExcluded(
        val path: String,
    ) : SettingsEvent

    data object FolderGone : SettingsEvent

    data object WholeStorage : SettingsEvent

    data class RuleRemoved(
        val rule: WhitelistRule,
    ) : SettingsEvent
}

/**
 * Screen 8, Settings and the whitelist (PRD screen 8): what is left alone, what a scan looks at, and how the app
 * looks.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val whitelist: WhitelistManager,
    private val roots: StorageRootsProvider,
    private val folderLister: FolderLister,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val picker = MutableStateFlow<FolderPickerState?>(null)
    private val busy = MutableStateFlow(false)

    private val events = Channel<SettingsEvent>(Channel.BUFFERED)
    val eventFlow: Flow<SettingsEvent> = events.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.preferences,
        whitelist.rules,
        picker,
        busy,
    ) { preferences, rules, picking, working ->
        SettingsUiState(
            loading = false,
            preferences = preferences,
            rules = rules,
            picker = picking,
            busy = working,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    // ---- Scan filters and thresholds ----

    fun setMinFileSize(
        bytes: Long,
    ) {
        viewModelScope.launch { settings.setMinFileSizeBytes(bytes) }
    }

    fun setLargeFileThreshold(
        bytes: Long,
    ) {
        viewModelScope.launch { settings.setLargeFileThresholdBytes(bytes) }
    }

    fun setLargeFileStaleDays(
        days: Int,
    ) {
        viewModelScope.launch { settings.setLargeFileStaleDays(days) }
    }

    fun setTheme(
        mode: ThemeMode,
    ) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    /** The daily scan in the background follows this setting on its own (see `BackgroundIndexing`). */
    fun setBackgroundIndexing(
        enabled: Boolean,
    ) {
        viewModelScope.launch { settings.setBackgroundIndexing(enabled) }
    }

    // ---- Exclusions ----

    /** Ends an exclusion: from the next scan on, the folder or file is offered again. */
    fun removeRule(
        rule: WhitelistRule,
    ) {
        viewModelScope.launch {
            whitelist.unprotect(rule)
            events.send(SettingsEvent.RuleRemoved(rule))
        }
    }

    /** Opens the picker at the top, where the storages are. */
    fun openPicker() {
        show(null)
    }

    fun openFolder(
        path: String,
    ) = show(path)

    /** From a folder to the folder that holds it; from a storage to the top. */
    fun pickerUp() {
        val current = picker.value?.path ?: return
        show(StoragePaths.parentOf(current))
    }

    fun closePicker() {
        picker.value = null
    }

    /** Excludes the folder the picker is on. It takes effect at once, and the picker closes. */
    fun excludeCurrentFolder() {
        val state = picker.value ?: return
        val path = state.path ?: return
        if (!state.canExclude || state.loading || busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                val event = when (val result = withContext(ioDispatcher) { whitelist.protect(path) }) {
                    is ProtectionResult.Added -> SettingsEvent.Excluded(path)
                    is ProtectionResult.AlreadyProtected -> SettingsEvent.AlreadyExcluded(path)
                    ProtectionResult.NothingThere -> SettingsEvent.FolderGone
                    ProtectionResult.WouldExcludeEverything -> SettingsEvent.WholeStorage
                }
                picker.value = null
                events.send(event)
            } finally {
                busy.value = false
            }
        }
    }

    /** Shows a folder, or the top when [path] is null. Until its folders are read, the picker says so. */
    private fun show(
        path: String?,
    ) {
        picker.value = FolderPickerState(path = path)
        viewModelScope.launch {
            val shown = withContext(ioDispatcher) {
                if (path == null) {
                    FolderPickerState(path = null, folders = roots.roots(), loading = false, canExclude = false)
                } else {
                    val storageRoots = roots.roots()
                    FolderPickerState(
                        path = path,
                        folders = folderLister.listFolders(path),
                        loading = false,
                        canExclude = path !in storageRoots && storageRoots.isNotEmpty(),
                    )
                }
            }
            picker.value = shown
        }
    }
}
