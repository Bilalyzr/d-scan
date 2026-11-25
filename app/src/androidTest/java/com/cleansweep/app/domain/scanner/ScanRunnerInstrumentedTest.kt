package com.cleansweep.app.domain.scanner

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.data.local.db.CleanSweepDatabase
import com.cleansweep.app.data.repository.FileIndexRepositoryImpl
import com.cleansweep.app.data.repository.SuggestionRepositoryImpl
import com.cleansweep.app.data.repository.WhitelistRepositoryImpl
import com.cleansweep.app.data.scanner.AndroidDeviceStateReader
import com.cleansweep.app.data.scanner.AndroidHardLinkReader
import com.cleansweep.app.data.scanner.AndroidInstalledApkOracle
import com.cleansweep.app.data.scanner.AndroidMimeTypeResolver
import com.cleansweep.app.data.scanner.NioFileMetadataReader
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.ThemeMode
import com.cleansweep.app.domain.model.UserPreferences
import com.cleansweep.app.domain.repository.SettingsRepository
import com.cleansweep.app.domain.scanner.duplicates.DuplicatePipeline
import com.cleansweep.app.domain.scanner.hashing.StreamingFileHasher
import com.cleansweep.app.domain.scanner.walker.FileWalker
import com.cleansweep.app.domain.scanner.walker.ScanExclusions
import com.cleansweep.app.domain.util.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock

/**
 * A whole scan on a real device, through the real platform adapters: `java.nio` attributes on shared storage,
 * the platform MIME table, link counts from `Os`, the thermal state, and Room on the device's own SQLite.
 *
 * The JVM tests run the same engine against Robolectric and the host file system; this is the one that proves
 * it on Android itself. Needs a device or emulator: `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ScanRunnerInstrumentedTest {

    private lateinit var root: File
    private lateinit var database: CleanSweepDatabase
    private lateinit var fileIndex: FileIndexRepositoryImpl
    private lateinit var suggestions: SuggestionRepositoryImpl
    private lateinit var runner: ScanRunner
    private lateinit var canonicalRoot: String

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.getExternalFilesDir(null), "scan-test-${System.nanoTime()}").apply { mkdirs() }
        canonicalRoot = StoragePaths.canonicalize(root.absolutePath)
        database = Room.inMemoryDatabaseBuilder(context, CleanSweepDatabase::class.java).build()
        fileIndex = FileIndexRepositoryImpl(database, database.fileIndexDao(), database.suggestionItemDao())
        suggestions = SuggestionRepositoryImpl(database, database.suggestionItemDao())
        val whitelist = WhitelistRepositoryImpl(
            database,
            database.whitelistRuleDao(),
            database.fileIndexDao(),
            Clock.systemDefaultZone(),
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
            settings = FixedSettings(),
            deviceState = AndroidDeviceStateReader(context),
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
    fun aScanOnRealStorageFindsDuplicatesAndJunk() = runBlocking {
        val content = ByteArray(300_000) { index -> (index * 31).toByte() }
        val report = write("Documents/report.pdf", content)
        val copy = write("Download/report (1).pdf", content)
        val log = write("Download/notes.log", ByteArray(10))
        val empty = write("Download/empty.dat", ByteArray(0))
        val oldFolder = File(root, "Download/Old Stuff").apply { mkdirs() }
        val untouchedForMonths = System.currentTimeMillis() - 200L * 24 * 60 * 60 * 1000
        for (entry in listOf(report, copy, log, empty, oldFolder)) {
            assertTrue("could not age ${entry.name}", entry.setLastModified(untouchedForMonths))
        }

        val summary = runner.run(ScanRequest(roots = listOf(canonicalRoot)), PauseGate(), ScanProgressListener { })

        assertEquals(listOf(canonicalRoot), summary.rootsScanned)
        assertEquals(0, summary.errors)
        assertEquals(1, summary.duplicateGroups)
        assertEquals(300_000L, summary.duplicateBytes)
        assertEquals(1, summary.emptyFolders)

        val indexed = requireNotNull(fileIndex.getByPath("$canonicalRoot/Documents/report.pdf"))
        assertEquals("application/pdf", indexed.mimeType)
        assertTrue(requireNotNull(indexed.fullHash).startsWith("sha256:"))

        val byPath = suggestions.observeSuggestions(includeDismissed = true).first().associateBy { it.file.path }
        assertEquals(
            setOf("Download/report (1).pdf", "Download/notes.log", "Download/empty.dat", "Download/Old Stuff"),
            byPath.keys.map { it.removePrefix("$canonicalRoot/") }.toSet(),
        )
        val duplicate = byPath.getValue("$canonicalRoot/Download/report (1).pdf")
        assertEquals(SuggestionCategory.EXACT_DUPLICATE, duplicate.category)
        assertEquals(ReasonCode.EXACT_COPY, duplicate.primaryReason)
        assertEquals(ReasonCode.EMPTY_FOLDER, byPath.getValue("$canonicalRoot/Download/Old Stuff").primaryReason)
    }

    private fun write(
        relative: String,
        content: ByteArray,
    ): File = File(root, relative).apply {
        parentFile?.mkdirs()
        writeBytes(content)
    }

    /** Settings as the PRD ships them, without a file behind them. */
    private class FixedSettings : SettingsRepository {
        private val state = MutableStateFlow(UserPreferences())

        override val preferences: Flow<UserPreferences> = state

        override suspend fun current(): UserPreferences = state.value

        override suspend fun setMinFileSizeBytes(bytes: Long) = Unit

        override suspend fun setLargeFileThresholdBytes(bytes: Long) = Unit

        override suspend fun setLargeFileStaleDays(days: Int) = Unit

        override suspend fun setThemeMode(mode: ThemeMode) = Unit
    }
}
