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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.domain.model.ThemeMode
import com.cleansweep.app.domain.model.TrashEntry
import com.cleansweep.app.domain.model.UserPreferences
import com.cleansweep.app.domain.model.WhitelistRule
import com.cleansweep.app.domain.model.WhitelistRuleType
import com.cleansweep.app.ui.settings.FolderPicker
import com.cleansweep.app.ui.settings.FolderPickerState
import com.cleansweep.app.ui.settings.SettingsContent
import com.cleansweep.app.ui.settings.SettingsUiState
import com.cleansweep.app.ui.theme.CleanSweepTheme
import com.cleansweep.app.ui.trash.TrashContent
import com.cleansweep.app.ui.trash.TrashRow
import com.cleansweep.app.ui.trash.TrashUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the Safe Trash screen and the Settings screen, and clicks through what they do. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h760dp-xhdpi")
class TrashAndSettingsRenderTest {

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

    // ---- Safe Trash ----

    private fun setTrash(
        state: TrashUiState,
        onRestore: (Long) -> Unit = {},
        onRestoreEverything: () -> Unit = {},
        onRequestEmpty: () -> Unit = {},
        onEmpty: () -> Unit = {},
    ) {
        set {
            TrashContent(
                state = state,
                messages = remember { SnackbarHostState() },
                onAllowAccess = {},
                onRestore = onRestore,
                onRestoreEverything = onRestoreEverything,
                onRequestEmpty = onRequestEmpty,
                onEmpty = onEmpty,
                onCancelEmpty = {},
            )
        }
    }

    @Test
    fun `the bin shows what it holds, how long it stays, and the two ways out`() {
        setTrash(trashState)

        rule.saveScreen("trash")
        rule.onNodeWithText("Safe Trash").assertIsDisplayed()
        rule.onNodeWithText("1.2 MB").assertIsDisplayed()
        rule.onNodeWithText("2 items").assertIsDisplayed()
        rule.onNodeWithText("The next ones go in 5 days.").assertIsDisplayed()
        rule.onNodeWithText("25 days left").assertIsDisplayed()
        rule.onNodeWithText("5 days left").assertIsDisplayed()
        rule.onAllNodesWithText("Put back").onFirst().assertIsDisplayed()
        rule.onNodeWithText("Empty bin").assertIsDisplayed()
        rule.onNodeWithText("Put everything back").assertIsDisplayed()
    }

    @Test
    fun `an entry whose time is up says so`() {
        setTrash(trashState.copy(rows = trashState.rows.map { it.copy(daysLeft = 0) }))
        rule.onAllNodesWithText("Due to be deleted").onFirst().assertIsDisplayed()
        rule.onNodeWithText("Some of it is due to be deleted today.").assertIsDisplayed()
    }

    @Test
    fun `an empty bin says so, and offers nothing to do`() {
        setTrash(TrashUiState())

        rule.onNodeWithText("Safe Trash is empty").assertIsDisplayed()
        rule.onNodeWithText("Empty bin").assertDoesNotExist()
        rule.saveScreen("trash-empty")
    }

    @Test
    fun `putting back and asking to empty reach the screen's ViewModel`() {
        var restored: Long? = null
        var asked = 0
        setTrash(trashState, onRestore = { restored = it }, onRequestEmpty = { asked++ })

        rule.onAllNodesWithText("Put back")[1].performClick()
        rule.onNodeWithText("Empty bin").performClick()

        assertEquals(22L, restored)
        assertEquals(1, asked)
    }

    @Test
    fun `emptying asks once more, in so many words`() {
        var emptied = 0
        set {
            TrashContent(
                state = trashState.copy(confirmingEmpty = true),
                messages = remember { SnackbarHostState() },
                onAllowAccess = {},
                onRestore = {},
                onRestoreEverything = {},
                onRequestEmpty = {},
                onEmpty = { emptied++ },
                onCancelEmpty = {},
            )
        }

        rule.saveScreen("trash-confirm")
        rule.onNodeWithText("Empty Safe Trash?").assertIsDisplayed()
        rule.onNodeWithText("The 2 files in Safe Trash (1.2 MB) are deleted for good. This cannot be undone.").assertIsDisplayed()

        rule.onNode(hasText("Delete for good") and hasClickAction()).performClick()
        assertEquals(1, emptied)
    }

    // ---- Settings ----

    private fun setSettings(
        state: SettingsUiState,
        onAddFolder: () -> Unit = {},
        onRemoveRule: (WhitelistRule) -> Unit = {},
        onTheme: (ThemeMode) -> Unit = {},
    ) {
        set {
            SettingsContent(
                state = state,
                messages = remember { SnackbarHostState() },
                onRemoveRule = onRemoveRule,
                onAddFolder = onAddFolder,
                onMinFileSize = {},
                onLargeFileThreshold = {},
                onLargeFileStaleDays = {},
                onBackgroundIndexing = {},
                onTheme = onTheme,
            )
        }
    }

    @Test
    fun `the settings show the exclusions, the scan thresholds, the background switch and the theme`() {
        setSettings(settingsState)

        rule.saveScreen("settings")
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("Internal storage/Documents/Tax").assertIsDisplayed()
        rule.onNodeWithText("Folder, with everything below it").assertIsDisplayed()
        rule.onNodeWithContentDescription("Stop excluding").assertIsDisplayed()
        rule.onNodeWithText("Add a folder").assertIsDisplayed()
        // Further down the page than one screen shows: present, not in view.
        rule.onNodeWithText("100 KB").assertExists()
        rule.onNodeWithText("10 MB").assertExists()
        rule.onNodeWithText("Files over 50 MB count as large.").assertExists()
        rule.onNodeWithText("A large file untouched for 90 days is suggested.").assertExists()
        rule.onNodeWithText("Index in the background").assertExists()
        rule.onNodeWithText("System").assertExists()
        rule.onNodeWithText("Dark").assertExists()
    }

    @Test
    fun `nothing excluded yet says so`() {
        setSettings(settingsState.copy(rules = emptyList()))

        rule.onNodeWithText("Nothing is excluded yet.").assertIsDisplayed()
    }

    @Test
    fun `adding a folder, ending an exclusion and choosing a theme reach the screen's ViewModel`() {
        var added = 0
        var removed: Long? = null
        var theme: ThemeMode? = null
        setSettings(settingsState, onAddFolder = { added++ }, onRemoveRule = { removed = it.id }, onTheme = { theme = it })

        rule.onNodeWithText("Add a folder").performClick()
        rule.onNodeWithContentDescription("Stop excluding").performClick()
        rule.onNodeWithText("Dark").performScrollTo().performClick()

        assertEquals(1, added)
        assertEquals(7L, removed)
        assertEquals(ThemeMode.DARK, theme)
    }

    @Test
    fun `the picker lists what can be entered, and offers to exclude the folder it is on`() {
        var opened: String? = null
        var excluded = 0
        set {
            FolderPicker(
                picker = FolderPickerState(
                    path = "/storage/emulated/0/Documents",
                    folders = listOf("/storage/emulated/0/Documents/Tax", "/storage/emulated/0/Documents/Work"),
                    loading = false,
                    canExclude = true,
                ),
                enabled = true,
                onOpen = { opened = it },
                onUp = {},
                onExclude = { excluded++ },
                onClose = {},
            )
        }

        rule.saveScreen("settings-picker")
        rule.onNodeWithText("Internal storage/Documents").assertIsDisplayed()
        rule.onNodeWithText("Tax").assertIsDisplayed()
        rule.onNodeWithText("Work").assertIsDisplayed()
        val exclude = rule.onNode(hasText("Exclude this folder") and hasClickAction())
        exclude.assertIsEnabled()

        rule.onNodeWithText("Tax").performClick()
        exclude.performClick()

        assertEquals("/storage/emulated/0/Documents/Tax", opened)
        assertEquals(1, excluded)
    }

    @Test
    fun `on a storage itself, nothing can be excluded`() {
        set {
            FolderPicker(
                picker = FolderPickerState(
                    path = "/storage/emulated/0",
                    folders = listOf("/storage/emulated/0/Documents"),
                    loading = false,
                    canExclude = false,
                ),
                enabled = true,
                onOpen = {},
                onUp = {},
                onExclude = {},
                onClose = {},
            )
        }

        rule.onNodeWithText("A whole storage cannot be excluded: there would be nothing left to scan.").assertIsDisplayed()
        rule.onNode(hasText("Exclude this folder") and hasClickAction()).assertIsNotEnabled()
        rule.saveScreen("settings-picker-storage")
    }

    // ---- What the screens are given ----

    private val firstEntry = TrashEntry(
        id = 11L,
        originalPath = "/storage/emulated/0/Documents/report.pdf",
        trashPath = "/storage/emulated/0/Documents/.cleansweep-trashed-1-report.pdf",
        sizeBytes = 1_200_000L,
        deletedAt = 1_795_000_000_000L,
        expiresAt = 1_797_160_000_000L,
    )

    private val secondEntry = TrashEntry(
        id = 22L,
        originalPath = "/storage/emulated/0/Download/setup.log",
        trashPath = "/storage/emulated/0/Download/.cleansweep-trashed-2-setup.log",
        sizeBytes = 3_000L,
        deletedAt = 1_793_432_000_000L,
        expiresAt = 1_795_592_000_000L,
    )

    private val trashState = TrashUiState(
        rows = listOf(
            TrashRow(firstEntry, name = "report.pdf", originalFolder = "/storage/emulated/0/Documents", daysLeft = 25),
            TrashRow(secondEntry, name = "setup.log", originalFolder = "/storage/emulated/0/Download", daysLeft = 5),
        ),
        totalBytes = 1_203_000L,
    )

    private val settingsState = SettingsUiState(
        loading = false,
        preferences = UserPreferences(),
        rules = listOf(
            WhitelistRule(id = 7L, path = "/storage/emulated/0/Documents/Tax", type = WhitelistRuleType.FOLDER, createdAt = 1L),
        ),
    )
}
