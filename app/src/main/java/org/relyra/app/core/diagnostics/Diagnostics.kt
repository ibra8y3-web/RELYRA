package org.relyra.app.core.diagnostics

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.relyra.app.AppContainer
import org.relyra.app.BuildConfig
import org.relyra.app.core.storage.DataCounts
import org.relyra.app.core.storage.StorageUsage

class DiagnosticsReport(
    val time: Long,
    val appVersion: String,
    val androidVersion: String,
    val device: String,
    val net: NetInfo,
    val battery: BatteryInfo,
    val memory: MemoryStats,
    val batteryOptimized: Boolean,
    val databaseOk: Boolean,
    val databaseDetail: String,
    val counts: DataCounts,
    val usage: StorageUsage,
    val queued: Int,
    val failed: Int,
    val expired: Int,
    val groupPending: Int,
    val relaysEnabled: Int,
    val relaysConnected: Int,
    val fileServersEnabled: Int,
    val notificationsAllowed: Boolean,
    val keepConnected: Boolean,
) {
    /**
     * Plain-text report that is safe to share: counts and system readings only. No names, public IDs, keys, relay or
     * server addresses, and no message text.
     */
    fun toRedactedText(): String = buildString {
        appendLine("RELYRA diagnostics")
        appendLine("App: $appVersion")
        appendLine("Android: $androidVersion")
        appendLine("Device: $device")
        appendLine("Network: ${net.kind}, metered=${net.metered}, vpn=${net.vpn}")
        appendLine("Battery: ${if (battery.percent >= 0) "${battery.percent}%" else "unknown"}, charging=${battery.charging}, saver=${battery.powerSaver}")
        appendLine("Battery optimization applies to RELYRA: $batteryOptimized")
        appendLine("Database integrity: ${if (databaseOk) "ok" else databaseDetail}")
        appendLine("Messages: ${counts.messages}, group messages: ${counts.groupMessages}, files: ${counts.attachments}")
        appendLine("Queued: $queued, failed: $failed, expired: $expired, group copies waiting: $groupPending")
        appendLine("Relays enabled: $relaysEnabled, connected: $relaysConnected")
        appendLine("File servers enabled: $fileServersEnabled")
        appendLine("Notifications allowed by Android: $notificationsAllowed, stay-connected mode: $keepConnected")
        appendLine("Database bytes: ${usage.databaseBytes}, files bytes: ${usage.filesBytes}, cache bytes: ${usage.cacheBytes}")
        appendLine("App memory bytes: ${memory.appUsedBytes} of ${memory.appMaxBytes}")
    }
}

object Diagnostics {
    suspend fun collect(c: AppContainer, ctx: Context): DiagnosticsReport = withContext(Dispatchers.IO) {
        val db = c.database
        var ok = false
        var detail = ""
        try {
            db.openHelper.writableDatabase.query("PRAGMA integrity_check").use {
                val first = if (it.moveToFirst()) it.getString(0) else ""
                ok = first == "ok"
                detail = if (ok) "" else first.take(200)
            }
        } catch (t: Throwable) {
            detail = t.javaClass.simpleName
        }
        val msgDao = db.messageDao()
        DiagnosticsReport(
            time = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            net = DeviceInfo.network(ctx),
            battery = DeviceInfo.battery(ctx),
            memory = DeviceInfo.memory(ctx),
            batteryOptimized = DeviceInfo.batteryOptimized(ctx),
            databaseOk = ok,
            databaseDetail = detail,
            counts = c.data.counts(),
            usage = c.data.usage(),
            queued = msgDao.countOutgoing("QUEUED"),
            failed = msgDao.countOutgoing("FAILED"),
            expired = msgDao.countOutgoing("EXPIRED"),
            groupPending = db.groupDao().observePending().first(),
            relaysEnabled = c.network.relays.value.count { it.enabled },
            relaysConnected = c.nostr.connections.value.values.count { it },
            fileServersEnabled = c.blossom.servers.value.count { it.enabled },
            notificationsAllowed = c.notifier.systemAllows(),
            keepConnected = c.settings.keepConnected.value,
        )
    }
}
