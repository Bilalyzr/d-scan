package com.cleansweep.app.data.scanner

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cleansweep.app.domain.scanner.PauseGate
import com.cleansweep.app.domain.scanner.ScanException
import com.cleansweep.app.domain.scanner.ScanFailure
import com.cleansweep.app.domain.scanner.ScanProgressListener
import com.cleansweep.app.domain.scanner.ScanRequest
import com.cleansweep.app.domain.scanner.ScanRunner
import com.cleansweep.app.domain.scanner.StorageAccess
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Runs a full incremental scan in the background (PRD 4.6, scheduled indexing).
 *
 * Nobody schedules this worker yet: it is added to the periodic schedule only once the user turns background
 * indexing on in Settings, which is part of the settings screen. A background scan cannot be paused and shows no
 * progress, so it ignores both: work already indexed and hashed stays done if it is stopped.
 */
@HiltWorker
class IncrementalIndexWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runner: ScanRunner,
    private val storageAccess: StorageAccess,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!storageAccess.canReadStorage()) {
            // Access was taken away since the schedule was made. Retrying cannot change that.
            return Result.failure()
        }
        return try {
            val summary = runner.run(ScanRequest(), PauseGate(), ScanProgressListener { })
            if (summary.rootsScanned.isEmpty()) throw ScanException(ScanFailure.NO_STORAGE_TO_SCAN, "No storage volume is mounted")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ScanException) {
            if (failure.failure == ScanFailure.NO_STORAGE_TO_SCAN && runAttemptCount < MAX_ATTEMPTS) {
                Result.retry() // a volume may come back
            } else {
                Result.failure()
            }
        } catch (failure: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } catch (failure: Exception) {
            Result.failure()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
