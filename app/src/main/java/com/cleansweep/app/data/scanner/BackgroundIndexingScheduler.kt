package com.cleansweep.app.data.scanner

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration

/**
 * Plans or drops the daily scan in the background, by the setting the user chose.
 *
 * There is one recurring request under one name, so turning the setting on twice does not plan two scans.
 */
class BackgroundIndexingScheduler(
    context: Context,
) {
    private val workManager: WorkManager = WorkManager.getInstance(context)

    fun setEnabled(
        enabled: Boolean,
    ) {
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<IncrementalIndexWorker>(Duration.ofHours(24))
                // Once a day is the point; there is no hurry, so it waits for a battery that is not low.
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(30))
                .build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME: String = "background-index"
    }
}
