package com.cleansweep.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.cleansweep.app.di.ApplicationScope
import com.cleansweep.app.data.scanner.BackgroundIndexing
import com.cleansweep.app.domain.scanner.trash.TrashManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class CleanSweepApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var trashManager: TrashManager

    @Inject
    lateinit var backgroundIndexing: BackgroundIndexing

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Every start brings the Safe Trash manifest back in step with storage and purges what has expired. The
        // recurring purge in WorkManager does the same in between, but may run late.
        applicationScope.launch { trashManager.maintain() }
        // The daily scan in the background follows its setting from here on.
        backgroundIndexing.start(applicationScope)
    }

    /**
     * WorkManager is created on first use with Hilt's worker factory, which lets background workers
     * (`@HiltWorker`) take constructor-injected dependencies. The default initializer is removed in the manifest.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
