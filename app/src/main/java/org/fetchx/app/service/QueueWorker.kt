package org.fetchx.app.service

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import org.fetchx.app.App
import org.fetchx.app.data.Status

/**
 * Re-queues transfers that were interrupted (process death, reboot, lost network) and drains the queue
 * as a foreground worker until nothing is left to do. Scheduled with a "connected" constraint, so it also
 * fires when the network comes back.
 */
class QueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        Notifications.ID + 1,
        Notifications.build(applicationContext, "FetchX", "Resuming downloads"),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        val controller = (applicationContext as App).controller
        runCatching { setForeground(getForegroundInfo()) }
        controller.recoverInterrupted()

        var idle = 0
        while (idle < 3) {
            delay(1000)
            val pending = controller.dao.byStatus(Status.QUEUED).size + controller.dao.runningCount()
            idle = if (pending == 0 && !controller.hasActiveJobs()) idle + 1 else 0
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "queue-drain"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<QueueWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
