package dev.sogn.snaptune.shared

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.sogn.snaptune.shared.data.PodcastRepository
import java.util.concurrent.TimeUnit

class PodcastSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        PodcastRepository.getInstance(applicationContext).syncDueFeedUpdates()
        return Result.success()
    }
}

object PodcastSyncScheduler {
    private const val PERIODIC_WORK_NAME = "podcast_periodic_sync"
    private const val IMMEDIATE_WORK_NAME = "podcast_immediate_sync"

    fun ensureScheduled(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<PodcastSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        val immediateRequest = OneTimeWorkRequestBuilder<PodcastSyncWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            immediateRequest
        )
    }
}
