package org.relyra.app.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.relyra.app.RelyraApp

/** Restarts the optional "stay connected" service after a reboot or an app update, only if the user turned it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as RelyraApp
        if (app.container.settings.keepConnected.value || app.container.network.meshEnabled.value) {
            ConnectionService.start(context)
        }
    }
}
