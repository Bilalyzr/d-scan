package com.cleansweep.app.domain.scanner.trash

import com.cleansweep.app.data.scanner.NioFileMetadataReader
import com.cleansweep.app.data.scanner.trash.NioTrashStorage
import kotlinx.coroutines.Dispatchers
import com.cleansweep.app.data.repository.TrashRepositoryImpl
import com.cleansweep.app.domain.repository.TrashRepository
import com.cleansweep.app.domain.scanner.ScanHarness
import java.util.concurrent.CopyOnWriteArrayList

/** [PurgeScheduler] that only remembers what it was asked to do. */
internal class RecordingPurgeScheduler : PurgeScheduler {
    var scheduled: Int = 0
        private set
    var cancelled: Int = 0
        private set

    /** When set, the next [schedule] fails with it, the way WorkManager can when it is not available. */
    var failure: Exception? = null

    override fun schedule() {
        failure?.let { failure = null; throw it }
        scheduled++
    }

    override fun cancel() {
        cancelled++
    }
}

/** [MediaScanner] that remembers every call, so that tests can see which paths were rescanned. */
internal class RecordingMediaScanner : MediaScanner {
    val calls = CopyOnWriteArrayList<List<String>>()

    /** The paths of the most recent call. */
    val last: List<String>
        get() = calls.lastOrNull().orEmpty()

    override fun scan(paths: Collection<String>) {
        calls += paths.toList()
    }
}

/**
 * A real [NioTrashStorage] that lets a test make storage read-only for chosen paths, or break outright.
 */
internal class FaultInjectingTrashStorage(
    private val real: TrashStorage = NioTrashStorage(NioFileMetadataReader()),
) : TrashStorage by real {
    private val readOnly = CopyOnWriteArrayList<String>()

    /** When set, every lookup of an entry fails with it. */
    var storageFailure: RuntimeException? = null

    fun makeReadOnly(vararg paths: String) {
        readOnly += paths
    }

    override fun stamp(path: String): TrashStamp? = storageFailure?.let { throw it } ?: real.stamp(path)

    override fun move(
        from: String,
        to: String,
    ): MoveResult = if (readOnly.any { from.startsWith(it) || to.startsWith(it) }) {
        MoveResult.ReadOnly
    } else {
        real.move(from, to)
    }

    override fun deleteFile(path: String): DeleteResult =
        if (readOnly.any { path.startsWith(it) }) DeleteResult.ReadOnly else real.deleteFile(path)

    override fun deleteEmptyDirectory(path: String): DeleteResult =
        if (readOnly.any { path.startsWith(it) }) DeleteResult.ReadOnly else real.deleteEmptyDirectory(path)
}

/** Everything a test of Safe Trash needs, on real files and a real in-memory database. */
internal class TrashHarness(
    private val scans: ScanHarness,
) {
    val storage = FaultInjectingTrashStorage()
    val scheduler = RecordingPurgeScheduler()
    val mediaScans = RecordingMediaScanner()
    val manifest: TrashRepository = TrashRepositoryImpl(scans.database, scans.database.trashManifestDao())
    val manager = TrashManager(
        trash = manifest,
        fileIndex = scans.fileIndex,
        suggestions = scans.suggestions,
        whitelist = scans.whitelist,
        storage = storage,
        mediaScanner = mediaScans,
        purgeScheduler = scheduler,
        clock = scans.clock,
        ioDispatcher = Dispatchers.IO,
    )
}
