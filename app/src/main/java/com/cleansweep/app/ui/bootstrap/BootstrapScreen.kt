package com.cleansweep.app.ui.bootstrap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleansweep.app.R
import com.cleansweep.app.ui.theme.CleanSweepTheme

/**
 * Temporary Phase 1 screen. It only shows live aggregates from the database to confirm the wiring;
 * Phase 4 replaces it with onboarding and the home dashboard.
 */
@Composable
fun BootstrapScreen(viewModel: BootstrapViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BootstrapContent(state)
}

@Composable
private fun BootstrapContent(state: BootstrapUiState) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = stringResource(R.string.bootstrap_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatRow(
                label = stringResource(R.string.bootstrap_stat_indexed_files),
                value = state.indexedFiles.toString(),
            )
            StatRow(
                label = stringResource(R.string.bootstrap_stat_safe_to_clean),
                value = (state.safeToClean?.itemCount ?: 0).toString(),
            )
            StatRow(
                label = stringResource(R.string.bootstrap_stat_review_recommended),
                value = (state.reviewRecommended?.itemCount ?: 0).toString(),
            )
            StatRow(
                label = stringResource(R.string.bootstrap_stat_whitelist_rules),
                value = state.whitelistRules.toString(),
            )
            StatRow(
                label = stringResource(R.string.bootstrap_stat_trash_entries),
                value = state.trashEntries.toString(),
            )
            state.categoryTotals.values.sortedBy { it.category.name }.forEach { total ->
                Text(
                    text = pluralStringResource(
                        R.plurals.bootstrap_category_row,
                        total.fileCount,
                        total.category.name,
                        total.fileCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Merged into one semantics node so TalkBack announces the label and its value together. */
@Composable
private fun StatRow(label: String, value: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BootstrapContentPreview() {
    CleanSweepTheme {
        BootstrapContent(
            BootstrapUiState(
                indexedFiles = 1284,
                whitelistRules = 2,
                trashEntries = 0,
            ),
        )
    }
}
