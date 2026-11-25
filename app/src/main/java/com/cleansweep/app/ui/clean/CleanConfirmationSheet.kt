package com.cleansweep.app.ui.clean

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cleansweep.app.R
import com.cleansweep.app.ui.common.formatBytes
import com.cleansweep.app.ui.common.formatCount

/**
 * The bottom sheet that asks whether the marked files may go to Safe Trash (PRD 5, step 5): how many, how much,
 * and what they are.
 *
 * Items that were meant to be looked at first need one more acknowledgement before the button unlocks, so that
 * they are never removed with the rest by oversight (PRD section 10, risk 2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanConfirmationSheet(
    summary: CleanSummary,
    isWorking: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var reviewAcknowledged by rememberSaveable { mutableStateOf(false) }
    val mayGoOn = !isWorking && (!summary.needsReview || reviewAcknowledged)

    ModalBottomSheet(
        onDismissRequest = { if (!isWorking) onCancel() },
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            // No half-open state: a sheet that shows the total but cuts off the button helps nobody.
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.clean_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = formatBytes(summary.bytes),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.clean_items,
                    summary.itemCount,
                    formatCount(summary.itemCount.toLong()),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(4.dp))
            for ((category, count) in summary.byCategory) {
                Text(
                    text = stringResource(R.string.clean_breakdown_line, category.label(), formatCount(count.toLong())),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (summary.needsReview) {
                Text(
                    text = pluralStringResource(
                        R.plurals.clean_review_title,
                        summary.reviewCount,
                        formatCount(summary.reviewCount.toLong()),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.clean_review_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = reviewAcknowledged,
                            enabled = !isWorking,
                            role = Role.Checkbox,
                            onValueChange = { reviewAcknowledged = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = reviewAcknowledged,
                        onCheckedChange = null,
                        enabled = !isWorking,
                    )
                    Text(
                        text = stringResource(R.string.clean_review_checkbox),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Text(
                text = stringResource(R.string.clean_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !isWorking,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_cancel), textAlign = TextAlign.Center)
                }
                Button(
                    onClick = onConfirm,
                    enabled = mayGoOn,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isWorking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                    }
                    Text(stringResource(R.string.clean_confirm), textAlign = TextAlign.Center)
                }
            }
        }
    }
}
