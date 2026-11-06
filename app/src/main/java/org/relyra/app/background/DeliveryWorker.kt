package org.relyra.app.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.relyra.app.RelyraApp

/** Periodic safety net: retries due messages even when the UI is closed (subject to Android background limits). */
class DeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val container = (applicationContext as RelyraApp).container
        container.open()
        container.nostr.syncOnce(container.identities.nostrSecret(), 20_000)
        container.delivery.processOutbox()
        Result.success()
    } catch (t: Throwable) {
        Result.retry()
    }
}
