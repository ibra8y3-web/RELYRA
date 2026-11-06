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

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "relyra-delivery",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DeliveryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}
