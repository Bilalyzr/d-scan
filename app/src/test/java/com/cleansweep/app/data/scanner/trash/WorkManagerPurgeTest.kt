package com.cleansweep.app.data.scanner.trash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.cleansweep.app.domain.model.TrashEntry
import com.cleansweep.app.domain.scanner.DAY_MILLIS
import com.cleansweep.app.domain.scanner.ScanHarness
import com.cleansweep.app.domain.scanner.trash.TrashHarness
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** The recurring purge as WorkManager plans it. */
@RunWith(AndroidJUnit4::class)
class WorkManagerPurgeSchedulerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork()
    }

    private fun planned(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerPurgeScheduler.UNIQUE_WORK_NAME).get()

    private fun awaitState(expected: WorkInfo.State) = runBlocking {
        withTimeout(10_000) {
            while (planned().map { it.state } != listOf(expected)) {
                delay(50)
            }
        }
    }

    @Test
    fun `asking for the purge again keeps the one that is planned`() {
        val scheduler = WorkManagerPurgeScheduler(context)

        scheduler.schedule()
        scheduler.schedule()

        awaitState(WorkInfo.State.ENQUEUED)
        assertEquals(1, planned().size)
    }

    @Test
    fun `cancelling ends the planned purge`() {
        val scheduler = WorkManagerPurgeScheduler(context)
        scheduler.schedule()
        awaitState(WorkInfo.State.ENQUEUED)

        scheduler.cancel()

        awaitState(WorkInfo.State.CANCELLED)
    }
}

/** The purge worker on real files, run the way WorkManager would run it. */
@RunWith(AndroidJUnit4::class)
class TrashPurgeWorkerTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var scans: ScanHarness
    private lateinit var trash: TrashHarness

    @Before
    fun setUp() {
        scans = ScanHarness(temporaryFolder.root)
        trash = TrashHarness(scans)
    }

    @After
    fun tearDown() {
        scans.close()
    }

    private fun worker(attempt: Int = 0): TrashPurgeWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = TrashPurgeWorker(appContext, workerParameters, trash.manager)
        }
        return TestListenableWorkerBuilder<TrashPurgeWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(factory)
            .setRunAttemptCount(attempt)
            .build()
    }

    /** Trashes a file the scan suggested as temporary junk, and returns its manifest entry. */
    private suspend fun trashOne(): TrashEntry {
        scans.write("Download/notes.log", "the notes".toByteArray())
        scans.scan()
        val file = requireNotNull(scans.indexed(scans.at("Download/notes.log")))
        return trash.manager.trash(listOf(file.id)).trashed.single()
    }

    @Test
    fun `the worker purges what has expired and reports success`() = runBlocking {
        val entry = trashOne()

        assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        assertTrue("nothing is purged a day early", File(entry.trashPath).exists())

        scans.clock.advanceBy(31 * DAY_MILLIS)

        assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        assertFalse(File(entry.trashPath).exists())
        assertTrue(trash.manifest.observeEntries().first().isEmpty())
    }

    @Test
    fun `a failed run is retried, and given up after a few attempts`() = runBlocking {
        trashOne()
        scans.clock.advanceBy(31 * DAY_MILLIS)
        trash.storage.storageFailure = IllegalStateException("volume unreadable")

        assertTrue(worker(attempt = 0).doWork() is ListenableWorker.Result.Retry)
        assertTrue(worker(attempt = 2).doWork() is ListenableWorker.Result.Retry)
        assertTrue(worker(attempt = 3).doWork() is ListenableWorker.Result.Failure)
    }
}
