package com.cleansweep.app.domain.scanner

import com.cleansweep.app.data.repository.FileIndexRepositoryImpl
import com.cleansweep.app.data.repository.SuggestionRepositoryImpl
import com.cleansweep.app.data.repository.WhitelistRepositoryImpl
import com.cleansweep.app.data.scanner.NioFileMetadataReader
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.ThemeMode
import com.cleansweep.app.domain.model.UserPreferences
import com.cleansweep.app.domain.repository.SettingsRepository
import com.cleansweep.app.domain.scanner.duplicates.DuplicatePipeline
import com.cleansweep.app.domain.scanner.hashing.DeviceLoad
import com.cleansweep.app.domain.scanner.hashing.DeviceStateReader
import com.cleansweep.app.domain.scanner.hashing.FileHasher
import com.cleansweep.app.domain.scanner.hashing.HashOutcome
import com.cleansweep.app.domain.scanner.hashing.StreamingFileHasher
import com.cleansweep.app.domain.scanner.hashing.ThermalStatus
import com.cleansweep.app.domain.scanner.junk.InstalledApkOracle
import com.cleansweep.app.domain.scanner.walker.FileWalker
import com.cleansweep.app.domain.util.StoragePaths
import com.cleansweep.app.inMemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

internal const val SCAN_NOW: Long = 1_800_000_000_000L
internal const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

/** A clock that moves forward by a millisecond on every reading, so that no two scans share a timestamp. */
internal class SteppingClock(
    private var now: Long = SCAN_NOW,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(millis())

    override fun millis(): Long = now++

    /** Moves the clock forward, the way days go by while nothing is read. */
    fun advanceBy(millis: Long) {
        now += millis
    }
}

/** [FileHasher] that records which files it was asked about, and can be held up or made to act. */
internal class CountingHasher(
    private val real: FileHasher = StreamingFileHasher(Dispatchers.IO, Dispatchers.Default),
) : FileHasher {
    val partiallyHashed = CopyOnWriteArrayList<String>()
    val fullyHashed = CopyOnWriteArrayList<String>()

    /** Runs before every hash. Tests use it to delete a file under the scanner's feet or to hold the scan. */
    var beforeHashing: suspend (File) -> Unit = {}

    // Canonical, because `File.getPath()` turns the forward slashes of an indexed path into backslashes on Windows.
    override suspend fun partialHash(file: File): HashOutcome {
        beforeHashing(file)
        partiallyHashed += StoragePaths.canonicalize(file.path)
        return real.partialHash(file)
    }

    override suspend fun fullHash(file: File): HashOutcome {
        beforeHashing(file)
        fullyHashed += StoragePaths.canonicalize(file.path)
        return real.fullHash(file)
    }
}

/** [InstalledApkOracle] that knows what the test tells it, and remembers what it was asked. */
internal class FakeInstalledApps(
    supersededPaths: Set<String> = emptySet(),
) : InstalledApkOracle {
    private val superseded = MutableStateFlow(supersededPaths)
    val askedAbout = CopyOnWriteArrayList<String>()

    fun install(path: String) {
        superseded.value = superseded.value + path
    }

    override suspend fun isSupersededByInstalledApp(apkPath: String): Boolean {
        askedAbout += apkPath
        return apkPath in superseded.value
    }
}

/** [SettingsRepository] in memory: everything the scan needs, without a file on disk. */
internal class FakeSettingsRepository(
    initial: UserPreferences = UserPreferences(),
) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    /** When set, [current] throws it, the way a broken settings file would. */
    var failure: Exception? = null

    override val preferences: Flow<UserPreferences> = state

    override suspend fun current(): UserPreferences = failure?.let { throw it } ?: state.value

    fun update(transform: (UserPreferences) -> UserPreferences) {
        state.value = transform(state.value)
    }

    override suspend fun setMinFileSizeBytes(bytes: Long) = update { it.copy(minFileSizeBytes = bytes) }

    override suspend fun setLargeFileThresholdBytes(bytes: Long) = update { it.copy(largeFileThresholdBytes = bytes) }

    override suspend fun setLargeFileStaleDays(days: Int) = update { it.copy(largeFileStaleDays = days) }

    override suspend fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    override suspend fun setBackgroundIndexing(enabled: Boolean) = update { it.copy(backgroundIndexing = enabled) }
}

/**
 * A complete scan engine wired to real files in a temporary folder and a real in-memory database, with
 * platform lookups replaced by fakes. Only the parts that need Android are faked: everything from the walker
 * to the suggestions runs for real.
 */
internal class ScanHarness(
    folder: File,
) {
    val root: String = StoragePaths.canonicalize(folder.absolutePath)
    val clock = SteppingClock()
    val database = inMemoryDatabase()
    val fileIndex = FileIndexRepositoryImpl(database, database.fileIndexDao(), database.suggestionItemDao())
    val whitelist = WhitelistRepositoryImpl(database, database.whitelistRuleDao(), database.fileIndexDao(), clock)
    val suggestions = SuggestionRepositoryImpl(database, database.suggestionItemDao())
    val hasher = CountingHasher()
    val settings = FakeSettingsRepository()
    val installedApks = FakeInstalledApps()

    /** The volumes the scan is told to look at. */
    var roots: List<String> = listOf(root)

    /** What the device looks like when the worker count is chosen. */
    var deviceLoad: DeviceLoad = DeviceLoad(4, 100, ThermalStatus.NONE, false)

    val runner: ScanRunner = ScanRunner(
        rootsProvider = StorageRootsProvider { roots },
        walker = FileWalker(NioFileMetadataReader()),
        fileIndex = fileIndex,
        whitelist = whitelist,
        suggestionRepository = suggestions,
        pipeline = DuplicatePipeline(hasher, fileIndex),
        guard = SuggestionGuard(),
        installedApks = installedApks,
        settings = settings,
        deviceState = DeviceStateReader { deviceLoad },
        clock = clock,
        ioDispatcher = Dispatchers.IO,
    )

    fun close() = database.close()

    fun at(relative: String): String = "$root/$relative"

    fun folder(relative: String) {
        File(root, relative).mkdirs()
    }

    /** Writes a file whose modification time lies [modifiedDaysAgo] in the past. */
    fun write(
        relative: String,
        content: ByteArray,
        modifiedDaysAgo: Long = 200,
    ): File = File(root, relative).apply {
        parentFile?.mkdirs()
        writeBytes(content)
        setLastModified(SCAN_NOW - modifiedDaysAgo * DAY_MILLIS)
    }

    fun write(
        relative: String,
        sizeBytes: Int,
        seed: Int,
        modifiedDaysAgo: Long = 200,
    ): File = write(relative, deterministicBytes(sizeBytes, seed), modifiedDaysAgo)

    /** Moves a folder's own modification time into the past; a folder that was just made counts as recent. */
    fun ageFolder(
        relative: String,
        modifiedDaysAgo: Long = 200,
    ) {
        File(root, relative).setLastModified(SCAN_NOW - modifiedDaysAgo * DAY_MILLIS)
    }

    suspend fun scan(request: ScanRequest = ScanRequest()): ScanSummary =
        runner.run(request, PauseGate(), ScanProgressListener { })

    suspend fun storedSuggestions(): List<Suggestion> = suggestions.observeSuggestions(includeDismissed = true).first()

    suspend fun indexed(path: String): IndexedFile? = fileIndex.getByPath(path)

    companion object {
        /** Bytes that depend only on their arguments, so that two calls with the same seed are equal. */
        fun deterministicBytes(
            sizeBytes: Int,
            seed: Int,
        ): ByteArray = ByteArray(sizeBytes).also { Random(seed).nextBytes(it) }
    }
}

internal fun List<Suggestion>.paths(): List<String> = map { it.file.path }
