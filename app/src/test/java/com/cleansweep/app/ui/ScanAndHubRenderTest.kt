package com.cleansweep.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.domain.model.DuplicateGroup
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.domain.model.TierTotal
import com.cleansweep.app.domain.scanner.ScanFailure
import com.cleansweep.app.domain.scanner.ScanPhase
import com.cleansweep.app.domain.scanner.ScanProgress
import com.cleansweep.app.domain.scanner.ScanState
import com.cleansweep.app.domain.scanner.ScanSummary
import com.cleansweep.app.file
import com.cleansweep.app.ui.clean.CleanConfirmationSheet
import com.cleansweep.app.ui.clean.CleanSummary
import com.cleansweep.app.ui.scan.ScanContent
import com.cleansweep.app.ui.suggestions.DuplicateGroupRow
import com.cleansweep.app.ui.suggestions.HubTab
import com.cleansweep.app.ui.suggestions.HubUiState
import com.cleansweep.app.ui.suggestions.SuggestionsContent
import com.cleansweep.app.ui.theme.CleanSweepTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the scan screen, the Suggestions Hub and the confirmation sheet, and clicks through what they do. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h760dp-xhdpi")
class ScanAndHubRenderTest {

    @get:Rule
    val rule = createComposeRule()

    /** Shows [content] in the brand palette, on the background the app's root paints behind every screen. */
    private fun set(
        darkTheme: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        rule.setContent {
            CleanSweepTheme(darkTheme = darkTheme, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        rule.waitForIdle()
    }

    // ---- The scan screen ----

    @Composable
    private fun scan(
        state: ScanState,
    ) {
        ScanContent(
            state = state,
            onBack = {},
            onAllowAccess = {},
            onPause = {},
            onResume = {},
            onCancel = {},
            onStartAgain = {},
            onReview = {},
        )
    }

    @Test
    fun `while folders are read the ring keeps turning and shows where the scan is`() {
        set {
            scan(
                ScanState.Running(
                    ScanProgress(
                        phase = ScanPhase.WALKING,
                        filesScanned = 1_234L,
                        bytesScanned = 5_600_000_000L,
                        currentFolder = "/storage/emulated/0/DCIM/Camera",
                    ),
                    isPaused = false,
                ),
            )
        }

        rule.onNodeWithText("Scanning").assertIsDisplayed()
        rule.onNodeWithText("Looking through your files").assertIsDisplayed()
        rule.onNodeWithText("Now in Internal storage/DCIM/Camera").assertIsDisplayed()
        rule.onNodeWithText("Pause").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.saveScreen("scan-walking")
    }

    @Test
    fun `while files are read the ring shows how far the stage is, and a paused scan says so`() {
        set {
            scan(
                ScanState.Running(
                    ScanProgress(
                        phase = ScanPhase.FULL_HASHING,
                        filesScanned = 9_000L,
                        bytesScanned = 40_000_000_000L,
                        filesHashed = 30,
                        filesToHash = 80,
                    ),
                    isPaused = true,
                ),
            )
        }

        rule.onNodeWithText("Paused").assertIsDisplayed()
        rule.onNodeWithText("Resume").assertIsDisplayed()
        rule.onNodeWithText("30 of 80 files done", substring = true).assertIsDisplayed()
        rule.onNodeWithText("38%").assertIsDisplayed()
        rule.saveScreen("scan-paused-hashing")
    }

    @Test
    fun `a finished scan sums up what it found and leads on to the suggestions`() {
        set { scan(ScanState.Finished(finishedSummary)) }

        rule.onNodeWithText("Scan finished").assertIsDisplayed()
        rule.onNodeWithText("40 GB in 9,000 files").assertIsDisplayed()
        rule.onNodeWithText("6 groups of identical files, with 1.9 GB in copies you do not need").assertIsDisplayed()
        rule.onNodeWithText("Review suggestions").assertIsDisplayed()
        rule.saveScreen("scan-finished")
    }

    @Test
    fun `a cancelled scan says that what was found is kept`() {
        set { scan(ScanState.Cancelled(ScanProgress(filesScanned = 12L))) }

        rule.onNodeWithText("Scan cancelled").assertIsDisplayed()
        rule.onNodeWithText("Scan again").assertIsDisplayed()
        rule.saveScreen("scan-cancelled")
    }

    @Test
    fun `a scan that could not start for want of access offers the way to the permission`() {
        set { scan(ScanState.Failed(ScanFailure.STORAGE_PERMISSION_MISSING)) }

        rule.onNodeWithText("Storage access is off").assertIsDisplayed()
        rule.onNodeWithText("Allow storage access").assertIsDisplayed()
        rule.saveScreen("scan-no-access")
    }

    // ---- The Suggestions Hub ----

    @Composable
    private fun Hub(
        state: HubUiState,
        onToggle: (Suggestion) -> Unit = {},
    ) {
        SuggestionsContent(
            state = state,
            messages = remember { SnackbarHostState() },
            onScan = {},
            onAllowAccess = {},
            onOpenGroup = {},
            onOpenFile = {},
            onSelectTab = {},
            onToggle = onToggle,
            onIgnore = {},
            onUnignore = {},
            onNeverSuggest = {},
            onShowIgnored = {},
            onSelectAllSafe = {},
            onUnselectAll = {},
            onAutoSelect = {},
            onClearFilter = {},
            onRequestClean = {},
        )
    }

    @Test
    fun `the hub shows what is safe to clean, with the bar that moves it to the trash`() {
        set {
            Hub(
                hubState.copy(
                    items = listOf(safePhoto, safeLog),
                    selected = listOf(safePhoto, safeLog),
                ),
            )
        }

        rule.onNodeWithText("2.4 GB safe to clean").assertIsDisplayed()
        rule.onNodeWithText("IMG_20250101.jpg").assertIsDisplayed()
        rule.onNodeWithText("Exact copy of another file").assertIsDisplayed()
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        rule.onNodeWithText("Move to Trash (84 MB)").assertIsDisplayed()
        rule.saveScreen("hub-safe")
    }

    @Test
    fun `the review tab marks its items as needing a look first`() {
        set { Hub(hubState.copy(tab = HubTab.REVIEW_RECOMMENDED, items = listOf(reviewVideo))) }

        rule.onNodeWithText("old-holiday.mp4").assertIsDisplayed()
        // The tab and the tag on the row are both called "Review".
        rule.onAllNodesWithText("Review").onFirst().assertIsDisplayed()
        rule.onNodeWithText("Untouched for 90 days or longer").assertIsDisplayed()
        rule.saveScreen("hub-review")
    }

    @Test
    fun `the duplicates tab lists each group with the copy it keeps`() {
        set { Hub(hubState.copy(tab = HubTab.ALL_DUPLICATES, groups = listOf(groupRow))) }

        rule.saveScreen("hub-duplicates")
        rule.onNodeWithText("report.pdf").assertIsDisplayed()
        rule.onNodeWithText("3 copies · 2.4 MB can be freed").assertIsDisplayed()
        rule.onNodeWithText("Kept copy: Internal storage/Documents").assertIsDisplayed()
    }

    @Test
    fun `nothing can be moved while a scan runs, and the hub says why`() {
        set {
            Hub(
                hubState.copy(
                    items = listOf(safePhoto),
                    selected = listOf(safePhoto),
                    isScanning = true,
                ),
            )
        }

        rule.onNodeWithText("A scan is running").assertIsDisplayed()
        rule.onNodeWithText("Move to Trash (42 MB)").assertIsNotEnabled()
        rule.saveScreen("hub-scanning")
    }

    @Test
    fun `tapping a row is what ticks it`() {
        var toggled: Suggestion? = null
        set {
            Hub(
                hubState.copy(tab = HubTab.REVIEW_RECOMMENDED, items = listOf(reviewVideo)),
                onToggle = { toggled = it },
            )
        }

        rule.onNodeWithText("old-holiday.mp4").performClick()

        assertEquals(reviewVideo, toggled)
    }

    @Test
    fun `the hub before any scan offers to scan`() {
        set { Hub(HubUiState(hasScanned = false)) }

        rule.onNodeWithText("No scan yet").assertIsDisplayed()
        rule.onNodeWithText("Scan device").assertIsDisplayed()
        rule.saveScreen("hub-never-scanned")
    }

    @Test
    fun `the hub in dark colours`() {
        set(darkTheme = true) {
            Hub(hubState.copy(items = listOf(safePhoto, safeLog), selected = listOf(safePhoto)))
        }

        rule.onNodeWithText("2.4 GB safe to clean").assertIsDisplayed()
        rule.saveScreen("hub-dark")
    }

    // ---- The confirmation sheet ----

    @Composable
    private fun sheet(
        summary: CleanSummary,
    ) {
        CleanConfirmationSheet(summary = summary, isWorking = false, onConfirm = {}, onCancel = {})
    }

    @Test
    fun `the sheet sums up what goes to the trash, and asks once more when a review item is among them`() {
        set { sheet(CleanSummary.of(listOf(safePhoto, safeLog, reviewVideo))) }

        rule.onNodeWithText("894 MB").assertIsDisplayed()
        rule.onNodeWithText("3 items").assertIsDisplayed()
        rule.onNodeWithText("Exact copies: 1").assertIsDisplayed()
        rule.onNodeWithText("Temporary files: 1").assertIsDisplayed()
        rule.onNodeWithText("Large files, long unused: 1").assertIsDisplayed()
        rule.onNodeWithText("1 needs a review first").assertIsDisplayed()
        val confirm = rule.onNode(hasText("Move to Trash") and hasClickAction())
        confirm.assertIsNotEnabled()
        rule.saveScreen("clean-sheet-review")

        rule.onNodeWithText("I have looked at the items that need a review").performClick()
        rule.waitForIdle()

        confirm.assertIsEnabled()
    }

    @Test
    fun `the sheet for safe items alone needs no extra acknowledgement`() {
        set { sheet(CleanSummary.of(listOf(safePhoto))) }

        rule.onNodeWithText("1 item").assertIsDisplayed()
        rule.onNodeWithText("I have looked at the items that need a review").assertDoesNotExist()
        rule.onNode(hasText("Move to Trash") and hasClickAction()).assertIsEnabled()
        rule.saveScreen("clean-sheet-safe")
    }

    // ---- What the screens are given ----

    private val safePhoto = Suggestion(
        fileId = 1L,
        category = SuggestionCategory.EXACT_DUPLICATE,
        confidenceScore = 100,
        primaryReason = ReasonCode.EXACT_COPY,
        reasons = listOf(ReasonCode.EXACT_COPY),
        isDismissed = false,
        file = file(
            "/storage/emulated/0/DCIM/Camera/IMG_20250101.jpg",
            sizeBytes = 42_000_000L,
            category = FileCategory.IMAGE,
            fullHash = "a",
        ),
    )

    private val safeLog = Suggestion(
        fileId = 2L,
        category = SuggestionCategory.TEMP_FILE,
        confidenceScore = 100,
        primaryReason = ReasonCode.TEMP_OR_LOG,
        reasons = listOf(ReasonCode.TEMP_OR_LOG, ReasonCode.STALE_90_DAYS),
        isDismissed = false,
        file = file("/storage/emulated/0/Download/setup.log", sizeBytes = 42_000_000L),
    )

    private val reviewVideo = Suggestion(
        fileId = 3L,
        category = SuggestionCategory.LARGE_FORGOTTEN,
        confidenceScore = 65,
        primaryReason = ReasonCode.LARGE_FILE,
        reasons = listOf(ReasonCode.LARGE_FILE, ReasonCode.STALE_90_DAYS, ReasonCode.IN_DOWNLOADS),
        isDismissed = false,
        file = file(
            "/storage/emulated/0/Download/old-holiday.mp4",
            sizeBytes = 810_000_000L,
            category = FileCategory.VIDEO,
        ),
    )

    private val hubState = HubUiState(
        hasScanned = true,
        safe = TierTotal(SuggestionTier.SAFE_TO_CLEAN, 14, 2_400_000_000L),
        review = TierTotal(SuggestionTier.REVIEW_RECOMMENDED, 3, 810_000_000L),
    )

    private val finishedSummary = ScanSummary(
        rootsScanned = listOf("/storage/emulated/0"),
        filesIndexed = 9_000L,
        bytesScanned = 40_000_000_000L,
        foldersScanned = 800L,
        emptyFolders = 2,
        duplicateGroups = 6,
        duplicateBytes = 1_900_000_000L,
        suggestions = emptyMap(),
        errors = 0,
    )

    /** Three identical files; the one in Documents is the copy kept by the usual rule. */
    private val groupRow: DuplicateGroupRow
        get() {
            val kept = file("/storage/emulated/0/Documents/report.pdf", sizeBytes = 1_200_000L, lastModified = 100L, fullHash = "h").copy(id = 2L)
            val copies = listOf(
                kept,
                file("/storage/emulated/0/Download/report.pdf", sizeBytes = 1_200_000L, lastModified = 200L, fullHash = "h").copy(id = 1L),
                file("/storage/emulated/0/Backup/report.pdf", sizeBytes = 1_200_000L, lastModified = 300L, fullHash = "h").copy(id = 3L),
            )
            return DuplicateGroupRow(DuplicateGroup(fullHash = "h", copies = copies), original = kept)
        }
}
