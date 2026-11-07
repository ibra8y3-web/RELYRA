package org.relyra.app

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.relyra.app.background.DeliveryWorker
import java.util.concurrent.TimeUnit

class RelyraApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    /** True when a staged backup was applied during this start (the UI tells the user once). */
    @Volatile var restoreApplied = false

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        // A backup staged by the previous run is swapped in before anything opens the database.
        restoreApplied = try {
            org.relyra.app.core.backup.RestoreApplier.applyIfPending(
                java.io.File(filesDir, "restore"), getDatabasePath("relyra.db"), container.secure,
            )
        } catch (t: Throwable) {
            false
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "relyra-delivery",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DeliveryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}
