package org.relyra.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.diagnostics.NetKind
import org.relyra.app.core.network.RelayStatus
import org.relyra.app.core.transfer.ServerStatus
import org.relyra.app.ui.components.InfoRow
import org.relyra.app.ui.components.SectionCard

@Composable
fun DiagnosticsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val report by vm.diagnostics.collectAsStateWithLifecycle()
    val resourceSnapshot by vm.resourceSnapshot.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val relays by vm.relays.collectAsStateWithLifecycle()
    val relayStatus by vm.relayStatus.collectAsStateWithLifecycle()
    val connected by vm.relayConnected.collectAsStateWithLifecycle()
    val servers by vm.fileServers.collectAsStateWithLifecycle()
    val serverStatus by vm.fileServerStatus.collectAsStateWithLifecycle()
    val shareTitle = stringResource(R.string.diag_share)
    LaunchedEffect(Unit) {
        vm.runDiagnostics()
        while (true) {
            vm.refreshResourceSnapshot()
            delay(2_000)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.runDiagnostics(); vm.refreshResourceSnapshot() }, enabled = !busy) { Text(stringResource(R.string.diag_refresh)) }
            OutlinedButton(onClick = {
                relays.filter { it.enabled }.forEach { vm.testRelay(it.url) }
                servers.filter { it.enabled }.forEach { vm.testFileServer(it.url) }
            }) { Text(stringResource(R.string.diag_test_all)) }
        }
        val r = report
        if (r == null) {
            CircularProgressIndicator()
            return@Column
        }
        SectionCard(stringResource(R.string.diag_device)) {
            InfoRow(stringResource(R.string.diag_app), r.appVersion)
            InfoRow("Android", r.androidVersion)
            InfoRow(stringResource(R.string.diag_device_model), r.device)
            InfoRow(
                stringResource(R.string.diag_battery),
                (if (r.battery.percent >= 0) "${r.battery.percent}%" else "?") +
                    (if (r.battery.charging) " · " + stringResource(R.string.diag_charging) else "") +
                    (if (r.battery.powerSaver) " · " + stringResource(R.string.diag_saver) else ""),
            )
            InfoRow(
                stringResource(R.string.diag_network),
                stringResource(
                    when (r.net.kind) {
                        NetKind.NONE -> R.string.diag_net_none
                        NetKind.WIFI -> R.string.diag_net_wifi
                        NetKind.CELLULAR -> R.string.diag_net_cellular
                        NetKind.ETHERNET -> R.string.diag_net_ethernet
                        NetKind.OTHER -> R.string.diag_net_other
                    }
                ) + (if (r.net.metered) " · " + stringResource(R.string.diag_metered) else "") +
                    (if (r.net.vpn) " · VPN" else ""),
            )
            InfoRow(
                stringResource(R.string.diag_memory),
                Formatter.formatShortFileSize(ctx, r.memory.appUsedBytes) + " / " + Formatter.formatShortFileSize(ctx, r.memory.appMaxBytes),
            )
        }
        SectionCard(stringResource(R.string.diag_resources)) {
            val snapshot = resourceSnapshot
            if (snapshot == null) {
                Text(stringResource(R.string.diag_resources_waiting), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                InfoRow(
                    stringResource(R.string.diag_app_cpu),
                    snapshot.appCpuPercent?.let { String.format(Locale.ROOT, "%.1f%%", it) }
                        ?: stringResource(R.string.diag_resources_waiting),
                )
                InfoRow(
                    stringResource(R.string.diag_app_rx),
                    snapshot.rxBytesPerSecond?.let { Formatter.formatShortFileSize(ctx, it) + "/s" }
                        ?: stringResource(R.string.diag_resources_unavailable),
                )
                InfoRow(
                    stringResource(R.string.diag_app_tx),
                    snapshot.txBytesPerSecond?.let { Formatter.formatShortFileSize(ctx, it) + "/s" }
                        ?: stringResource(R.string.diag_resources_unavailable),
                )
                InfoRow(
                    stringResource(R.string.diag_filesystem_free),
                    if (snapshot.storageAvailableBytes != null && snapshot.storageTotalBytes != null)
                        Formatter.formatShortFileSize(ctx, snapshot.storageAvailableBytes) + " / " +
                            Formatter.formatShortFileSize(ctx, snapshot.storageTotalBytes)
                    else stringResource(R.string.diag_resources_unavailable),
                )
            }
            Text(stringResource(R.string.diag_resources_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard(stringResource(R.string.diag_messages)) {
            InfoRow(stringResource(R.string.diag_total_messages), r.counts.messages.toString())
            InfoRow(stringResource(R.string.diag_total_group_messages), r.counts.groupMessages.toString())
            InfoRow(stringResource(R.string.diag_total_files), r.counts.attachments.toString())
            InfoRow(stringResource(R.string.diag_queued), r.queued.toString())
            InfoRow(stringResource(R.string.diag_failed), r.failed.toString())
            InfoRow(stringResource(R.string.diag_expired), r.expired.toString())
            InfoRow(stringResource(R.string.diag_group_waiting), r.groupPending.toString())
            if (r.failed > 0 || r.expired > 0) {
                Text(stringResource(R.string.diag_failed_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionCard(stringResource(R.string.net_title)) {
            InfoRow(stringResource(R.string.home_relays), "${r.relaysEnabled} / ${relays.size}")
            InfoRow(stringResource(R.string.home_relays_connected), connected.values.count { it }.toString())
            InfoRow(stringResource(R.string.home_file_servers), "${r.fileServersEnabled} / ${servers.size}")
            if (r.relaysEnabled == 0) {
                Text(stringResource(R.string.diag_no_relay), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            relays.forEach { rl ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(rl.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(if (connected[rl.url] == true) R.string.net_conn_yes else R.string.net_conn_no) + " · " +
                        statusText(relayStatus[rl.url] ?: RelayStatus.Untested),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            servers.forEach { sv ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(sv.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(fileStatusText(serverStatus[sv.url] ?: ServerStatus.Untested), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionCard(stringResource(R.string.diag_storage)) {
            InfoRow(
                stringResource(R.string.diag_db_integrity),
                if (r.databaseOk) stringResource(R.string.diag_ok) else stringResource(R.string.diag_problem, r.databaseDetail),
            )
            InfoRow(stringResource(R.string.storage_database), Formatter.formatShortFileSize(ctx, r.usage.databaseBytes))
            InfoRow(stringResource(R.string.storage_files), Formatter.formatShortFileSize(ctx, r.usage.filesBytes))
            InfoRow(stringResource(R.string.storage_cache), Formatter.formatShortFileSize(ctx, r.usage.cacheBytes))
            InfoRow(stringResource(R.string.storage_free), Formatter.formatShortFileSize(ctx, r.usage.freeBytes) + " / " + Formatter.formatShortFileSize(ctx, r.usage.totalBytes))
        }
        SectionCard(stringResource(R.string.diag_background)) {
            InfoRow(stringResource(R.string.diag_notifications), stringResource(if (r.notificationsAllowed) R.string.state_on else R.string.state_off))
            InfoRow(stringResource(R.string.home_keep), stringResource(if (r.keepConnected) R.string.state_on else R.string.state_off))
            InfoRow(stringResource(R.string.diag_battery_opt), stringResource(if (r.batteryOptimized) R.string.state_on else R.string.state_off))
            Text(stringResource(R.string.diag_background_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.batteryOptimized) {
                TextButton(onClick = {
                    vm.externalInFlight = true
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e: ActivityNotFoundException) {
                        vm.externalInFlight = false
                    }
                }) { Text(stringResource(R.string.settings_battery)) }
            }
        }
        OutlinedButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, r.toRedactedText())
            }
            vm.externalInFlight = true
            ctx.startActivity(Intent.createChooser(send, shareTitle))
        }) { Text(stringResource(R.string.diag_share)) }
        Text(stringResource(R.string.diag_share_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
