package com.cleansweep.app.domain.scanner.trash

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.data.local.db.CleanSweepDatabase
import com.cleansweep.app.data.repository.FileIndexRepositoryImpl
import com.cleansweep.app.data.repository.SuggestionRepositoryImpl
import com.cleansweep.app.data.repository.TrashRepositoryImpl
import com.cleansweep.app.data.repository.WhitelistRepositoryImpl
import com.cleansweep.app.data.scanner.AndroidHardLinkReader
import com.cleansweep.app.data.scanner.AndroidInstalledApkOracle
import com.cleansweep.app.data.scanner.AndroidMimeTypeResolver
import com.cleansweep.app.data.scanner.NioFileMetadataReader
import com.cleansweep.app.data.scanner.trash.AndroidMediaScanner
import com.cleansweep.app.data.scanner.trash.NioTrashStorage
import com.cleansweep.app.domain.model.ThemeMode
import com.cleansweep.app.domain.model.UserPreferences
import com.cleansweep.app.domain.repository.SettingsRepository
import com.cleansweep.app.domain.scanner.PauseGate
import com.cleansweep.app.domain.scanner.ScanProgressListener
import com.cleansweep.app.domain.scanner.ScanRequest
import com.cleansweep.app.domain.scanner.ScanRunner
import com.cleansweep.app.domain.scanner.StorageRootsProvider
import com.cleansweep.app.domain.scanner.SuggestionGuard
import com.cleansweep.app.domain.scanner.duplicates.DuplicatePipeline
import com.cleansweep.app.domain.scanner.hashing.DeviceLoad
import com.cleansweep.app.domain.scanner.hashing.DeviceStateReader
import com.cleansweep.app.domain.scanner.hashing.StreamingFileHasher
import com.cleansweep.app.domain.scanner.hashing.ThermalStatus
import com.cleansweep.app.domain.scanner.walker.FileWalker
import com.cleansweep.app.domain.scanner.walker.ScanExclusions
import com.cleansweep.app.domain.util.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock

/**
 * Safe Trash on a real device, on shared storage, through the real adapters.
 *
 * Trashing only renames an entry in its own folder. That is what this test is about: that the rename works on
 * Android's shared storage, that the hidden entry is ignored by a later scan, and that it comes back on restore.
 * Needs a device or emulator: `./gradlew connectedDebugAndroidTest`. It has never been run.
 */
@RunWith(AndroidJUnit4::class)
class TrashManagerInstrumentedTest {

    private lateinit var root: File
    private lateinit var canonicalRoot: String
    private lateinit var database: CleanSweepDatabase
    private lateinit var fileIndex: FileIndexRepositoryImpl
    private lateinit var trashManager: TrashManager
    private lateinit var runner: ScanRunner

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.getExternalFilesDir(null), "trash-test-${System.nanoTime()}").apply { mkdirs() }
        canonicalRoot = StoragePaths.canonicalize(root.absolutePath)
        database = Room.inMemoryDatabaseBuilder(context, CleanSweepDatabase::class.java).build()
        fileIndex = FileIndexRepositoryImpl(database, database.fileIndexDao(), database.suggestionItemDao())
        val suggestions = SuggestionRepositoryImpl(database, database.suggestionItemDao())
        val whitelist = WhitelistRepositoryImpl(
            database,
            database.whitelistRuleDao(),
            database.fileIndexDao(),
            Clock.systemDefaultZone(),
        )
        val manifest = TrashRepositoryImpl(database, database.trashManifestDao())
        trashManager = TrashManager(
            trash = manifest,
            fileIndex = fileIndex,
            suggestions = suggestions,
            whitelist = whitelist,
            storage = NioTrashStorage(),
            mediaScanner = AndroidMediaScanner(context),
            purgeScheduler = object : PurgeScheduler {
                override fun schedule() = Unit
                override fun cancel() = Unit
            },
            clock = Clock.systemDefaultZone(),
            ioDispatcher = Dispatchers.IO,
        )
        runner = ScanRunner(
            rootsProvider = StorageRootsProvider { listOf(canonicalRoot) },
            walker = FileWalker(NioFileMetadataReader(), AndroidMimeTypeResolver(), ScanExclusions()),
            fileIndex = fileIndex,
            whitelist = whitelist,
            suggestionRepository = suggestions,
            pipeline = DuplicatePipeline(StreamingFileHasher(Dispatchers.IO, Dispatchers.Default), fileIndex),
            guard = SuggestionGuard(AndroidHardLinkReader()),
            installedApks = AndroidInstalledApkOracle(context, Dispatchers.IO),
            settings = DefaultSettings(),
            deviceState = DeviceStateReader { DeviceLoad(4, 100, ThermalStatus.NONE, isPowerSaveMode = false) },
            clock = Clock.systemDefaultZone(),
            ioDispatcher = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun trashingRenamesInPlaceAndRestoringPutsTheFileBack() = runBlocking {
        val file = File(root, "Download/notes.log").apply {
            parentFile!!.mkdirs()
            writeText("the notes")
            setLastModified(System.currentTimeMillis() - 200L * 24 * 60 * 60 * 1000)
        }
        val indexedPath = "$canonicalRoot/Download/notes.log"

        scan()
        val suggested = requireNotNull(fileIndex.getByPath(indexedPath))

        val trashed = trashManager.trash(listOf(suggested.id))

        assertTrue(trashed.refused.toString(), trashed.refused.isEmpty())
        val entry = trashed.trashed.single()
        assertEquals("$canonicalRoot/Download", StoragePaths.parentOf(entry.trashPath))
        assertTrue(TrashNaming.isTrashedName(StoragePaths.nameOf(entry.trashPath)))
        assertFalse("the file is no longer where it was", file.exists())
        assertEquals("the notes", File(entry.trashPath).readText())
        assertNull(fileIndex.getByPath(indexedPath))

        scan()

        assertNull("a later scan does not index the trashed entry", fileIndex.getByPath(entry.trashPath))

        val restored = trashManager.restore(listOf(entry.id))

        assertEquals(listOf(indexedPath), restored.restoredPaths)
        assertTrue(restored.failures.isEmpty())
        assertEquals("the notes", file.readText())
        assertFalse(File(entry.trashPath).exists())
    }

    @Test
    fun emptyingTheTrashDeletesTheEntriesForGood() = runBlocking {
        val file = File(root, "Download/notes.log").apply {
            parentFile!!.mkdirs()
            writeText("the notes")
            setLastModified(System.currentTimeMillis() - 200L * 24 * 60 * 60 * 1000)
        }
        scan()
        val suggested = requireNotNull(fileIndex.getByPath("$canonicalRoot/Download/notes.log"))
        val entry = trashManager.trash(listOf(suggested.id)).trashed.single()

        val purged = trashManager.emptyTrashPermanently()

        assertEquals(1, purged.deletedEntries)
        assertFalse(File(entry.trashPath).exists())
        assertFalse(file.exists())
    }

    private suspend fun scan() {
        runner.run(ScanRequest(roots = listOf(canonicalRoot)), PauseGate(), ScanProgressListener { })
    }

    /** Settings as the PRD ships them, without a file behind them. */
    private class DefaultSettings : SettingsRepository {
        private val state = MutableStateFlow(UserPreferences())

        override val preferences: Flow<UserPreferences> = state

        override suspend fun current(): UserPreferences = state.value

        override suspend fun setMinFileSizeBytes(bytes: Long) = Unit

        override suspend fun setLargeFileThresholdBytes(bytes: Long) = Unit

        override suspend fun setLargeFileStaleDays(days: Int) = Unit

        override suspend fun setThemeMode(mode: ThemeMode) = Unit
    }
}
