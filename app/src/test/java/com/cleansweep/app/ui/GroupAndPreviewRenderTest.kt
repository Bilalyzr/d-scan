package com.cleansweep.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.domain.scanner.duplicates.AutoSelectRule
import com.cleansweep.app.file
import com.cleansweep.app.ui.group.CopyView
import com.cleansweep.app.ui.group.GroupContent
import com.cleansweep.app.ui.group.GroupUiState
import com.cleansweep.app.ui.preview.ContentState
import com.cleansweep.app.ui.preview.PreviewContent
import com.cleansweep.app.ui.preview.PreviewKind
import com.cleansweep.app.ui.preview.PreviewUiState
import com.cleansweep.app.ui.theme.CleanSweepTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the screen of one group of duplicates and the preview, and clicks through what they do. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h760dp-xhdpi")
class GroupAndPreviewRenderTest {

    @get:Rule
    val rule = createComposeRule()

    /** Shows [content] in the brand palette, on the background the app's root paints behind every screen. */
    private fun set(
        content: @Composable () -> Unit,
    ) {
        rule.setContent {
            CleanSweepTheme(darkTheme = false, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        rule.waitForIdle()
    }

    // ---- The group of duplicates ----

    @Composable
    private fun Group(
        state: GroupUiState,
        onKeep: (CopyView) -> Unit = {},
        onToggle: (CopyView) -> Unit = {},
        onRule: (AutoSelectRule) -> Unit = {},
    ) {
        GroupContent(
            state = state,
            messages = remember { SnackbarHostState() },
            onBack = {},
            onOpenFile = {},
            onAllowAccess = {},
            onToggle = onToggle,
            onKeep = onKeep,
            onRelease = {},
            onIgnore = {},
            onUnignore = {},
            onRule = onRule,
            onRequestClean = {},
        )
    }

    @Test
    fun `the group shows the kept copy first, the copies side by side, and what can be ticked`() {
        set { Group(groupState) }

        rule.saveScreen("group")
        rule.onNodeWithText("3 copies · 1.2 MB each").assertIsDisplayed()
        rule.onNodeWithText("2.4 MB can be freed").assertIsDisplayed()
        rule.onNodeWithText("Tick by rule").assertIsDisplayed()
        rule.onNodeWithText("Compare").assertIsDisplayed()
        // The compare strip and the first card both say it.
        rule.onAllNodesWithText("Kept copy").onFirst().assertIsDisplayed()
        rule.onNodeWithText("Copy 2").assertExists()
    }

    @Test
    fun `each copy shows its path, size and dates, and the way to keep it`() {
        set { Group(groupState) }

        rule.onNodeWithText("/storage/emulated/0/Documents/report.pdf").assertIsDisplayed()
        rule.onNodeWithText("Internal storage/Documents").assertIsDisplayed()
        rule.onAllNodesWithText("Keep this copy").onFirst().assertIsDisplayed()
        rule.onAllNodesWithText("Preview").onFirst().assertIsDisplayed()
        rule.onNodeWithText("Kept by the group's rule. Keep another copy to offer this one.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Kept copy").assertExists()
    }

    @Test
    fun `ticking a copy puts the bar with the way to the trash on the screen`() {
        set { Group(groupState.copy(copies = groupState.copies.map { if (it.file.id == 2L) it.copy(isSelected = true) else it })) }

        rule.onNodeWithText("1 selected").assertIsDisplayed()
        rule.onNodeWithText("Move to Trash (1.2 MB)").assertIsDisplayed()
        rule.saveScreen("group-selected")
    }

    @Test
    fun `keeping a copy and ticking by rule reach the screen's ViewModel`() {
        var kept: Long? = null
        var ruled: AutoSelectRule? = null
        set {
            Group(
                groupState,
                onKeep = { kept = it.file.id },
                onRule = { ruled = it },
            )
        }

        // The second card is the copy in Download, which is offered; its chip is below the fold until scrolled to.
        rule.onAllNodesWithText("Keep this copy")[1].performScrollTo().performClick()
        rule.onNodeWithText("Tick by rule").performScrollTo().performClick()
        rule.onNodeWithText("Keep the oldest copy").performClick()

        assertEquals(1L, kept)
        assertEquals(AutoSelectRule.KEEP_OLDEST, ruled)
    }

    @Test
    fun `when the files are no longer a group the screen says so`() {
        set { Group(GroupUiState(loading = false)) }

        rule.onNodeWithText("These files are no longer identical copies").assertIsDisplayed()
        rule.saveScreen("group-gone")
    }

    // ---- The preview ----

    @Test
    fun `the preview of a text file shows its text and the choice about the copy`() {
        set {
            PreviewContent(
                state = PreviewUiState(loading = false, file = textFile, kind = PreviewKind.TEXT, isDuplicate = true, isOffered = true, content = ContentState.READY),
                text = "Quarterly report\nRevenue is up.",
                pages = emptyList(),
                messages = remember { SnackbarHostState() },
                onClose = {},
                onKeep = {},
                onRelease = {},
                onMark = {},
                picture = { Box(Modifier.fillMaxSize()) },
                player = { _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }

        rule.saveScreen("preview-text")
        rule.onNodeWithText("Revenue is up.", substring = true).assertExists()
        rule.onNodeWithText("Keep this copy").assertIsDisplayed()
        rule.onNodeWithText("Mark for deletion").assertIsEnabled()
    }

    @Test
    fun `the preview of the kept copy says so, and offers no marking`() {
        set {
            PreviewContent(
                state = PreviewUiState(loading = false, file = textFile, kind = PreviewKind.TEXT, isDuplicate = true, isOriginal = true, content = ContentState.READY),
                text = "Quarterly report",
                pages = emptyList(),
                messages = remember { SnackbarHostState() },
                onClose = {},
                onKeep = {},
                onRelease = {},
                onMark = {},
                picture = { Box(Modifier.fillMaxSize()) },
                player = { _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }

        rule.onNodeWithText("This is the copy its group keeps. Keep another copy to offer this one.").assertIsDisplayed()
        rule.onNodeWithText("Mark for deletion").assertIsNotEnabled()
        rule.saveScreen("preview-kept")
    }

    @Test
    fun `a file with no preview shows what is known about it`() {
        set {
            PreviewContent(
                state = PreviewUiState(loading = false, file = textFile.copy(name = "table.xlsx", extension = "xlsx"), kind = PreviewKind.NONE, content = ContentState.READY),
                text = null,
                pages = emptyList(),
                messages = remember { SnackbarHostState() },
                onClose = {},
                onKeep = {},
                onRelease = {},
                onMark = {},
                picture = { Box(Modifier.fillMaxSize()) },
                player = { _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }

        rule.onNodeWithText("No preview for this kind of file").assertIsDisplayed()
        rule.saveScreen("preview-none")
    }

    @Test
    fun `a picture is drawn where the preview has room, and the bar stays below it`() {
        set {
            PreviewContent(
                state = PreviewUiState(loading = false, file = pictureFile, kind = PreviewKind.IMAGE, isDuplicate = true, isOffered = true, content = ContentState.READY),
                text = null,
                pages = emptyList(),
                messages = remember { SnackbarHostState() },
                onClose = {},
                onKeep = {},
                onRelease = {},
                onMark = {},
                picture = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("picture stands in here") } },
                player = { _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }

        rule.onNodeWithText("picture stands in here").assertIsDisplayed()
        rule.onNodeWithText("Mark for deletion").assertIsDisplayed()
        rule.saveScreen("preview-picture")
    }

    @Test
    fun `ticking and keeping reach the preview's ViewModel`() {
        var marked: Boolean? = null
        var kept = 0
        set {
            PreviewContent(
                state = PreviewUiState(loading = false, file = textFile, kind = PreviewKind.TEXT, isDuplicate = true, isOffered = true, content = ContentState.READY),
                text = "Quarterly report",
                pages = emptyList(),
                messages = remember { SnackbarHostState() },
                onClose = {},
                onKeep = { kept++ },
                onRelease = {},
                onMark = { marked = it },
                picture = { Box(Modifier.fillMaxSize()) },
                player = { _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }

        rule.onNodeWithText("Mark for deletion").performClick()
        rule.onNodeWithText("Keep this copy").performClick()

        assertEquals(true, marked)
        assertEquals(1, kept)
    }

    // ---- What the screens are given ----

    private val keptCopy = file(
        "/storage/emulated/0/Documents/report.pdf",
        sizeBytes = 1_200_000L,
        lastModified = 1_700_000_000_000L,
        category = FileCategory.DOCUMENT,
        fullHash = "h",
    ).copy(id = 3L)

    private val downloadCopy = file(
        "/storage/emulated/0/Download/report.pdf",
        sizeBytes = 1_200_000L,
        lastModified = 1_750_000_000_000L,
        category = FileCategory.DOCUMENT,
        fullHash = "h",
    ).copy(id = 1L)

    private val backupCopy = file(
        "/storage/emulated/0/Backup/report.pdf",
        sizeBytes = 1_200_000L,
        lastModified = 1_780_000_000_000L,
        category = FileCategory.DOCUMENT,
        fullHash = "h",
    ).copy(id = 2L)

    private fun offered(
        copy: IndexedFile,
        score: Int = 100,
    ) = Suggestion(copy.id, SuggestionCategory.EXACT_DUPLICATE, score, ReasonCode.EXACT_COPY, listOf(ReasonCode.EXACT_COPY), false, copy)

    /** The Documents copy is kept; Download and Backup are offered. */
    private val groupState = GroupUiState(
        loading = false,
        copies = listOf(
            CopyView(keptCopy, isOriginal = true, isKeptByUser = false, suggestion = null, isSelected = false),
            CopyView(downloadCopy, isOriginal = false, isKeptByUser = false, suggestion = offered(downloadCopy), isSelected = false),
            CopyView(backupCopy, isOriginal = false, isKeptByUser = false, suggestion = offered(backupCopy), isSelected = false),
        ),
    )

    private val textFile = file(
        "/storage/emulated/0/Documents/notes.txt",
        sizeBytes = 900L,
        lastModified = 1_770_000_000_000L,
        category = FileCategory.DOCUMENT,
    ).copy(id = 7L)

    private val pictureFile = file(
        "/storage/emulated/0/DCIM/Camera/IMG_1.jpg",
        sizeBytes = 42_000_000L,
        lastModified = 1_770_000_000_000L,
        category = FileCategory.IMAGE,
    ).copy(id = 8L)
}
