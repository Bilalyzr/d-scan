package com.cleansweep.app.ui.scan

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleansweep.app.R
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.scanner.ScanFailure
import com.cleansweep.app.domain.scanner.ScanPhase
import com.cleansweep.app.domain.scanner.ScanProgress
import com.cleansweep.app.domain.scanner.ScanState
import com.cleansweep.app.domain.scanner.ScanSummary
import com.cleansweep.app.ui.common.AppIcons
import com.cleansweep.app.ui.common.formatBytes
import com.cleansweep.app.ui.common.forDisplay
import com.cleansweep.app.ui.common.formatCount
import kotlin.math.roundToInt

/**
 * Screen 3: the progress screen of a running scan (PRD screen 3).
 *
 * It shows how far the scan is and where it is, and lets the user pause and cancel it. It never blocks anything:
 * the scan runs in the scan service, so leaving the screen leaves the scan going.
 */
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onFinished: () -> Unit,
    onAllowAccess: () -> Unit,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The happy path of the PRD: when the scan is done, the app lands on the Suggestions Hub.
    LaunchedEffect(viewModel) {
        viewModel.finished.collect { onFinished() }
    }

    ScanContent(
        state = state,
        onBack = onBack,
        onAllowAccess = onAllowAccess,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onCancel = viewModel::cancel,
        onStartAgain = viewModel::startAgain,
        onReview = onFinished,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScanContent(
    state: ScanState,
    onBack: () -> Unit,
    onAllowAccess: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onStartAgain: () -> Unit,
    onReview: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (state is ScanState.Running) R.string.scan_running_title else R.string.scan_title),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is ScanState.Running -> RunningScan(state, onPause, onResume, onCancel)
                ScanState.Idle -> Message(
                    icon = AppIcons.Storage,
                    title = stringResource(R.string.scan_idle_title),
                    text = stringResource(R.string.scan_idle_text),
                    primaryLabel = stringResource(R.string.home_scan_device),
                    onPrimary = onStartAgain,
                    onBack = onBack,
                )

                is ScanState.Finished -> FinishedScan(state.summary, onReview, onBack)
                is ScanState.Cancelled -> Message(
                    icon = AppIcons.Stop,
                    title = stringResource(R.string.scan_cancelled_title),
                    text = stringResource(R.string.scan_cancelled_text),
                    primaryLabel = stringResource(R.string.scan_again),
                    onPrimary = onStartAgain,
                    onBack = onBack,
                )

                is ScanState.Failed -> when (state.failure) {
                    ScanFailure.STORAGE_PERMISSION_MISSING -> Message(
                        icon = Icons.Filled.Warning,
                        title = stringResource(R.string.scan_failed_permission_title),
                        text = stringResource(R.string.scan_failed_permission_text),
                        primaryLabel = stringResource(R.string.action_allow_access),
                        onPrimary = onAllowAccess,
                        onBack = onBack,
                    )

                    ScanFailure.NO_STORAGE_TO_SCAN -> Message(
                        icon = Icons.Filled.Warning,
                        title = stringResource(R.string.scan_failed_no_storage_title),
                        text = stringResource(R.string.scan_failed_no_storage_text),
                        primaryLabel = stringResource(R.string.scan_again),
                        onPrimary = onStartAgain,
                        onBack = onBack,
                    )

                    ScanFailure.UNEXPECTED_ERROR -> Message(
                        icon = Icons.Filled.Warning,
                        title = stringResource(R.string.scan_failed_unexpected_title),
                        text = stringResource(R.string.scan_failed_unexpected_text),
                        primaryLabel = stringResource(R.string.action_retry),
                        onPrimary = onStartAgain,
                        onBack = onBack,
                    )
                }
            }
        }
    }
}

@Composable
private fun RunningScan(
    state: ScanState.Running,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val progress = state.progress
    val fraction = ScanViewModel.hashingFraction(state)
    val shown by animateFloatAsState(targetValue = fraction ?: 0f, label = "scanProgress")
    val track = MaterialTheme.colorScheme.outlineVariant

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val phaseLabel = phaseLabel(progress.phase)
        val percent = fraction?.let { stringResource(R.string.scan_percent, (shown * 100f).roundToInt()) }
        Box(
            modifier = Modifier
                .size(220.dp)
                .semantics {
                    // TalkBack reads the stage and how far it is, instead of "in progress" without end.
                    progressBarRangeInfo = if (fraction == null) {
                        ProgressBarRangeInfo.Indeterminate
                    } else {
                        ProgressBarRangeInfo(fraction, 0f..1f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (fraction == null) {
                // A bare track, so that the turning arc is seen as the part of a ring that has been done.
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2
                    drawArc(
                        color = track,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke),
                    )
                }
                CircularProgressIndicator(modifier = Modifier.fillMaxSize(), strokeWidth = 12.dp)
            } else {
                CircularProgressIndicator(
                    progress = { shown },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 12.dp,
                    trackColor = track,
                    strokeCap = StrokeCap.Round,
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                Icon(
                    imageVector = if (state.isPaused) AppIcons.Pause else phaseIcon(progress.phase),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                if (percent != null) {
                    Text(percent, style = MaterialTheme.typography.headlineMedium)
                }
                Text(
                    text = if (state.isPaused) stringResource(R.string.scan_paused) else phaseLabel,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
        StatLine(stringResource(R.string.scan_files_scanned, pluralFiles(progress.filesScanned)))
        StatLine(stringResource(R.string.scan_size_scanned, formatBytes(progress.bytesScanned)))
        if (progress.currentFolder != null) {
            Text(
                text = stringResource(R.string.scan_current_folder, forDisplay(progress.currentFolder.orEmpty())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
        val toHash = progress.filesToHash
        if (toHash != null && toHash > 0 && fraction != null) {
            Text(
                text = stringResource(R.string.scan_stage_description, phaseLabel, progress.filesHashed, toHash),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (progress.errors > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.scan_errors,
                    progress.errors,
                    formatCount(progress.errors.toLong()),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = stringResource(R.string.scan_can_leave),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = if (state.isPaused) onResume else onPause,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = if (state.isPaused) Icons.Filled.PlayArrow else AppIcons.Pause,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (state.isPaused) R.string.scan_resume else R.string.scan_pause))
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Icon(AppIcons.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun phaseLabel(
    phase: ScanPhase,
): String = stringResource(
    when (phase) {
        ScanPhase.WALKING -> R.string.scan_phase_walking
        ScanPhase.PARTIAL_HASHING -> R.string.scan_phase_partial
        ScanPhase.FULL_HASHING -> R.string.scan_phase_full
        ScanPhase.ANALYSING -> R.string.scan_phase_analysing
    },
)

private fun phaseIcon(
    phase: ScanPhase,
): ImageVector = when (phase) {
    ScanPhase.WALKING -> AppIcons.Folder
    ScanPhase.PARTIAL_HASHING -> AppIcons.Document
    ScanPhase.FULL_HASHING -> AppIcons.Shield
    ScanPhase.ANALYSING -> AppIcons.Suggestions
}

@Composable
private fun StatLine(
    text: String,
) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun pluralFiles(
    count: Long,
): String = pluralStringResource(
    R.plurals.file_count,
    count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    formatCount(count),
)

@Composable
private fun FinishedScan(
    summary: ScanSummary,
    onReview: () -> Unit,
    onBack: () -> Unit,
) {
    val otherSuggestions = summary.suggestions
        .filterKeys { it != SuggestionCategory.EXACT_DUPLICATE }
        .values
        .sum()
    val groups = summary.duplicateGroups

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Text(stringResource(R.string.scan_finished_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = pluralStringResource(
                R.plurals.scan_finished_files,
                summary.filesIndexed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                formatCount(summary.filesIndexed),
                formatBytes(summary.bytesScanned),
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (groups > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.scan_finished_duplicates,
                    groups,
                    formatCount(groups.toLong()),
                    formatBytes(summary.duplicateBytes),
                ),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
        if (otherSuggestions > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.scan_finished_others,
                    otherSuggestions,
                    formatCount(otherSuggestions.toLong()),
                ),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
        if (groups == 0 && otherSuggestions == 0) {
            Text(
                text = stringResource(R.string.scan_finished_none),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
        if (summary.errors > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.scan_errors,
                    summary.errors,
                    formatCount(summary.errors.toLong()),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.scan_review))
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }
}

/** A scan that is not running: what happened, and what can be done about it. */
@Composable
private fun Message(
    icon: ImageVector,
    title: String,
    text: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp),
        )
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) { Text(primaryLabel) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }
}
