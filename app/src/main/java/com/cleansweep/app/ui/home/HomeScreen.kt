package com.cleansweep.app.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleansweep.app.R
import com.cleansweep.app.domain.model.CategoryTotal
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.domain.model.TierTotal
import com.cleansweep.app.domain.scanner.ScanFailure
import com.cleansweep.app.domain.scanner.ScanProgress
import com.cleansweep.app.domain.scanner.ScanState
import com.cleansweep.app.domain.scanner.ScanSummary
import com.cleansweep.app.ui.common.AppIcons
import com.cleansweep.app.ui.common.NoticeCard
import com.cleansweep.app.ui.common.NoticeTone
import com.cleansweep.app.ui.common.OnResumeEffect
import com.cleansweep.app.ui.common.SectionTitle
import com.cleansweep.app.ui.common.chartColor
import com.cleansweep.app.ui.common.formatCount
import com.cleansweep.app.ui.common.formatBytes
import com.cleansweep.app.ui.common.icon
import com.cleansweep.app.ui.common.label
import com.cleansweep.app.ui.theme.CleanSweepTheme
import kotlin.math.roundToInt

/**
 * Screen 2, the Home Dashboard: how full the storage is, what could be freed, and the button that starts a scan
 * (PRD screen 2).
 */
@Composable
fun HomeScreen(
    onOpenSuggestions: (FileCategory?) -> Unit,
    onOpenScan: () -> Unit,
    onOpenTrash: () -> Unit,
    onAllowAccess: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OnResumeEffect { viewModel.onResume() }
    HomeContent(
        state = state,
        onRefreshStorage = viewModel::refreshStorage,
        onStartScan = {
            viewModel.startScan()
            onOpenScan()
        },
        onOpenScan = onOpenScan,
        onOpenSuggestions = onOpenSuggestions,
        onOpenTrash = onOpenTrash,
        onAllowAccess = onAllowAccess,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    onRefreshStorage: () -> Unit,
    onStartScan: () -> Unit,
    onOpenScan: () -> Unit,
    onOpenSuggestions: (FileCategory?) -> Unit,
    onOpenTrash: () -> Unit,
    onAllowAccess: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name)) },
            actions = {
                IconButton(onClick = onRefreshStorage) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.storageAccessGranted) {
                NoticeCard(
                    title = stringResource(R.string.storage_access_off_title),
                    text = stringResource(R.string.storage_access_off_text),
                    icon = Icons.Filled.Lock,
                    tone = NoticeTone.ACTION,
                    actionLabel = stringResource(R.string.action_allow_access),
                    onAction = onAllowAccess,
                )
            }
            ScanNotice(state.scan)
            StorageCard(state)
            ScanButton(
                isScanning = state.isScanning,
                onStartScan = onStartScan,
                onOpenScan = onOpenScan,
            )
            ReclaimableCard(state, onOpenSuggestions = { onOpenSuggestions(null) })
            BreakdownSection(state, onOpenSuggestions = onOpenSuggestions)
            TrashCard(state, onOpenTrash = onOpenTrash)
        }
    }
}

/** The outcome of the last scan, as long as it says something the dashboard cannot show by itself. */
@Composable
private fun ScanNotice(
    scan: ScanState,
) {
    when (scan) {
        is ScanState.Cancelled -> NoticeCard(
            title = stringResource(R.string.home_scan_cancelled_title),
            text = stringResource(R.string.home_scan_cancelled_text),
            tone = NoticeTone.INFO,
        )

        is ScanState.Failed -> when (scan.failure) {
            // The banner about storage access already says this.
            ScanFailure.STORAGE_PERMISSION_MISSING -> Unit
            ScanFailure.NO_STORAGE_TO_SCAN -> NoticeCard(
                title = stringResource(R.string.home_scan_failed_no_storage_title),
                text = stringResource(R.string.home_scan_failed_no_storage_text),
                tone = NoticeTone.WARNING,
            )
            ScanFailure.UNEXPECTED_ERROR -> NoticeCard(
                title = stringResource(R.string.home_scan_failed_unexpected_title),
                text = stringResource(R.string.home_scan_failed_unexpected_text),
                tone = NoticeTone.WARNING,
            )
        }

        is ScanState.Finished -> {
            val summary = scan.summary
            NoticeCard(
                title = stringResource(R.string.home_scan_finished_title),
                text = listOf(
                    pluralStringResource(
                        R.plurals.home_scan_finished_text,
                        summary.filesIndexed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        formatBytes(summary.bytesScanned),
                        formatCount(summary.filesIndexed),
                    ),
                    if (summary.errors > 0) {
                        pluralStringResource(R.plurals.home_scan_errors, summary.errors, formatCount(summary.errors.toLong()))
                    } else {
                        ""
                    },
                ).filter { it.isNotEmpty() }.joinToString(" "),
                tone = NoticeTone.INFO,
            )
        }

        ScanState.Idle, is ScanState.Running -> Unit
    }
}

/** The circular gauge with the used and free space of every volume added up. */
@Composable
private fun StorageCard(
    state: HomeUiState,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val gaugeDescription = if (state.hasStorageSizes) {
                stringResource(
                    R.string.home_storage_gauge_description,
                    (state.usedFraction * 100f).roundToInt(),
                )
            } else {
                stringResource(R.string.home_storage_unknown)
            }
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .semantics(mergeDescendants = true) { contentDescription = gaugeDescription },
                contentAlignment = Alignment.Center,
            ) {
                if (state.hasStorageSizes) {
                    StorageGauge(usedFraction = state.usedFraction, modifier = Modifier.fillMaxSize())
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = formatBytes(state.usedBytes),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            text = stringResource(
                                R.string.home_storage_used_of,
                                formatBytes(state.totalBytes),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.home_storage_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (state.hasStorageSizes) {
                Text(
                    text = stringResource(R.string.home_storage_free, formatBytes(state.freeBytes)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                text = when {
                    !state.hasScanned -> stringResource(R.string.home_never_scanned)
                    state.indexedFiles > 0 -> stringResource(
                        R.string.home_last_scanned,
                        relativeTime(state.lastScanAt ?: 0L, state.now),
                    ) + " · " + pluralStringResource(
                        R.plurals.home_files_indexed,
                        state.indexedFiles,
                        formatCount(state.indexedFiles.toLong()),
                    )
                    else -> stringResource(R.string.home_never_scanned)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "5 minutes ago", in the device's language. */
private fun relativeTime(
    time: Long,
    now: Long,
): String = DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()

/** A ring: the track is the whole storage, the coloured arc the part that is in use. */
@Composable
fun StorageGauge(
    usedFraction: Float,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val used = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = 18.dp.toPx()
        val diameter = minOf(size.width, size.height) - stroke
        if (diameter <= 0f) {
            return@Canvas
        }
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        drawArc(
            color = track,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(stroke),
        )
        if (usedFraction > 0f) {
            // A storage that is nearly empty still gets a visible dot of colour.
            val sweep = 360f * usedFraction.coerceIn(0.004f, 1f)
            drawArc(
                color = used,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** The prominent "Scan device" button, or the way to the progress screen while a scan runs. */
@Composable
private fun ScanButton(
    isScanning: Boolean,
    onStartScan: () -> Unit,
    onOpenScan: () -> Unit,
) {
    if (isScanning) {
        OutlinedButton(onClick = onOpenScan, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.home_scanning), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.home_view_progress))
        }
    } else {
        Button(
            onClick = onStartScan,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(AppIcons.Storage, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.home_scan_device), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = stringResource(R.string.home_scan_device_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What could be freed, by how sure the app is that it may go. */
@Composable
private fun ReclaimableCard(
    state: HomeUiState,
    onOpenSuggestions: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle(stringResource(R.string.home_reclaimable_title))
            Spacer(Modifier.height(8.dp))
            when {
                !state.hasScanned -> Text(stringResource(R.string.home_reclaimable_empty))
                state.safeToClean == null && state.reviewRecommended == null ->
                    Text(stringResource(R.string.home_reclaimable_none))

                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.safeToClean?.let { TierLine(it) }
                    state.reviewRecommended?.let { TierLine(it) }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                    onClick = onOpenSuggestions,
                    // The card is tinted, so the button has to take the colour that reads on the tint.
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onPrimaryContainer),
                ) { Text(stringResource(R.string.home_review_suggestions)) }
        }
    }
}

/** `2.4 GB safe to clean, in 12 files`, or the line for the tier that wants a look first. */
@Composable
private fun TierLine(
    tier: TierTotal,
) {
    val safe = tier.tier == SuggestionTier.SAFE_TO_CLEAN
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (safe) Icons.Filled.Check else AppIcons.Visibility,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(if (safe) R.string.tier_safe_to_clean else R.string.tier_review_recommended),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(
            text = stringResource(
                R.string.home_tier_size,
                formatBytes(tier.totalBytes),
                pluralStringResource(R.plurals.item_count, tier.itemCount, formatCount(tier.itemCount.toLong())),
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** The Quick Category tiles: how much space the scanned files of each kind take up. */
@Composable
private fun BreakdownSection(
    state: HomeUiState,
    onOpenSuggestions: (FileCategory) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.home_breakdown_title))
        if (state.categoryTotals.isEmpty()) {
            Text(
                text = stringResource(R.string.home_breakdown_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        StackedBar(state.categoryTotals, Modifier.fillMaxWidth().height(12.dp))
        state.categoryTotals.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (total in pair) {
                    CategoryTile(total, Modifier.weight(1f), onClick = { onOpenSuggestions(total.category) })
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Text(
            text = stringResource(R.string.home_breakdown_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One bar, one segment per kind of file, each as wide as its share of the scanned bytes. */
@Composable
private fun StackedBar(
    totals: List<CategoryTotal>,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.clip(RoundedCornerShape(6.dp))) {
        for (total in totals) {
            Box(
                modifier = Modifier
                    .weight(total.totalBytes.toFloat().coerceAtLeast(1f))
                    .fillMaxHeight()
                    .background(total.category.chartColor),
            )
        }
    }
}

/** One of the Quick Category tiles. Opening it shows what can be cleaned among files of that kind. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryTile(
    total: CategoryTotal,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // TalkBack reads the tile as one thing: its name, size and file count.
    Card(onClick = onClick, modifier = modifier.semantics(mergeDescendants = true) {}) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = total.category.icon,
                contentDescription = null,
                tint = total.category.chartColor,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = total.category.label(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(text = formatBytes(total.totalBytes), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = pluralStringResource(R.plurals.file_count, total.fileCount, formatCount(total.fileCount.toLong())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrashCard(
    state: HomeUiState,
    onOpenTrash: () -> Unit,
) {
    val summary = when {
        state.trashEntries == 0 -> stringResource(R.string.home_trash_empty)
        else -> stringResource(
            R.string.home_trash_summary,
            formatBytes(state.trashBytes),
            pluralStringResource(R.plurals.file_count, state.trashEntries, formatCount(state.trashEntries.toLong())),
        )
    }
    Card(onClick = onOpenTrash, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            supportingContent = { Text(summary) },
            leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null) },
        ) {
            Text(stringResource(R.string.home_trash_title))
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun HomeScannedPreview() {
    CleanSweepTheme {
        HomeContent(
            state = HomeUiState(
                hasStorageSizes = true,
                totalBytes = 128_000_000_000L,
                freeBytes = 43_500_000_000L,
                categoryTotals = listOf(
                    CategoryTotal(FileCategory.IMAGE, 4212, 38_200_000_000L),
                    CategoryTotal(FileCategory.VIDEO, 310, 29_400_000_000L),
                    CategoryTotal(FileCategory.DOCUMENT, 870, 5_100_000_000L),
                    CategoryTotal(FileCategory.AUDIO, 64, 900_000_000L),
                    CategoryTotal(FileCategory.APK, 9, 410_000_000L),
                    CategoryTotal(FileCategory.OTHER, 1500, 1_800_000_000L),
                ),
                indexedFiles = 6265,
                safeToClean = TierTotal(SuggestionTier.SAFE_TO_CLEAN, 14, 2_400_000_000L),
                reviewRecommended = TierTotal(SuggestionTier.REVIEW_RECOMMENDED, 3, 810_000_000L),
                trashEntries = 2,
                trashBytes = 1_150_000_000L,
                lastScanAt = 1_800_000_000_000L,
                now = 1_800_000_000_000L + 5 * 60 * 1000,
                scan = ScanState.Finished(
                    ScanSummary(
                        rootsScanned = listOf("/storage/emulated/0"),
                        filesIndexed = 6265,
                        bytesScanned = 75_800_000_000L,
                        foldersScanned = 812,
                        emptyFolders = 4,
                        duplicateGroups = 6,
                        duplicateBytes = 1_900_000_000L,
                        suggestions = emptyMap(),
                        errors = 0,
                    ),
                ),
            ),
            onRefreshStorage = {},
            onStartScan = {},
            onOpenScan = {},
            onOpenSuggestions = {},
            onOpenTrash = {},
            onAllowAccess = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun HomeFreshPreview() {
    CleanSweepTheme {
        HomeContent(
            state = HomeUiState(scan = ScanState.Running(ScanProgress(), isPaused = false)),
            onRefreshStorage = {},
            onStartScan = {},
            onOpenScan = {},
            onOpenSuggestions = {},
            onOpenTrash = {},
            onAllowAccess = {},
        )
    }
}
