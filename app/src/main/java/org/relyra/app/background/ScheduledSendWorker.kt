package org.relyra.app.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.relyra.app.RelyraApp
import java.util.concurrent.TimeUnit

/** Wakes the app when a scheduled message becomes due. The queueing itself is idempotent (see ScheduledMessageService). */
class ScheduledSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val container = (applicationContext as RelyraApp).container
        container.open()
        container.scheduled.dispatchDue()
        Result.success()
    } catch (t: Throwable) {
        Result.retry()
    }

    companion object {
        private fun name(id: String) = "relyra-scheduled-$id"

        fun schedule(context: Context, id: String, sendAt: Long) {
            val delay = (sendAt - System.currentTimeMillis()).coerceAtLeast(0L)
            val req = OneTimeWorkRequestBuilder<ScheduledSendWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(id), ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork(name(id))
        }
    }
}
