package com.cleansweep.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleansweep.app.R
import com.cleansweep.app.domain.model.ThemeMode
import com.cleansweep.app.domain.model.UserPreferences
import com.cleansweep.app.domain.model.WhitelistRule
import com.cleansweep.app.domain.model.WhitelistRuleType
import com.cleansweep.app.domain.util.StoragePaths
import com.cleansweep.app.ui.common.AppIcons
import com.cleansweep.app.ui.common.SectionTitle
import com.cleansweep.app.ui.common.forDisplay
import com.cleansweep.app.ui.common.formatBytes
import com.cleansweep.app.ui.common.formatCount
import kotlin.math.roundToInt

/**
 * Screen 8, Settings and the whitelist (PRD screen 8): what is left alone, what a scan looks at, whether the
 * index is brought up to date in the background, and how the app looks.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages = remember { SnackbarHostState() }

    Messages(viewModel, messages)

    SettingsContent(
        state = state,
        messages = messages,
        onRemoveRule = viewModel::removeRule,
        onAddFolder = viewModel::openPicker,
        onMinFileSize = viewModel::setMinFileSize,
        onLargeFileThreshold = viewModel::setLargeFileThreshold,
        onLargeFileStaleDays = viewModel::setLargeFileStaleDays,
        onBackgroundIndexing = viewModel::setBackgroundIndexing,
        onTheme = viewModel::setTheme,
    )

    state.picker?.let { picker ->
        FolderPicker(
            picker = picker,
            enabled = !state.busy,
            onOpen = viewModel::openFolder,
            onUp = viewModel::pickerUp,
            onExclude = viewModel::excludeCurrentFolder,
            onClose = viewModel::closePicker,
        )
    }
}

/** Shows what came of a decision about the exclusions. */
@Composable
private fun Messages(
    viewModel: SettingsViewModel,
    messages: SnackbarHostState,
) {
    // Through LocalResources, so that a message follows a change of language.
    val resources = LocalResources.current
    LaunchedEffect(viewModel, messages, resources) {
        viewModel.eventFlow.collect { event ->
            val text = when (event) {
                is SettingsEvent.Excluded -> resources.getString(R.string.settings_msg_excluded)
                is SettingsEvent.AlreadyExcluded -> resources.getString(R.string.settings_msg_already)
                SettingsEvent.FolderGone -> resources.getString(R.string.settings_msg_gone)
                SettingsEvent.WholeStorage -> resources.getString(R.string.settings_msg_whole)
                is SettingsEvent.RuleRemoved -> resources.getString(R.string.settings_msg_removed)
            }
            messages.showSnackbar(message = text, duration = SnackbarDuration.Short)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    messages: SnackbarHostState,
    onRemoveRule: (WhitelistRule) -> Unit,
    onAddFolder: () -> Unit,
    onMinFileSize: (Long) -> Unit,
    onLargeFileThreshold: (Long) -> Unit,
    onLargeFileStaleDays: (Int) -> Unit,
    onBackgroundIndexing: (Boolean) -> Unit,
    onTheme: (ThemeMode) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(messages) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Exclusions(state, onRemoveRule, onAddFolder)
            Section(stringResource(R.string.settings_scan_title)) {
                ScanFilters(state.preferences, onMinFileSize, onLargeFileThreshold, onLargeFileStaleDays)
            }
            Section(stringResource(R.string.settings_background_title)) {
                ListItem(
                    supportingContent = { Text(stringResource(R.string.settings_background_text)) },
                    trailingContent = {
                        Switch(
                            checked = state.preferences.backgroundIndexing,
                            onCheckedChange = onBackgroundIndexing,
                        )
                    },
                ) {
                    Text(stringResource(R.string.settings_background_switch))
                }
            }
            Section(stringResource(R.string.settings_appearance_title)) {
                ThemeChoice(state.preferences.themeMode, onTheme)
            }
        }
    }
}

/** A titled part of the screen. */
@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    SectionTitle(title, Modifier.padding(top = 8.dp, bottom = 4.dp))
    content()
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun Exclusions(
    state: SettingsUiState,
    onRemoveRule: (WhitelistRule) -> Unit,
    onAddFolder: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Section(stringResource(R.string.settings_exclusions_title)) {
        Text(
            text = stringResource(R.string.settings_exclusions_text),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        if (state.rules.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_exclusions_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        for (rule in state.rules) {
            ExcludedRow(rule = rule, onRemove = { onRemoveRule(rule) })
        }
        Button(onClick = onAddFolder, modifier = Modifier.padding(top = 4.dp)) {
            Icon(AppIcons.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_exclusions_add))
        }
        Text(
            text = stringResource(R.string.settings_exclusions_file_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ExcludedRow(
    rule: WhitelistRule,
    onRemove: () -> Unit,
) {
    val isFolder = rule.type == WhitelistRuleType.FOLDER
    val stop = stringResource(R.string.settings_stop_excluding)
    ListItem(
        leadingContent = {
            Icon(if (isFolder) AppIcons.Folder else AppIcons.File, contentDescription = null)
        },
        supportingContent = {
            Text(
                text = if (isFolder) {
                    stringResource(R.string.whitelist_kind_folder)
                } else {
                    stringResource(R.string.whitelist_kind_file)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = stop)
            }
        },
    ) {
        Text(forDisplay(rule.path), style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanFilters(
    preferences: UserPreferences,
    onMinFileSize: (Long) -> Unit,
    onLargeFileThreshold: (Long) -> Unit,
    onLargeFileStaleDays: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Text(stringResource(R.string.settings_min_size), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (bytes in ScanSettingsSteps.MIN_FILE_SIZE_BYTES) {
            FilterChip(
                selected = preferences.minFileSizeBytes == bytes,
                onClick = { onMinFileSize(bytes) },
                label = {
                    Text(
                        if (bytes == 0L) {
                            stringResource(R.string.settings_min_size_off)
                        } else {
                            formatBytes(bytes)
                        },
                    )
                },
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    val large = ScanSettingsSteps.LARGE_FILE_THRESHOLDS
    val largeIndex = ScanSettingsSteps.nearestIndex(large, preferences.largeFileThresholdBytes) { it.bytes }
    Text(stringResource(R.string.settings_large_title), style = MaterialTheme.typography.titleSmall)
    Text(
        text = stringResource(R.string.settings_large_text, large[largeIndex].label),
        style = MaterialTheme.typography.bodyMedium,
    )
    StepSlider(
        index = largeIndex,
        count = large.size,
        label = stringResource(R.string.settings_large_title),
        description = large[largeIndex].label,
        onChange = { onLargeFileThreshold(large[it].bytes) },
    )

    val stale = ScanSettingsSteps.LARGE_FILE_STALE_DAYS
    val staleIndex = ScanSettingsSteps.nearestIndex(stale, preferences.largeFileStaleDays.toLong()) { it.toLong() }
    Text(
        text = stringResource(
            R.string.settings_stale_text,
            stale[staleIndex].toString(),
        ),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    StepSlider(
        index = staleIndex,
        count = stale.size,
        label = stringResource(R.string.settings_stale_label),
        description = formatCount(stale[staleIndex].toLong()),
        onChange = { onLargeFileStaleDays(stale[it]) },
    )

    Text(
        text = stringResource(R.string.settings_scan_note),
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )
}

/** A slider over a few steps. TalkBack hears what each step means, not a number. */
@Composable
private fun StepSlider(
    index: Int,
    count: Int,
    label: String,
    description: String,
    onChange: (Int) -> Unit,
) {
    // Made anew whenever the setting changes, so that the handle is always where the setting says.
    val handle = remember(index) {
        // Positional, as the constructor is declared: value, steps, range.
        SliderState(index.toFloat(), (count - 2).coerceAtLeast(0), 0f..(count - 1).toFloat())
    }
    Slider(
        state = handle,
        onValueChange = { onChange(it.roundToInt().coerceIn(0, count - 1)) },
        modifier = Modifier.semantics {
            contentDescription = label
            stateDescription = description
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeChoice(
    current: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((mode, label) in listOf(
            ThemeMode.SYSTEM to R.string.settings_theme_system,
            ThemeMode.LIGHT to R.string.settings_theme_light,
            ThemeMode.DARK to R.string.settings_theme_dark,
        )) {
            FilterChip(
                selected = current == mode,
                onClick = { onTheme(mode) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

/**
 * The folder picker: one folder at a time, from a storage down. The storages are at the top, where nothing can
 * be excluded; a storage itself cannot be excluded either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderPicker(
    picker: FolderPickerState,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    onUp: () -> Unit,
    onExclude: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Text(stringResource(R.string.picker_title), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                IconButton(onClick = onUp, enabled = picker.path != null) {
                    Icon(AppIcons.ArrowUpward, contentDescription = stringResource(R.string.picker_up))
                }
                Text(
                    text = picker.path?.let { forDisplay(it) } ?: stringResource(R.string.picker_top),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider()
            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 360.dp)) {
                when {
                    picker.loading -> Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.picker_loading), style = MaterialTheme.typography.bodyMedium)
                    }

                    picker.folders.isEmpty() -> Box(Modifier.fillMaxWidth().padding(24.dp)) {
                        Text(stringResource(R.string.picker_empty), style = MaterialTheme.typography.bodyMedium)
                    }

                    else -> LazyColumn(Modifier.fillMaxWidth()) {
                        items(picker.folders, key = { it }) { folder ->
                            ListItem(
                                leadingContent = { Icon(AppIcons.Folder, contentDescription = null) },
                                modifier = Modifier.clickable { onOpen(folder) },
                            ) {
                                Text(StoragePaths.nameOf(folder))
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            if (picker.path != null && !picker.canExclude && !picker.loading) {
                Text(
                    text = stringResource(R.string.picker_storage_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Button(
                onClick = onExclude,
                enabled = enabled && picker.canExclude && !picker.loading,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Icon(AppIcons.Block, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.picker_exclude))
            }
        }
    }
}
