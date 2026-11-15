package org.relyra.app.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.relyra.app.MainActivity
import org.relyra.app.R
import org.relyra.app.core.notify.Notifier

/** Holds the microphone/camera foreground-service lifetime for an active, user-started call. */
class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Notifier.CH_CALLS, getString(R.string.call_notification_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val open = android.app.PendingIntent.getActivity(
            this, SERVICE_ID, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, Notifier.CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_relyra)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.call_active))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val types = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                (if (video) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        } else 0
        ServiceCompat.startForeground(this, SERVICE_ID, notification, types)
        return START_NOT_STICKY
    }

    companion object {
        private const val SERVICE_ID = 4748
        private const val EXTRA_VIDEO = "video"
        fun start(context: Context, video: Boolean) {
            ContextCompat.startForegroundService(
                context, Intent(context, CallForegroundService::class.java).putExtra(EXTRA_VIDEO, video)
            )
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
