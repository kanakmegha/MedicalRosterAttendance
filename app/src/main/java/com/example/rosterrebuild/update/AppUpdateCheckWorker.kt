package com.example.rosterrebuild.update

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Periodic background worker that checks for updates once every 24 hours
 * when connected to an unmetered or general network.
 *
 * Runs non-intrusively in the background without blocking the UI or interrupting roster workflows.
 */
class AppUpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "medical_roster_periodic_update_check"

        /**
         * Enqueues a 24-hour periodic update check with network constraints.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val updateWorkRequest = PeriodicWorkRequestBuilder<AppUpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                updateWorkRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        val configStore = UpdateConfigStore(applicationContext)
        val fetcher = UpdateManifestFetcher(applicationContext, configStore)

        return when (val result = fetcher.checkForUpdates()) {
            is UpdateCheckResult.UpdateAvailable -> {
                configStore.saveLastKnownManifest(result.manifest)
                Result.success()
            }
            is UpdateCheckResult.UpToDate -> {
                configStore.saveLastKnownManifest(result.manifest)
                Result.success()
            }
            is UpdateCheckResult.Error -> {
                if (result.errorType == UpdateErrorType.SERVER_UNAVAILABLE) {
                    Result.retry()
                } else {
                    Result.success() // Fatal errors shouldn't loop rapidly
                }
            }
        }
    }
}

