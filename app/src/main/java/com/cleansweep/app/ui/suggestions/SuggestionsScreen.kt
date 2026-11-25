package com.cleansweep.app.ui.suggestions

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleansweep.app.R
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.ui.clean.movedMessage
import com.cleansweep.app.domain.model.TierTotal
import com.cleansweep.app.domain.scanner.duplicates.AutoSelectRule
import com.cleansweep.app.ui.clean.CleanBar
import com.cleansweep.app.ui.clean.CleanConfirmationSheet
import com.cleansweep.app.ui.clean.CleanOutcome
import com.cleansweep.app.ui.clean.countedByReason
import com.cleansweep.app.ui.clean.label
import com.cleansweep.app.ui.clean.refusedMessage
import com.cleansweep.app.ui.clean.restoredMessage
import com.cleansweep.app.ui.common.AppIcons
import com.cleansweep.app.ui.common.EmptyState
import com.cleansweep.app.ui.common.NoticeCard
import com.cleansweep.app.ui.common.NoticeTone
import com.cleansweep.app.ui.common.OnResumeEffect
import com.cleansweep.app.ui.common.formatBytes
import com.cleansweep.app.ui.common.formatCount
import com.cleansweep.app.ui.common.label

/**
 * Screen 4, the Smart Suggestions Hub: what may be removed, by how sure the app is (PRD screen 4).
 *
 * Safe items are ticked from the start, items that want a review are not. What is ticked adds up in the bar at
 * the bottom, from where it goes to Safe Trash after one more question.
 */
@Composable
fun SuggestionsScreen(
    onScan: () -> Unit,
    onAllowAccess: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenFile: (Long) -> Unit,
    viewModel: SuggestionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages = remember { SnackbarHostState() }

    OnResumeEffect { viewModel.onResume() }
    Messages(viewModel, messages)

    SuggestionsContent(
        state = state,
        messages = messages,
        // The scan service answers to the hub; the scan screen shows what it says.
        onScan = {
            viewModel.startScan()
            onScan()
        },
        onAllowAccess = onAllowAccess,
        onOpenGroup = onOpenGroup,
        onOpenFile = onOpenFile,
        onSelectTab = viewModel::selectTab,
        onToggle = { viewModel.setSelected(it, !state.selected.any { marked -> marked.fileId == it.fileId }) },
        onIgnore = viewModel::ignore,
        onUnignore = viewModel::unignore,
        onNeverSuggest = viewModel::neverSuggest,
        onShowIgnored = viewModel::setShowIgnored,
        onSelectAllSafe = viewModel::selectAllSafe,
        onUnselectAll = viewModel::unselectAll,
        onAutoSelect = viewModel::applyAutoSelect,
        onClearFilter = viewModel::clearFilter,
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

/**
 * Shows what came of each action, with a way to take it back where there is one.
 *
 * The wording is read from resources when an action is over, not in composition: a message is made inside the
 * coroutine that reports the action, where no composable may be called.
 */
@Composable
private fun Messages(
    viewModel: SuggestionsViewModel,
    messages: SnackbarHostState,
) {
    val resources = LocalContext.current.resources
    LaunchedEffect(viewModel, messages, resources) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is HubEvent.MovedToTrash -> {
                    val outcome = event.outcome
                    val result = messages.showSnackbar(
                        message = resources.movedMessage(outcome),
                        actionLabel = resources.getString(R.string.hub_msg_undo)
                            .takeIf { outcome.moved.isNotEmpty() },
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoLastTrash()
                    // What was refused is said separately, so that it is not cut off behind the Undo.
                    if (outcome.refused.isNotEmpty()) {
                        messages.showSnackbar(
                            message = resources.refusedMessage(outcome.refused),
                            duration = SnackbarDuration.Long,
                        )
                    }
                }

                is HubEvent.Restored -> messages.showSnackbar(
                    message = resources.restoredMessage(event.count),
                    duration = SnackbarDuration.Short,
                )

                HubEvent.NothingToRestore -> messages.showSnackbar(
                    message = resources.getString(R.string.hub_msg_restore_nothing),
                    duration = SnackbarDuration.Short,
                )

                is HubEvent.Ignored -> {
                    val result = messages.showSnackbar(
                        message = resources.getString(R.string.hub_msg_ignored),
                        actionLabel = resources.getString(R.string.hub_msg_undo),
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.unignore(event.fileId)
                }

                is HubEvent.Unignored -> messages.showSnackbar(
                    message = resources.getString(R.string.hub_msg_unignored),
                    duration = SnackbarDuration.Short,
                )

                is HubEvent.Excluded -> {
                    val result = messages.showSnackbar(
                        message = resources.getString(R.string.hub_msg_excluded),
                        actionLabel = resources.getString(R.string.hub_msg_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.allowAgain(event.rule)
                }

                is HubEvent.RuleApplied -> messages.showSnackbar(
                    message = resources.ruleMessage(event.rule, event.groupsChanged),
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
}

private fun Resources.ruleMessage(
    rule: AutoSelectRule,
    groupsChanged: Int,
): String = when (rule) {
    AutoSelectRule.UNSELECT_ALL -> getString(R.string.hub_rule_unticked)
    AutoSelectRule.KEEP_OLDEST -> ruleApplied(groupsChanged, getString(R.string.auto_keep_oldest_short))
    AutoSelectRule.KEEP_NEWEST -> ruleApplied(groupsChanged, getString(R.string.auto_keep_newest_short))
    AutoSelectRule.KEEP_SHORTEST_PATH -> ruleApplied(groupsChanged, getString(R.string.auto_keep_shortest_path_short))
}

private fun Resources.ruleApplied(
    groupsChanged: Int,
    keeps: String,
): String = if (groupsChanged == 0) {
    getString(R.string.hub_rule_nothing_changed)
} else {
    getQuantityString(
        R.plurals.hub_rule_applied,
        groupsChanged,
        formatCount(groupsChanged.toLong()),
        keeps,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SuggestionsContent(
    state: HubUiState,
    messages: SnackbarHostState,
    onScan: () -> Unit,
    onAllowAccess: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenFile: (Long) -> Unit,
    onSelectTab: (HubTab) -> Unit,
    onToggle: (Suggestion) -> Unit,
    onIgnore: (Suggestion) -> Unit,
    onUnignore: (Long) -> Unit,
    onNeverSuggest: (Suggestion) -> Unit,
    onShowIgnored: (Boolean) -> Unit,
    onSelectAllSafe: () -> Unit,
    onUnselectAll: () -> Unit,
    onAutoSelect: (AutoSelectRule) -> Unit,
    onClearFilter: () -> Unit,
    onRequestClean: () -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val selectedIds = remember(state.selected) { state.selected.map { it.fileId }.toSet() }
    val canMark = state.storageAccessGranted && !state.isScanning && !state.busy

    Scaffold(
        topBar = {
            Column {
                // A screen with a tab is a root of its own, so it has no back arrow.
                TopAppBar(
                    title = { Text(stringResource(R.string.hub_title)) },
                    actions = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (state.ignoredCount > 0) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (state.showIgnored) R.string.hub_hide_ignored
                                                else R.string.hub_show_ignored,
                                                formatCount(state.ignoredCount.toLong()),
                                            ),
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onShowIgnored(!state.showIgnored)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.hub_select_all_safe)) },
                                onClick = {
                                    menuOpen = false
                                    onSelectAllSafe()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.hub_unselect_all)) },
                                onClick = {
                                    menuOpen = false
                                    onUnselectAll()
                                },
                            )
                            HorizontalDivider()
                            Text(
                                text = stringResource(R.string.hub_auto_select_section),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            for ((rule, label) in listOf(
                                AutoSelectRule.KEEP_OLDEST to R.string.auto_keep_oldest,
                                AutoSelectRule.KEEP_NEWEST to R.string.auto_keep_newest,
                                AutoSelectRule.KEEP_SHORTEST_PATH to R.string.auto_keep_shortest_path,
                            )) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    onClick = {
                                        menuOpen = false
                                        onAutoSelect(rule)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.auto_unselect_all)) },
                                onClick = {
                                    menuOpen = false
                                    onAutoSelect(AutoSelectRule.UNSELECT_ALL)
                                },
                            )
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                    Tab(
                        selected = state.tab == HubTab.SAFE_TO_CLEAN,
                        onClick = { onSelectTab(HubTab.SAFE_TO_CLEAN) },
                        text = { Text(stringResource(R.string.hub_tab_safe)) },
                    )
                    Tab(
                        selected = state.tab == HubTab.REVIEW_RECOMMENDED,
                        onClick = { onSelectTab(HubTab.REVIEW_RECOMMENDED) },
                        text = { Text(stringResource(R.string.hub_tab_review)) },
                    )
                    Tab(
                        selected = state.tab == HubTab.ALL_DUPLICATES,
                        onClick = { onSelectTab(HubTab.ALL_DUPLICATES) },
                        text = { Text(stringResource(R.string.hub_tab_duplicates)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(messages) },
        bottomBar = {
            if (state.selected.isNotEmpty()) {
                CleanBar(selectedCount = state.selected.size, bytes = state.summary.bytes, enabled = state.canClean, onRequestClean = onRequestClean)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (!state.storageAccessGranted) {
                item(key = "access") {
                    NoticeCard(
                        title = stringResource(R.string.storage_access_off_title),
                        text = stringResource(R.string.storage_access_off_text),
                        icon = Icons.Filled.Lock,
                        tone = NoticeTone.ACTION,
                        actionLabel = stringResource(R.string.action_allow_access),
                        onAction = onAllowAccess,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (state.isScanning) {
                item(key = "scanning") {
                    NoticeCard(
                        title = stringResource(R.string.hub_scanning_title),
                        text = stringResource(R.string.hub_scanning_text),
                        icon = AppIcons.Clock,
                        tone = NoticeTone.INFO,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (!state.hasScanned) {
                item(key = "never-scanned") {
                    EmptyState(
                        icon = AppIcons.Suggestions,
                        title = stringResource(R.string.hub_never_scanned_title),
                        text = stringResource(R.string.hub_never_scanned_text),
                        actionLabel = stringResource(R.string.home_scan_device),
                        onAction = onScan,
                    )
                }
                return@LazyColumn
            }
            if (state.safe != null || state.review != null) {
                item(key = "banner") {
                    SummaryBanner(safe = state.safe, review = state.review, modifier = Modifier.padding(16.dp))
                }
            }
            if (!state.filter.isEmpty) {
                item(key = "filter") {
                    val only = state.filter.type?.label() ?: state.filter.category?.label().orEmpty()
                    AssistChip(
                        onClick = onClearFilter,
                        label = {
                            Text(
                                stringResource(
                                    if (state.filter.type != null) R.string.hub_filter_type else R.string.hub_filter_category,
                                    only,
                                ),
                            )
                        },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.hub_filter_clear))
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            if (state.busy) {
                item(key = "busy") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            when (state.tab) {
                HubTab.ALL_DUPLICATES -> if (state.groups.isEmpty()) {
                    item(key = "empty-duplicates") { EmptyTab(HubTab.ALL_DUPLICATES, state.filter.isEmpty) }
                } else {
                    items(state.groups, key = { it.group.fullHash }) { row ->
                        GroupRow(row = row, onOpen = { onOpenGroup(row.group.fullHash) })
                        HorizontalDivider()
                    }
                }

                else -> if (state.items.isEmpty()) {
                    item(key = "empty-tier") { EmptyTab(state.tab, state.filter.isEmpty) }
                } else {
                    items(state.items, key = { it.fileId }) { suggestion ->
                        SuggestionRow(
                            suggestion = suggestion,
                            selected = suggestion.fileId in selectedIds,
                            canMark = canMark,
                            onToggle = { onToggle(suggestion) },
                            onOpen = { onOpenFile(suggestion.fileId) },
                            onIgnore = { onIgnore(suggestion) },
                            onUnignore = { onUnignore(suggestion.fileId) },
                            onNeverSuggest = { onNeverSuggest(suggestion) },
                            onOpenGroup = if (suggestion.category == SuggestionCategory.EXACT_DUPLICATE) {
                                suggestion.file.fullHash?.let { hash -> { onOpenGroup(hash) } }
                            } else {
                                null
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** The summary at the top of the hub: `2.4 GB safe to clean`, and what wants a review (PRD screen 4). */
@Composable
private fun SummaryBanner(
    safe: TierTotal?,
    review: com.cleansweep.app.domain.model.TierTotal?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(
                    R.string.hub_banner_safe,
                    formatBytes(safe?.totalBytes ?: 0L),
                ),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (safe != null) {
                Text(
                    text = pluralStringResource(
                        R.plurals.item_count,
                        safe.itemCount,
                        formatCount(safe.itemCount.toLong()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (review != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.hub_banner_review, formatBytes(review.totalBytes)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.item_count,
                        review.itemCount,
                        formatCount(review.itemCount.toLong()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun EmptyTab(
    tab: HubTab,
    unfiltered: Boolean,
) {
    if (!unfiltered) {
        EmptyState(icon = AppIcons.Block, title = stringResource(R.string.hub_empty_filtered_title), text = "")
        return
    }
    when (tab) {
        HubTab.SAFE_TO_CLEAN -> EmptyState(
            icon = Icons.Filled.Check,
            title = stringResource(R.string.hub_empty_safe_title),
            text = stringResource(R.string.hub_empty_safe_text),
        )

        HubTab.REVIEW_RECOMMENDED -> EmptyState(
            icon = Icons.Filled.Warning,
            title = stringResource(R.string.hub_empty_review_title),
            text = stringResource(R.string.hub_empty_review_text),
        )

        HubTab.ALL_DUPLICATES -> EmptyState(
            icon = AppIcons.Document,
            title = stringResource(R.string.hub_empty_duplicates_title),
            text = stringResource(R.string.hub_empty_duplicates_text),
        )
    }
}
