package com.cleansweep.app.data.scanner.trash

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cleansweep.app.domain.scanner.trash.PurgeScheduler
import com.cleansweep.app.domain.scanner.trash.TrashManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.time.Duration

/**
 * [PurgeScheduler] on WorkManager: one recurring request, once a day, that survives the app being closed and
 * the phone being restarted.
 */
class WorkManagerPurgeScheduler(
    private val context: Context,
) : PurgeScheduler {

    override fun schedule() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            // There is at most one purge request. Asking again must not restart its interval.
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TrashPurgeWorker>(Duration.ofDays(1))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(30))
                .build(),
        )
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        /** The name the one recurring purge is known under. */
        const val UNIQUE_WORK_NAME: String = "safe-trash-purge"
    }
}

/**
 * Deletes the Safe Trash entries whose 30 days are over.
 *
 * An entry that stays too long costs nothing but space, so a failed run is simply tried again and then given
 * up until the next one. The trash manager never leaves a half-deleted entry behind.
 */
@HiltWorker
class TrashPurgeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trashManager: TrashManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        trashManager.purgeExpired()
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val MAX_ATTEMPTS: Int = 3
    }
}
