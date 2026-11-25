package com.cleansweep.app.data.scanner

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
import com.cleansweep.app.domain.scanner.FakeSettingsRepository
import com.cleansweep.app.domain.scanner.ScanHarness
import com.cleansweep.app.domain.scanner.ScanProgress
import com.cleansweep.app.domain.scanner.ScanState
import com.cleansweep.app.domain.scanner.StorageAccess
import com.cleansweep.app.ui.FakeScanControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** The daily scan in the background: how WorkManager plans it, what decides it, and when it waits. */
@RunWith(AndroidJUnit4::class)
class BackgroundIndexingTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

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
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackgroundIndexingScheduler.UNIQUE_WORK_NAME).get()

    private suspend fun awaitState(expected: WorkInfo.State) {
        withTimeout(10_000L) {
            while (planned().map { it.state }.distinct() != listOf(expected)) {
                delay(50)
            }
        }
    }

    @Test
    fun `turning the setting on plans one daily scan, and turning it off again drops it`() = runBlocking {
        val scheduler = BackgroundIndexingScheduler(context)

        scheduler.setEnabled(true)
        scheduler.setEnabled(true)
        awaitState(WorkInfo.State.ENQUEUED)
        assertEquals("One request, however often the setting is applied", 1, planned().size)

        scheduler.setEnabled(false)
        awaitState(WorkInfo.State.CANCELLED)
    }

    @Test
    fun `the setting decides, from the moment the app starts and whenever it changes`() = runBlocking {
        val settings = FakeSettingsRepository()
        val following = BackgroundIndexing(settings, BackgroundIndexingScheduler(context))

        following.start(CoroutineScope(Dispatchers.Unconfined))
        assertTrue("Off until the user turns it on: nothing is planned", planned().isEmpty())

        settings.update { it.copy(backgroundIndexing = true) }
        awaitState(WorkInfo.State.ENQUEUED)

        // Another setting changes: the plan stays as it is.
        settings.update { it.copy(minFileSizeBytes = 4_096L) }
        delay(200)
        awaitState(WorkInfo.State.ENQUEUED)
        assertEquals(1, planned().size)

        settings.update { it.copy(backgroundIndexing = false) }
        awaitState(WorkInfo.State.CANCELLED)
    }

    @Test
    fun `the worker waits while the user's own scan is running`() = runBlocking {
        val scans = ScanHarness(temporaryFolder.root)
        try {
            val control = FakeScanControl()
            val worker = TestListenableWorkerBuilder<IncrementalIndexWorker>(context)
                .setWorkerFactory(factory(scans, control))
                .build()
            control.state.value = ScanState.Running(ScanProgress(filesScanned = 3L), isPaused = false)

            assertTrue(worker.doWork() is ListenableWorker.Result.Retry)
            assertTrue("Nothing was scanned beside the user's scan", scans.hasher.partiallyHashed.isEmpty())
        } finally {
            scans.close()
        }
    }

    @Test
    fun `the worker scans when no other scan is running`() = runBlocking {
        val scans = ScanHarness(temporaryFolder.root)
        try {
            scans.write("Download/notes.log", 100, seed = 1)
            val control = FakeScanControl()
            val worker = TestListenableWorkerBuilder<IncrementalIndexWorker>(context)
                .setWorkerFactory(factory(scans, control))
                .build()

            assertTrue(worker.doWork() is ListenableWorker.Result.Success)
            assertEquals(1, scans.fileIndex.observeFileCount().first())
        } finally {
            scans.close()
        }
    }

    /** Builds the worker with the pieces it needs, as Hilt would. */
    private fun factory(
        scans: ScanHarness,
        control: FakeScanControl,
    ) = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker =
            IncrementalIndexWorker(appContext, workerParameters, scans.runner, StorageAccess.GRANTED, control)
    }
}

/** The folder lister on real folders. */
@RunWith(AndroidJUnit4::class)
class NioFolderListerTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `lists the folders inside a folder by name, and leaves hidden ones and files out`() {
        val root = temporaryFolder.root
        File(root, "Zebra").mkdirs()
        File(root, "alpha").mkdirs()
        File(root, ".hidden").mkdirs()
        File(root, "notes.txt").writeText("a file, not a folder")

        val listed = NioFolderLister().listFolders(root.absolutePath)

        assertEquals("By name, whatever the case", listOf("alpha", "Zebra"), listed.map { File(it).name })
        assertTrue("Every folder listed is a folder", listed.all { File(it).isDirectory })
    }

    @Test
    fun `a folder that is not there has no folders`() {
        assertTrue(NioFolderLister().listFolders(File(temporaryFolder.root, "nowhere").absolutePath).isEmpty())
    }
}
