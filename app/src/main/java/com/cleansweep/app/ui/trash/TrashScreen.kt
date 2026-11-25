package com.cleansweep.app.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleansweep.app.R
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.ui.common.AppIcons
import com.cleansweep.app.ui.common.EmptyState
import com.cleansweep.app.ui.common.NoticeCard
import com.cleansweep.app.ui.common.NoticeTone
import com.cleansweep.app.ui.common.OnResumeEffect
import com.cleansweep.app.ui.common.forDisplay
import com.cleansweep.app.ui.common.formatBytes
import com.cleansweep.app.ui.common.formatCount
import com.cleansweep.app.ui.common.icon

/**
 * Screen 7, Safe Trash (PRD screen 7): what was taken out of the way and how long it stays, a way to put it
 * back, and the bin that deletes it for good after one more question.
 */
@Composable
fun TrashScreen(
    onAllowAccess: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages = remember { SnackbarHostState() }

    OnResumeEffect { viewModel.onResume() }
    Messages(viewModel, messages)

    TrashContent(
        state = state,
        messages = messages,
        onAllowAccess = onAllowAccess,
        onRestore = viewModel::restore,
        onRestoreEverything = viewModel::restoreEverything,
        onRequestEmpty = viewModel::requestEmpty,
        onEmpty = viewModel::emptyTrashPermanently,
        onCancelEmpty = viewModel::cancelEmpty,
    )
}

/** Shows what came of an action. */
@Composable
private fun Messages(
    viewModel: TrashViewModel,
    messages: SnackbarHostState,
) {
    // Through LocalResources, so that a message follows a change of language.
    val resources = LocalResources.current
    LaunchedEffect(viewModel, messages, resources) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is TrashEvent.Restored -> {
                    if (event.count > 0) {
                        messages.showSnackbar(
                            message = resources.getQuantityString(
                                R.plurals.trash_msg_put_back,
                                event.count,
                                formatCount(event.count.toLong()),
                            ),
                            duration = SnackbarDuration.Short,
                        )
                    }
                    if (event.nothingLeft > 0) {
                        messages.showSnackbar(
                            message = resources.getQuantityString(
                                R.plurals.trash_msg_nothing_left,
                                event.nothingLeft,
                                formatCount(event.nothingLeft.toLong()),
                            ),
                            duration = SnackbarDuration.Long,
                        )
                    }
                    if (event.failed > 0) {
                        messages.showSnackbar(
                            message = resources.getQuantityString(
                                R.plurals.trash_msg_not_put_back,
                                event.failed,
                                formatCount(event.failed.toLong()),
                            ),
                            duration = SnackbarDuration.Long,
                        )
                    }
                }

                is TrashEvent.Emptied -> {
                    val result = event.result
                    if (result.deletedEntries > 0) {
                        messages.showSnackbar(
                            message = resources.getQuantityString(
                                R.plurals.trash_msg_deleted,
                                result.deletedEntries,
                                formatBytes(result.bytesFreed),
                                formatCount(result.deletedEntries.toLong()),
                            ),
                            duration = SnackbarDuration.Long,
                        )
                    }
                    if (result.foldersNoLongerEmpty > 0) {
                        messages.showSnackbar(
                            message = resources.getQuantityString(
                                R.plurals.trash_msg_folders_kept,
                                result.foldersNoLongerEmpty,
                                formatCount(result.foldersNoLongerEmpty.toLong()),
                            ),
                            duration = SnackbarDuration.Long,
                        )
                    }
                    if (result.failedEntries > 0) {
                        messages.showSnackbar(
                            message = resources.getQuantityString(
                                R.plurals.trash_msg_delete_failed,
                                result.failedEntries,
                                formatCount(result.failedEntries.toLong()),
                            ),
                            duration = SnackbarDuration.Long,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrashContent(
    state: TrashUiState,
    messages: SnackbarHostState,
    onAllowAccess: () -> Unit,
    onRestore: (Long) -> Unit,
    onRestoreEverything: () -> Unit,
    onRequestEmpty: () -> Unit,
    onEmpty: () -> Unit,
    onCancelEmpty: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.trash_title)) }) },
        snackbarHost = { SnackbarHost(messages) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            if (!state.storageAccessGranted) {
                NoticeCard(
                    title = stringResource(R.string.storage_access_off_title),
                    text = stringResource(R.string.storage_access_off_text),
                    icon = AppIcons.Shield,
                    tone = NoticeTone.ACTION,
                    actionLabel = stringResource(R.string.action_allow_access),
                    onAction = onAllowAccess,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (state.isEmpty) {
                EmptyState(
                    icon = AppIcons.Restore,
                    title = stringResource(R.string.trash_empty_title),
                    text = stringResource(R.string.trash_empty_text),
                )
            } else {
                Summary(state, onRestoreEverything, onRequestEmpty, Modifier.padding(16.dp))
                for (row in state.rows) {
                    TrashRowView(
                        row = row,
                        enabled = state.storageAccessGranted && !state.busy,
                        onRestore = { onRestore(row.entry.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (state.confirmingEmpty) {
        AlertDialog(
            onDismissRequest = onCancelEmpty,
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.trash_confirm_title)) },
            text = {
                Text(
                    text = pluralStringResource(
                        R.plurals.trash_confirm_text,
                        state.rows.size,
                        formatCount(state.rows.size.toLong()),
                        formatBytes(state.totalBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onEmpty) { Text(stringResource(R.string.trash_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelEmpty) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** How much is in the bin, how long it stays, and the two ways out. */
@Composable
private fun Summary(
    state: TrashUiState,
    onRestoreEverything: () -> Unit,
    onRequestEmpty: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val canAct = state.storageAccessGranted && !state.busy
    Card(
        colors = CardDefaults.cardColors(
            containerColor = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = formatBytes(state.totalBytes), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = pluralStringResource(
                    R.plurals.item_count,
                    state.rows.size,
                    formatCount(state.rows.size.toLong()),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.trash_summary_keep),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            state.nextPurgeDays?.let { days ->
                Text(
                    text = if (days > 0) {
                        pluralStringResource(R.plurals.trash_next_purge, days, formatCount(days.toLong()))
                    } else {
                        stringResource(R.string.trash_due_now)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (days > 0) colors.onSurfaceVariant else colors.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onRestoreEverything, enabled = canAct, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.trash_restore_everything), maxLines = 2, textAlign = TextAlign.Center)
                }
                OutlinedButton(onClick = onRequestEmpty, enabled = canAct, modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.trash_empty_bin),
                        color = if (canAct) colors.error else colors.onSurface.copy(alpha = 0.38f),
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** One entry: what it is, where it came from, how long it stays, and the way back. */
@Composable
private fun TrashRowView(
    row: TrashRow,
    enabled: Boolean,
    onRestore: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val category = FileCategory.fromExtension(row.name.substringAfterLast('.', ""))
    val folder = row.originalFolder?.let { forDisplay(it) }
    val location = listOfNotNull(formatBytes(row.entry.sizeBytes), folder).joinToString(" · ")
    ListItem(
        leadingContent = {
            Icon(category.icon, contentDescription = null, tint = colors.onSurfaceVariant)
        },
        supportingContent = {
            Column {
                Text(location, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (row.isDue) {
                        stringResource(R.string.trash_due)
                    } else {
                        pluralStringResource(R.plurals.trash_days_left, row.daysLeft, formatCount(row.daysLeft.toLong()))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (row.isDue) colors.error else colors.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            TextButton(onClick = onRestore, enabled = enabled) { Text(stringResource(R.string.trash_put_back)) }
        },
    ) {
        Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
