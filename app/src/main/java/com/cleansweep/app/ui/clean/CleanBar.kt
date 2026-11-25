package com.cleansweep.app.ui.clean

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cleansweep.app.R
import com.cleansweep.app.ui.common.formatBytes
import com.cleansweep.app.ui.common.formatCount

/**
 * The sticky bar above a list: how much is marked, and the way to Safe Trash (PRD screen 4, "Clean Selected").
 * Shared by the Suggestions Hub and the screen of one group of duplicates.
 */
@Composable
fun CleanBar(
    selectedCount: Int,
    bytes: Long,
    enabled: Boolean,
    onRequestClean: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(
                    text = pluralStringResource(
                        R.plurals.hub_selected,
                        selectedCount,
                        formatCount(selectedCount.toLong()),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    text = formatBytes(bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onRequestClean, enabled = enabled) {
                Text(stringResource(R.string.hub_move_to_trash_sized, formatBytes(bytes)), maxLines = 1)
            }
        }
    }
}
