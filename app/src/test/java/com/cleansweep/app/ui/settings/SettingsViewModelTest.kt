package com.cleansweep.app.ui.settings

import com.cleansweep.app.domain.model.ThemeMode
import com.cleansweep.app.domain.model.UserPreferences
import com.cleansweep.app.domain.model.WhitelistRuleType
import com.cleansweep.app.domain.scanner.FakeSettingsRepository
import com.cleansweep.app.domain.scanner.FolderLister
import com.cleansweep.app.domain.scanner.StorageRootsProvider
import com.cleansweep.app.domain.scanner.walker.FileMetadata
import com.cleansweep.app.domain.scanner.walker.FileMetadataReader
import com.cleansweep.app.domain.scanner.whitelist.WhitelistManager
import com.cleansweep.app.domain.util.StoragePaths
import com.cleansweep.app.ui.FakeFileIndexRepository
import com.cleansweep.app.ui.FakeSuggestionRepository
import com.cleansweep.app.ui.FakeWhitelistRepository
import com.cleansweep.app.ui.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The Settings screen: every control reaches the settings, and the folder picker ends in an exclusion or a reason. */
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    /** A test that waits for a state that never comes fails after a minute instead of hanging the whole run. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val settings = FakeSettingsRepository()
    private val index = FakeFileIndexRepository()
    private val suggestions = FakeSuggestionRepository(index)
    private val rules = FakeWhitelistRepository(index, suggestions)

    /** The folders the picker finds, by folder. */
    private val folders = HashMap<String, List<String>>()

    /** The storages, as the device reports them. */
    private var storages = listOf("/storage/emulated/0")

    /** Whether the whitelist manager finds anything at a path; off when a folder went missing. */
    private var folderIsThere: Boolean = true

    private val whitelist = WhitelistManager(
        whitelist = rules,
        roots = StorageRootsProvider { storages },
        metadata = FileMetadataReader { path ->
            if (!folderIsThere) {
                null
            } else {
                FileMetadata(isDirectory = true, isRegularFile = false, isSymbolicLink = false, sizeBytes = 0L, lastModified = 1L, createdAt = null)
            }
        },
    )

    private fun makeViewModel() = SettingsViewModel(
        settings = settings,
        whitelist = whitelist,
        roots = StorageRootsProvider { storages },
        folderLister = FolderLister { path -> folders[path].orEmpty() },
        ioDispatcher = mainDispatcher.dispatcher,
    )

    @Before
    fun storage() {
        folders["/storage/emulated/0"] = listOf("/storage/emulated/0/Documents", "/storage/emulated/0/Download")
        folders["/storage/emulated/0/Documents"] = listOf("/storage/emulated/0/Documents/Tax")
        folders["/storage/emulated/0/Documents/Tax"] = emptyList()
        folderIsThere = true
    }

    private suspend fun StateFlow<SettingsUiState>.await(
        predicate: (SettingsUiState) -> Boolean,
    ): SettingsUiState = withTimeout(10_000L) {
        while (!predicate(value)) {
            mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
            delay(1)
        }
        value
    }

    private suspend fun awaitUntil(
        condition: () -> Boolean,
    ) {
        withTimeout(10_000L) {
            while (!condition()) {
                mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
                delay(1)
            }
        }
    }

    private fun withEvents(
        block: suspend CoroutineScope.(events: MutableList<SettingsEvent>, viewModel: SettingsViewModel) -> Unit,
    ): Unit = runBlocking {
        val events = ArrayList<SettingsEvent>()
        val viewModel = makeViewModel()
        val watching = launch { viewModel.uiState.collect { } }
        val collector = launch { viewModel.eventFlow.collect { events += it } }
        try {
            mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
            block(events, viewModel)
        } finally {
            collector.cancelAndJoin()
            watching.cancelAndJoin()
        }
    }

    @Test
    fun `every scan setting on the screen reaches the stored settings`() = withEvents { _, viewModel ->
        viewModel.uiState.await { !it.loading }

        viewModel.setMinFileSize(1L * 1024 * 1024)
        viewModel.setLargeFileThreshold(200L * 1024 * 1024)
        viewModel.setLargeFileStaleDays(180)
        viewModel.setTheme(ThemeMode.DARK)
        viewModel.setBackgroundIndexing(true)

        val stored = runBlocking { settings.preferences.first() }
        assertEquals(1L * 1024 * 1024, stored.minFileSizeBytes)
        assertEquals(200L * 1024 * 1024, stored.largeFileThresholdBytes)
        assertEquals(180, stored.largeFileStaleDays)
        assertEquals(ThemeMode.DARK, stored.themeMode)
        assertTrue(stored.backgroundIndexing)
    }

    @Test
    fun `the exclusions are the rules, newest first`() = withEvents { _, viewModel ->
        val older = rules.addRule("/storage/emulated/0/Documents", WhitelistRuleType.FOLDER)
        val newer = rules.addRule("/storage/emulated/0/Download", WhitelistRuleType.FOLDER)

        val state = viewModel.uiState.await { it.rules.size == 2 }

        assertEquals(listOf(newer, older), state.rules)
    }

    @Test
    fun `ending an exclusion drops the rule and says so`() = withEvents { events, viewModel ->
        val rule = rules.addRule("/storage/emulated/0/Documents", WhitelistRuleType.FOLDER)
        viewModel.uiState.await { it.rules.size == 1 }

        viewModel.removeRule(rule)
        awaitUntil { events.size == 1 }

        assertEquals(SettingsEvent.RuleRemoved(rule), events.single())
        assertTrue(viewModel.uiState.await { it.rules.isEmpty() }.rules.isEmpty())
    }

    @Test
    fun `the picker starts at the storages, where nothing can be excluded`() = withEvents { events, viewModel ->
        viewModel.openPicker()

        val top = viewModel.uiState.await { it.picker?.loading == false }
        assertNull(top.picker?.path)
        assertEquals(storages, top.picker?.folders)
        assertFalse("A whole storage cannot be excluded", top.picker?.canExclude == true)

        viewModel.excludeCurrentFolder()
        mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Nothing happened", 0, events.size)
    }

    @Test
    fun `the picker walks from a storage down to a folder and back up`() = withEvents { _, viewModel ->
        viewModel.openPicker()
        viewModel.uiState.await { it.picker?.loading == false }

        viewModel.openFolder("/storage/emulated/0")
        val onStorage = viewModel.uiState.await { it.picker?.path == "/storage/emulated/0" && it.picker.loading == false }
        assertEquals(listOf("/storage/emulated/0/Documents", "/storage/emulated/0/Download"), onStorage.picker?.folders)
        assertFalse("The storage itself cannot be excluded", onStorage.picker?.canExclude == true)

        viewModel.openFolder("/storage/emulated/0/Documents")
        viewModel.uiState.await { it.picker?.path == "/storage/emulated/0/Documents" && it.picker.loading == false }

        viewModel.pickerUp()

        assertEquals("/storage/emulated/0", viewModel.uiState.await { it.picker?.loading == false }.picker?.path)
    }

    @Test
    fun `excluding the folder the picker is on adds the rule and closes the picker`() = withEvents { events, viewModel ->
        viewModel.openPicker()
        viewModel.uiState.await { it.picker?.loading == false }
        viewModel.openFolder("/storage/emulated/0/Documents")
        viewModel.uiState.await { it.picker?.loading == false }

        viewModel.excludeCurrentFolder()
        awaitUntil { events.size == 1 }
        val after = viewModel.uiState.await { it.picker == null && it.rules.size == 1 }

        assertEquals(SettingsEvent.Excluded("/storage/emulated/0/Documents"), events.single())
        assertEquals(WhitelistRuleType.FOLDER, after.rules.single().type)
        assertEquals("Stored in canonical form, as the real repository stores it", StoragePaths.canonicalize("/storage/emulated/0/Documents"), after.rules.single().path)
    }

    @Test
    fun `a folder that is excluded already is said to be, and nothing is added`() = withEvents { events, viewModel ->
        rules.addRule("/storage/emulated/0/Documents", WhitelistRuleType.FOLDER)
        viewModel.uiState.await { it.rules.size == 1 }
        viewModel.openPicker()
        viewModel.uiState.await { it.picker?.loading == false }
        viewModel.openFolder("/storage/emulated/0/Documents/Tax")
        viewModel.uiState.await { it.picker?.path == "/storage/emulated/0/Documents/Tax" && it.picker.loading == false }

        viewModel.excludeCurrentFolder()
        awaitUntil { events.size == 1 }

        assertEquals(SettingsEvent.AlreadyExcluded("/storage/emulated/0/Documents/Tax"), events.single())
        assertEquals("No second rule", 1, viewModel.uiState.value.rules.size)
    }

    @Test
    fun `a folder that is no longer there is said to be`() = withEvents { events, viewModel ->
        viewModel.openPicker()
        viewModel.uiState.await { it.picker?.loading == false }
        viewModel.openFolder("/storage/emulated/0/Download")
        // Gone from storage between the listing and the tap.
        folderIsThere = false
        viewModel.uiState.await { it.picker?.loading == false }

        viewModel.excludeCurrentFolder()
        awaitUntil { events.size == 1 }

        assertEquals(SettingsEvent.FolderGone, events.single())
        assertTrue(viewModel.uiState.value.rules.isEmpty())
    }

    @Test
    fun `closing the picker changes nothing`() = withEvents { _, viewModel ->
        viewModel.openPicker()
        viewModel.uiState.await { it.picker?.loading == false }

        viewModel.closePicker()

        assertNull(viewModel.uiState.await { it.picker == null }.picker)
        assertTrue(viewModel.uiState.value.rules.isEmpty())
    }

    @Test
    fun `a folder the picker cannot read is shown as one without folders`() = withEvents { _, viewModel ->
        viewModel.openFolder("/storage/emulated/0/Nowhere")

        val state = viewModel.uiState.await { it.picker?.loading == false }

        assertTrue(state.picker?.folders.orEmpty().isEmpty())
    }

    @Test
    fun `the steps offered for the numbers a scan depends on`() {
        assertEquals(listOf(0L, 100_000L, 1_000_000L, 10_000_000L), ScanSettingsSteps.MIN_FILE_SIZE_BYTES)

        val thresholds = ScanSettingsSteps.LARGE_FILE_THRESHOLDS
        assertEquals("The PRD's 50 MB is the first step", "50 MB", thresholds.first().label)
        assertEquals("The most the settings keep is the last", 10L * 1024 * 1024 * 1024, thresholds.last().bytes)
        assertEquals(listOf("50 MB", "100 MB", "200 MB", "500 MB", "1 GB", "2 GB", "5 GB", "10 GB"), thresholds.map { it.label })
        assertTrue("Each step is larger than the one before", thresholds.zipWithNext().all { (smaller, larger) -> smaller.bytes < larger.bytes })

        assertEquals(listOf(30, 60, 90, 180, 365), ScanSettingsSteps.LARGE_FILE_STALE_DAYS)

        // A value from somewhere else lands on the step nearest to it.
        assertEquals(3, ScanSettingsSteps.nearestIndex(thresholds, 400L * 1024 * 1024) { it.bytes })
        assertEquals(2, ScanSettingsSteps.nearestIndex(ScanSettingsSteps.LARGE_FILE_STALE_DAYS, 100L) { it.toLong() })
        assertEquals(0, ScanSettingsSteps.nearestIndex(ScanSettingsSteps.LARGE_FILE_STALE_DAYS, 0L) { it.toLong() })
    }
}
