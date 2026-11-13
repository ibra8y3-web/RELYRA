package org.relyra.app.background

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.relyra.app.RelyraApp
import org.relyra.app.core.notify.Notifier

/**
 * Optional "stay connected" mode. The foreground service keeps relay connections active while the UI is closed.
 * Android may still stop it under system policy; WorkManager remains the periodic fallback.
 */
class ConnectionService : Service() {
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as RelyraApp).container
        val first = container.notifier.serviceNotification(0, 0)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.SERVICE_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(Notifier.SERVICE_ID, first)
        }
        if (scope == null) {
            val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = s
            s.launch { keepAlive(container) }
        }
        return START_STICKY
    }

    private suspend fun keepAlive(container: org.relyra.app.AppContainer) {
        try {
            container.open()
        } catch (t: Throwable) {
            stopSelf()
            return
        }
        val secret = container.identities.nostrSecret()
        if (secret == null) {
            stopSelf()
            return
        }
        container.nostr.setIdentity(secret)
        val nm = getSystemService(NotificationManager::class.java)
        val s = scope ?: return
        s.launch {
            combine(container.settings.keepConnected, container.network.meshEnabled) { keepConnected, meshEnabled ->
                keepConnected || meshEnabled
            }.collect { shouldStayActive -> if (!shouldStayActive) stopSelf() }
        }
        // Do not wait for the first 15-minute timer after the user explicitly enabled stay-connected mode.
        runCatching { container.deviceSyncNetwork.syncNow() }
        while (s.isActive) {
            val enabled = container.network.relays.value.count { it.enabled }
            val connected = container.nostr.connections.value.values.count { it }
            nm.notify(Notifier.SERVICE_ID, container.notifier.serviceNotification(connected, enabled))
            runCatching { container.delivery.processOutbox() }
            delay(30_000)
        }
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
            } catch (e: RuntimeException) {
                // Android refused to start a foreground service from the background. It starts next time the app opens.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
