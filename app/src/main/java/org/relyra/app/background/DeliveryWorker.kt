package org.relyra.app.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.relyra.app.RelyraApp

/** Periodic safety net for message delivery and encrypted device replication when the UI is closed. */
class DeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val container = (applicationContext as RelyraApp).container
        container.open()
        container.nostr.syncOnce(container.identities.nostrSecret(), 20_000)
        // Device replication is a separate Nostr event stream; syncing the normal inbox alone is insufficient.
        container.deviceSyncNetwork.syncNow()
        container.delivery.processOutbox()
        Result.success()
    } catch (t: Throwable) {
        Result.retry()
    }
}
