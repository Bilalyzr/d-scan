package com.cleansweep.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.domain.model.CategoryTotal
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.domain.model.TierTotal
import com.cleansweep.app.domain.scanner.ScanProgress
import com.cleansweep.app.domain.scanner.ScanState
import com.cleansweep.app.domain.scanner.ScanSummary
import com.cleansweep.app.ui.common.AppIcons
import com.cleansweep.app.ui.home.HomeContent
import com.cleansweep.app.ui.home.HomeUiState
import com.cleansweep.app.ui.onboarding.AccessMethod
import com.cleansweep.app.ui.onboarding.OnboardingContent
import com.cleansweep.app.ui.onboarding.OnboardingStep
import com.cleansweep.app.ui.onboarding.OnboardingUiState
import com.cleansweep.app.ui.theme.CleanSweepTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the screens on the JVM and checks that what they say is on screen.
 *
 * Robolectric renders Compose with its native graphics mode, so a screen is laid out and drawn here the way it
 * would be on a device, short of the GPU. Each test also writes the screen to `app/build/screenshots/`, to look
 * at. What needs a device is not covered: the system permission dialogs, the video player, PDF rendering.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h760dp-xhdpi")
class ScreenRenderTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `onboarding explains first and asks only after the disclosure was acknowledged`() {
        set { Onboarding(OnboardingUiState(method = AccessMethod.ALL_FILES_SETTINGS)) }

        rule.onNodeWithText("100% on-device").assertIsDisplayed()
        rule.onNodeWithText("Storage access is needed").assertIsDisplayed()
        rule.onNodeWithText("I understand, continue").assertIsDisplayed()
        rule.onNodeWithText("Allow storage access").assertDoesNotExist()
        rule.saveScreen("onboarding-explaining")
    }

    @Test
    fun `onboarding asks for access once the disclosure was acknowledged`() {
        set {
            Onboarding(OnboardingUiState(step = OnboardingStep.REQUESTING, method = AccessMethod.ALL_FILES_SETTINGS))
        }

        rule.onNodeWithText("Allow storage access").assertIsDisplayed()
        rule.onNodeWithText("Not now").assertIsDisplayed()
        rule.saveScreen("onboarding-requesting")
    }

    @Test
    fun `onboarding offers the app settings when the system will not ask again`() {
        set {
            Onboarding(
                OnboardingUiState(
                    step = OnboardingStep.DENIED,
                    method = AccessMethod.RUNTIME_PERMISSION,
                    cannotAskAgain = true,
                ),
            )
        }

        rule.onNodeWithText("Access is still off").assertIsDisplayed()
        rule.onNodeWithText("Try again").assertIsDisplayed()
        rule.onNodeWithText("Open app settings").assertIsDisplayed()
        rule.saveScreen("onboarding-denied")
    }

    @Test
    fun `dashboard after a scan shows the gauge, what could be freed, and the scan button`() {
        set { Home(scannedHome) }

        rule.saveScreen("home-scanned")
        rule.onNodeWithText("84 GB").assertIsDisplayed()
        rule.onNodeWithText("44 GB free").assertIsDisplayed()
        rule.onNodeWithText("used of 128 GB").assertIsDisplayed()
        rule.onNodeWithText("Space you could free").assertIsDisplayed()
        rule.onNodeWithText("Safe to clean").assertIsDisplayed()
        rule.onNodeWithText("2.4 GB in 14 items").assertIsDisplayed()
        rule.onNodeWithText("Review suggestions").assertIsDisplayed()
        rule.onNodeWithText("Scan device").assertIsDisplayed()
        rule.onNodeWithText("Scan finished").assertIsDisplayed()
    }

    @Test
    fun `dashboard before the first scan says so and still offers to scan`() {
        set { Home(HomeUiState()) }

        rule.onNodeWithText("Not scanned yet").assertIsDisplayed()
        rule.onNodeWithText("Scan your device to find out what can go.").assertIsDisplayed()
        rule.onNodeWithText("Scan your device to see what takes up space.").assertIsDisplayed()
        rule.onNodeWithText("Scan device").assertIsDisplayed()
        rule.saveScreen("home-fresh")
    }

    @Test
    fun `dashboard without storage access leads back to the permission`() {
        set { Home(HomeUiState(storageAccessGranted = false)) }

        rule.onNodeWithText("Storage access is off").assertIsDisplayed()
        rule.onNodeWithText("Allow storage access").assertIsDisplayed()
        rule.saveScreen("home-no-access")
    }

    @Test
    fun `dashboard while a scan runs offers the progress screen instead of a new scan`() {
        set { Home(HomeUiState(scan = isScanningState())) }

        rule.onNodeWithText("View progress").assertIsDisplayed()
        rule.onNodeWithText("Scan device").assertDoesNotExist()
        rule.saveScreen("home-scanning")
    }

    @Test
    fun `the dashboard in dark colours, in the brand palette`() {
        set(darkTheme = true, dynamicColor = false) { Home(scannedHome) }

        rule.onNodeWithText("Scan device").assertIsDisplayed()
        rule.saveScreen("home-dark")
    }

    @Test
    fun `the onboarding in dark colours, in the brand palette`() {
        set(darkTheme = true, dynamicColor = false) { Onboarding(OnboardingUiState(method = AccessMethod.ALL_FILES_SETTINGS)) }

        rule.onNodeWithText("I understand, continue").assertIsDisplayed()
        rule.saveScreen("onboarding-dark")
    }

    @Test
    fun `the dashboard in the brand palette, on Android 10 and 11`() {
        set(dynamicColor = false) { Home(scannedHome) }

        rule.onNodeWithText("Scan device").assertIsDisplayed()
        rule.saveScreen("home-brand-light")
    }

    @Test
    fun `the dashboard in dark colours, with the dynamic palette of Android 12`() {
        set(darkTheme = true) { Home(scannedHome) }

        rule.onNodeWithText("Scan device").assertIsDisplayed()
        rule.saveScreen("home-dark-dynamic")
    }

    @Test
    fun `the drawn icons are shown next to the standard ones`() {
        val drawn = listOf(
            "Pause" to AppIcons.Pause,
            "Stop" to AppIcons.Stop,
            "Restore" to AppIcons.Restore,
            "Keep" to AppIcons.Bookmark,
            "Preview" to AppIcons.Visibility,
            "Folder" to AppIcons.Folder,
            "FolderOpen" to AppIcons.FolderOpen,
            "File" to AppIcons.File,
            "Document" to AppIcons.Document,
            "Image" to AppIcons.Image,
            "Video" to AppIcons.Video,
            "Audio" to AppIcons.Audio,
            "Installer" to AppIcons.Installer,
            "Archive" to AppIcons.Archive,
            "Storage" to AppIcons.Storage,
            "Shield" to AppIcons.Shield,
            "Block" to AppIcons.Block,
            "Suggestions" to AppIcons.Suggestions,
            "SelectAll" to AppIcons.SelectAll,
            "ArrowUpward" to AppIcons.ArrowUpward,
            "OpenInNew" to AppIcons.OpenInNew,
            "Clock" to AppIcons.Clock,
        )
        val standard = listOf(
            "Home" to Icons.Filled.Home,
            "Settings" to Icons.Filled.Settings,
            "Delete" to Icons.Filled.Delete,
            "Lock" to Icons.Filled.Lock,
            "Check" to Icons.Filled.Check,
            "Close" to Icons.Filled.Close,
            "Star" to Icons.Filled.Star,
            "Refresh" to Icons.Filled.Refresh,
            "Warning" to Icons.Filled.Warning,
            "Info" to Icons.Filled.Info,
        )

        set {
            CleanSweepTheme(darkTheme = false) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                    (drawn + standard).chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            for ((name, icon) in row) {
                                Column(
                                    Modifier.padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(icon, contentDescription = null, Modifier.size(32.dp))
                                    Text(name, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        rule.saveScreen("icons")
    }

    /**
     * Shows [content] in the theme of the app. Robolectric's dynamic palette is the same in light and dark, so
     * a test of dark colours or of the brand palette turns the dynamic palette off.
     */
    private fun set(
        darkTheme: Boolean = false,
        dynamicColor: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        rule.setContent {
            CleanSweepTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                // The app's root paints the background behind every screen. The test stands in for it.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        rule.waitForIdle()
    }

    /** The onboarding screen in a state of its own. */
    @Composable
    private fun Onboarding(
        state: OnboardingUiState,
    ) {
        OnboardingContent(
            state = state,
            onDisclosureRead = {},
            onRequestAccess = {},
            onOpenAppSettings = {},
            onNotNow = {},
        )
    }

    /** The dashboard in a state of its own. */
    @Composable
    private fun Home(
        state: HomeUiState,
    ) {
        HomeContent(
            state = state,
            onRefreshStorage = {},
            onStartScan = {},
            onOpenScan = {},
            onOpenSuggestions = {},
            onOpenTrash = {},
            onAllowAccess = {},
        )
    }

    private fun isScanningState(): ScanState = ScanState.Running(ScanProgress(), isPaused = false)

    private companion object {
        val scannedHome = HomeUiState(
            hasStorageSizes = true,
            totalBytes = 128_000_000_000L,
            freeBytes = 44_000_000_000L,
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
        )
    }
}
