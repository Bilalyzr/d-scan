package com.cleansweep.app.ui.group

import android.content.ClipData
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleansweep.app.R
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.domain.scanner.duplicates.AutoSelectRule
import com.cleansweep.app.ui.clean.CleanBar
import com.cleansweep.app.ui.clean.CleanConfirmationSheet
import com.cleansweep.app.ui.clean.label
import com.cleansweep.app.ui.clean.movedMessage
import com.cleansweep.app.ui.clean.refusedMessage
import com.cleansweep.app.ui.clean.restoredMessage
import com.cleansweep.app.ui.common.AppIcons
import com.cleansweep.app.ui.common.EmptyState
import com.cleansweep.app.ui.common.NoticeCard
import com.cleansweep.app.ui.common.NoticeTone
import com.cleansweep.app.ui.common.OnResumeEffect
import com.cleansweep.app.ui.common.SectionTitle
import com.cleansweep.app.ui.common.Tag
import com.cleansweep.app.ui.common.formatBytes
import com.cleansweep.app.ui.common.formatCount
import com.cleansweep.app.ui.common.formatDate
import com.cleansweep.app.ui.common.icon
import com.cleansweep.app.ui.common.parentForDisplay
import kotlinx.coroutines.launch

/**
 * Screen 5, one group of identical files (PRD screen 5): every copy with its path, size and dates, the kept copy
 * first; the copies side by side to compare; a preview on tap; and a rule to tick the rest.
 */
@Composable
fun GroupScreen(
    onBack: () -> Unit,
    onOpenFile: (Long) -> Unit,
    onAllowAccess: () -> Unit,
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages = remember { SnackbarHostState() }

    OnResumeEffect { viewModel.onResume() }
    Messages(viewModel, messages)

    GroupContent(
        state = state,
        messages = messages,
        onBack = onBack,
        onOpenFile = onOpenFile,
        onAllowAccess = onAllowAccess,
        onToggle = viewModel::toggle,
        onKeep = viewModel::keep,
        onRelease = viewModel::release,
        onIgnore = viewModel::ignore,
        onUnignore = viewModel::unignore,
        onRule = viewModel::applyRule,
        onRequestClean = viewModel::requestClean,
    )

    if (state.confirming) {
        CleanConfirmationSheet(
            summary = state.summary,
            isWorking = state.busy,
            onConfirm = viewModel::clean,
            onCancel = viewModel::cancelClean,
        )
    }
}

/** Shows what came of each action, with a way to take it back where there is one. */
@Composable
private fun Messages(
    viewModel: GroupViewModel,
    messages: SnackbarHostState,
) {
    // Through LocalResources, so that a message follows a change of language.
    val resources = LocalResources.current
    LaunchedEffect(viewModel, messages, resources) {
        viewModel.eventFlow.collect { event ->
            val undo = resources.getString(R.string.hub_msg_undo)
            when (event) {
                is GroupEvent.MovedToTrash -> {
                    val outcome = event.outcome
                    val result = messages.showSnackbar(
                        message = resources.movedMessage(outcome),
                        actionLabel = undo.takeIf { outcome.moved.isNotEmpty() },
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoLastTrash()
                    if (outcome.refused.isNotEmpty()) {
                        messages.showSnackbar(
                            message = resources.refusedMessage(outcome.refused),
                            duration = SnackbarDuration.Long,
                        )
                    }
                }

                is GroupEvent.Restored -> messages.showSnackbar(
                    message = resources.restoredMessage(event.count),
                    duration = SnackbarDuration.Short,
                )

                GroupEvent.NothingToRestore -> messages.showSnackbar(
                    message = resources.getString(R.string.hub_msg_restore_nothing),
                    duration = SnackbarDuration.Short,
                )

                GroupEvent.CopyKept -> messages.showSnackbar(
                    message = resources.getString(R.string.group_msg_kept),
                    duration = SnackbarDuration.Short,
                )

                GroupEvent.CopyReleased -> messages.showSnackbar(
                    message = resources.getString(R.string.group_msg_released),
                    duration = SnackbarDuration.Short,
                )

                GroupEvent.KeptByRule -> messages.showSnackbar(
                    message = resources.getString(R.string.group_msg_kept_by_rule),
                    duration = SnackbarDuration.Short,
                )

                GroupEvent.CouldNotChange -> messages.showSnackbar(
                    message = resources.getString(R.string.group_msg_could_not_change),
                    duration = SnackbarDuration.Short,
                )

                is GroupEvent.RuleApplied -> messages.showSnackbar(
                    message = resources.getString(
                        when (event.rule) {
                            AutoSelectRule.KEEP_OLDEST -> R.string.group_msg_rule_oldest
                            AutoSelectRule.KEEP_NEWEST -> R.string.group_msg_rule_newest
                            AutoSelectRule.KEEP_SHORTEST_PATH -> R.string.group_msg_rule_shortest
                            AutoSelectRule.UNSELECT_ALL -> R.string.group_msg_rule_none
                        },
                    ),
                    duration = SnackbarDuration.Short,
                )

                is GroupEvent.Ignored -> {
                    val result = messages.showSnackbar(
                        message = resources.getString(R.string.group_msg_ignored),
                        actionLabel = undo,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.unignore(event.fileId)
                }

                is GroupEvent.Unignored -> messages.showSnackbar(
                    message = resources.getString(R.string.group_msg_unignored),
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupContent(
    state: GroupUiState,
    messages: SnackbarHostState,
    onBack: () -> Unit,
    onOpenFile: (Long) -> Unit,
    onAllowAccess: () -> Unit,
    onToggle: (CopyView) -> Unit,
    onKeep: (CopyView) -> Unit,
    onRelease: (CopyView) -> Unit,
    onIgnore: (CopyView) -> Unit,
    onUnignore: (Long) -> Unit,
    onRule: (AutoSelectRule) -> Unit,
    onRequestClean: () -> Unit,
) {
    val copiedMessage = stringResource(R.string.file_copied)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (state.isGone) stringResource(R.string.group_title) else state.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(messages) },
        bottomBar = {
            if (state.selected.isNotEmpty()) {
                CleanBar(
                    selectedCount = state.selected.size,
                    bytes = state.summary.bytes,
                    enabled = state.canClean,
                    onRequestClean = onRequestClean,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.isGone -> Box(Modifier.fillMaxSize().padding(innerPadding)) {
                EmptyState(
                    icon = AppIcons.Document,
                    title = stringResource(R.string.group_gone_title),
                    text = stringResource(R.string.group_gone_text),
                    actionLabel = stringResource(R.string.action_back),
                    onAction = onBack,
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.storageAccessGranted) {
                    NoticeCard(
                        title = stringResource(R.string.storage_access_off_title),
                        text = stringResource(R.string.storage_access_off_text),
                        icon = AppIcons.Shield,
                        tone = NoticeTone.ACTION,
                        actionLabel = stringResource(R.string.action_allow_access),
                        onAction = onAllowAccess,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (state.isScanning) {
                    NoticeCard(
                        title = stringResource(R.string.hub_scanning_title),
                        text = stringResource(R.string.hub_scanning_text),
                        icon = AppIcons.Clock,
                        tone = NoticeTone.INFO,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Summary(state, Modifier.padding(horizontal = 16.dp))
                RuleMenu(
                    enabled = state.canMark,
                    onRule = onRule,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                CompareStrip(state.copies)
                for (copy in state.copies) {
                    CopyCard(
                        copy = copy,
                        canAct = state.canMark,
                        messages = messages,
                        copiedMessage = copiedMessage,
                        onToggle = { onToggle(copy) },
                        onOpen = { onOpenFile(copy.file.id) },
                        onKeep = { onKeep(copy) },
                        onRelease = { onRelease(copy) },
                        onIgnore = { onIgnore(copy) },
                        onUnignore = { onUnignore(copy.file.id) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/** How many copies there are, how big each is, and how much of them can be freed. */
@Composable
private fun Summary(
    state: GroupUiState,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(
                    R.string.group_size_line,
                    pluralStringResource(
                        R.plurals.copy_count,
                        state.copies.size,
                        formatCount(state.copies.size.toLong()),
                    ),
                    formatBytes(state.copies.first().file.sizeBytes),
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = if (state.reclaimableBytes > 0L) {
                    stringResource(R.string.group_reclaim, formatBytes(state.reclaimableBytes))
                } else {
                    stringResource(R.string.group_every_copy_kept)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.reclaimableBytes > 0L) colors.primary else colors.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.group_identical_note),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** The rule to tick by, for this one group (PRD 4.5). */
@Composable
private fun RuleMenu(
    enabled: Boolean,
    onRule: (AutoSelectRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Icon(AppIcons.SelectAll, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.group_tick_by_rule))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val rules = listOf(
                AutoSelectRule.KEEP_OLDEST to R.string.auto_keep_oldest,
                AutoSelectRule.KEEP_NEWEST to R.string.auto_keep_newest,
                AutoSelectRule.KEEP_SHORTEST_PATH to R.string.auto_keep_shortest_path,
            )
            for ((rule, label) in rules) {
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        open = false
                        onRule(rule)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.auto_unselect_all)) },
                onClick = {
                    open = false
                    onRule(AutoSelectRule.UNSELECT_ALL)
                },
            )
        }
    }
}

/** The copies side by side, to compare where they are and how old they are (PRD 4.5). */
@Composable
private fun CompareStrip(
    copies: List<CopyView>,
) {
    Column {
        SectionTitle(stringResource(R.string.group_compare_title), Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            copies.forEachIndexed { position, copy ->
                CompareColumn(copy = copy, position = position, modifier = Modifier.widthIn(min = 160.dp))
            }
        }
    }
}

@Composable
private fun CompareColumn(
    copy: CopyView,
    position: Int,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val kept = copy.isOriginal
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (kept) colors.primaryContainer else colors.surfaceContainerHigh,
            contentColor = if (kept) colors.onPrimaryContainer else colors.onSurface,
        ),
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (position == 0) {
                    stringResource(R.string.group_column_original)
                } else {
                    stringResource(R.string.group_column_copy, position)
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = parentForDisplay(copy.file.path).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = formatBytes(copy.file.sizeBytes), style = MaterialTheme.typography.bodyMedium)
            Text(text = formatDate(copy.file.lastModified), style = MaterialTheme.typography.bodySmall)
            Text(
                text = copy.file.createdAt?.let { formatDate(it) }
                    ?: stringResource(R.string.file_created_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = if (kept) colors.onPrimaryContainer else colors.onSurfaceVariant,
            )
        }
    }
}

/** One copy: what it is, where it is, and what can be done with it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CopyCard(
    copy: CopyView,
    canAct: Boolean,
    messages: SnackbarHostState,
    copiedMessage: String,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onKeep: () -> Unit,
    onRelease: () -> Unit,
    onIgnore: () -> Unit,
    onUnignore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val onContainer = if (copy.isOriginal) colors.onPrimaryContainer else colors.onSurface
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val file = copy.file
    val suggestion = copy.suggestion
    val kept = copy.isOriginal || copy.isKeptByUser

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (copy.isOriginal) colors.primaryContainer else colors.surfaceContainerLow,
            contentColor = onContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (kept) {
                    // No tick box for a copy that cannot be ticked: a box would say it could be.
                    Icon(
                        imageVector = AppIcons.Bookmark,
                        contentDescription = stringResource(R.string.group_tag_original),
                        tint = colors.onPrimaryContainer,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp).size(24.dp),
                    )
                } else {
                    Checkbox(
                        checked = copy.isSelected,
                        enabled = copy.canMark && canAct,
                        onCheckedChange = { onToggle() },
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = file.category.icon,
                            contentDescription = null,
                            tint = if (copy.isOriginal) colors.onPrimaryContainer else colors.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Text(
                        text = formatBytes(file.sizeBytes) + " · " + parentForDisplay(file.path).orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (copy.isOriginal) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (copy.isOriginal) {
                            Tag(text = stringResource(R.string.group_tag_original), icon = AppIcons.Bookmark)
                        }
                        if (copy.isKeptByUser && !copy.isOriginal) {
                            Tag(text = stringResource(R.string.group_tag_kept_by_you), icon = AppIcons.Bookmark)
                        }
                        if (suggestion != null && !suggestion.isDismissed) {
                            if (suggestion.tier == SuggestionTier.REVIEW_RECOMMENDED) {
                                Tag(
                                    text = stringResource(R.string.group_tag_review),
                                    container = colors.tertiaryContainer,
                                    content = colors.onTertiaryContainer,
                                )
                            }
                            Tag(text = suggestion.primaryReason.label())
                        }
                        if (suggestion?.isDismissed == true) {
                            Tag(text = stringResource(R.string.group_tag_ignored))
                        }
                    }
                    if (suggestion == null) {
                        Text(
                            text = stringResource(
                                if (copy.isKeptByUser) R.string.group_kept_hint else R.string.group_original_hint,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Column(Modifier.padding(start = 48.dp, end = 8.dp)) {
                Detail(
                    label = stringResource(R.string.file_path),
                    value = file.path,
                    onCopy = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("file path", file.path)))
                            messages.showSnackbar(copiedMessage, duration = SnackbarDuration.Short)
                        }
                    },
                )
                Detail(label = stringResource(R.string.file_size), value = formatBytes(file.sizeBytes))
                Detail(label = stringResource(R.string.file_modified), value = formatDate(file.lastModified))
                Detail(
                    label = stringResource(R.string.file_created),
                    value = file.createdAt?.let { formatDate(it) }
                        ?: stringResource(R.string.file_created_unknown),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpen) {
                    Icon(AppIcons.Visibility, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.group_preview))
                }
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = kept,
                    enabled = canAct,
                    onClick = { if (kept) onRelease() else onKeep() },
                    label = { Text(stringResource(R.string.group_keep_button)) },
                    leadingIcon = { Icon(AppIcons.Bookmark, contentDescription = null) },
                )
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = suggestion != null) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.group_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (suggestion != null) {
                            if (suggestion.isDismissed) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.group_unignore)) },
                                    onClick = {
                                        menuOpen = false
                                        onUnignore()
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.group_ignore)) },
                                    onClick = {
                                        menuOpen = false
                                        onIgnore()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A label and its value; the value can be selected, and copied where there is a button for it. */
@Composable
private fun Detail(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        SelectionContainer(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
        if (onCopy != null) {
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = AppIcons.Copy,
                    contentDescription = stringResource(R.string.file_copy_path),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
