package com.cleansweep.app.ui.suggestions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleansweep.app.R
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.ui.clean.label
import com.cleansweep.app.ui.common.AppIcons
import com.cleansweep.app.ui.common.Tag
import com.cleansweep.app.ui.common.formatBytes
import com.cleansweep.app.ui.common.formatCount
import com.cleansweep.app.ui.common.icon
import com.cleansweep.app.ui.common.parentForDisplay

/**
 * One suggestion in the hub: what it is, where it is, and why it may go. The whole row is one checkbox for
 * TalkBack and for the finger; the preview has a button of its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SuggestionRow(
    suggestion: Suggestion,
    selected: Boolean,
    canMark: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onIgnore: () -> Unit,
    onUnignore: () -> Unit,
    onNeverSuggest: () -> Unit,
    onOpenGroup: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val ignored = suggestion.isDismissed
    val file = suggestion.file

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = selected,
                enabled = canMark && !ignored,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = selected, onCheckedChange = null, enabled = canMark && !ignored)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = file.category.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Spacer(Modifier.width(8.dp))
                if (ignored) {
                    Tag(stringResource(R.string.hub_row_ignored_tag))
                } else if (suggestion.tier == SuggestionTier.REVIEW_RECOMMENDED) {
                    Tag(
                        text = stringResource(R.string.hub_row_review_tag),
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            val size = if (file.isDirectory) {
                stringResource(R.string.reason_empty_folder)
            } else {
                formatBytes(file.sizeBytes)
            }
            val folder = parentForDisplay(file.path)?.let { stringResource(R.string.hub_row_folder_in, it) }
            Text(
                text = listOfNotNull(size, folder).joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = suggestion.primaryReason.label(), style = MaterialTheme.typography.bodySmall)
            val further = suggestion.reasons.filter { it != suggestion.primaryReason && it != ReasonCode.RECENT }
            if (further.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (reason in further) {
                        Tag(text = reason.label())
                    }
                }
            }
        }
        Column {
            IconButton(onClick = onOpen) {
                Icon(AppIcons.Visibility, contentDescription = stringResource(R.string.hub_row_preview))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.hub_row_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (onOpenGroup != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.hub_row_see_copies)) },
                            onClick = {
                                menuOpen = false
                                onOpenGroup()
                            },
                        )
                    }
                    if (ignored) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.hub_row_unignore)) },
                            onClick = {
                                menuOpen = false
                                onUnignore()
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.hub_row_ignore)) },
                            onClick = {
                                menuOpen = false
                                onIgnore()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.hub_row_never_suggest)) },
                            onClick = {
                                menuOpen = false
                                onNeverSuggest()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One group of identical files in the Duplicates tab. Opening it shows every copy side by side. */
@Composable
internal fun GroupRow(
    row: DuplicateGroupRow,
    onOpen: () -> Unit,
) {
    val description = stringResource(R.string.hub_group_open_hint, formatCount(row.group.copies.size.toLong()))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = description, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = row.category.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(
                    pluralStringResource(
                        R.plurals.copy_count,
                        row.group.copies.size,
                        formatCount(row.group.copies.size.toLong()),
                    ),
                    if (row.reclaimableBytes > 0L) {
                        stringResource(R.string.hub_group_reclaim, formatBytes(row.reclaimableBytes))
                    } else {
                        stringResource(R.string.hub_group_all_kept)
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    R.string.hub_group_original_in,
                    parentForDisplay(row.original.path).orEmpty(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
