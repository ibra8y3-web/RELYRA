package org.relyra.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.storage.ThemeMode
import org.relyra.app.ui.components.InfoRow
import org.relyra.app.ui.components.SectionCard
import org.relyra.app.ui.components.rememberNotificationRequester

@Composable
fun NotificationSettingsCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val on by vm.settings.notifications.collectAsStateWithLifecycle()
    val preview by vm.settings.notifPreview.collectAsStateWithLifecycle()
    val keep by vm.settings.keepConnected.collectAsStateWithLifecycle()
    var tick by remember { mutableStateOf(0) }
    val allowed = remember(tick, on) { vm.notificationsAllowed() }
    val request = rememberNotificationRequester { tick += 1 }

    fun openSystem(intent: Intent) {
        vm.externalInFlight = true
        try {
            ctx.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            vm.externalInFlight = false
        }
    }

    SectionCard(stringResource(R.string.settings_notifications)) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_notif_on)) },
            supportingContent = { Text(stringResource(R.string.settings_notif_on_desc)) },
            trailingContent = {
                Switch(checked = on, onCheckedChange = { want ->
                    vm.settings.setNotifications(want)
                    if (want) request()
                })
            },
        )
        if (on && !allowed) {
            Text(stringResource(R.string.settings_notif_blocked), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            TextButton(onClick = {
                openSystem(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }) { Text(stringResource(R.string.settings_notif_open_system)) }
        }
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_notif_preview)) },
            supportingContent = { Text(stringResource(R.string.settings_notif_preview_desc)) },
            trailingContent = { Switch(checked = preview, onCheckedChange = { vm.settings.setNotifPreview(it) }) },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_keep)) },
            supportingContent = { Text(stringResource(R.string.settings_keep_desc)) },
            trailingContent = { Switch(checked = keep, onCheckedChange = { vm.setKeepConnected(it) }) },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_battery)) },
            supportingContent = { Text(stringResource(R.string.settings_battery_desc)) },
            modifier = Modifier.clickable { openSystem(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        )
    }
}

@Composable
fun PrivacyAppearanceCards(vm: MainViewModel) {
    val shots by vm.settings.blockScreenshots.collectAsStateWithLifecycle()
    val listPreview by vm.settings.listPreview.collectAsStateWithLifecycle()
    val theme by vm.settings.theme.collectAsStateWithLifecycle()
    SectionCard(stringResource(R.string.settings_privacy)) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_shots)) },
            supportingContent = { Text(stringResource(R.string.settings_shots_desc)) },
            trailingContent = { Switch(checked = shots, onCheckedChange = { vm.settings.setBlockScreenshots(it) }) },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_list_preview)) },
            supportingContent = { Text(stringResource(R.string.settings_list_preview_desc)) },
            trailingContent = { Switch(checked = listPreview, onCheckedChange = { vm.settings.setListPreview(it) }) },
        )
    }
    SectionCard(stringResource(R.string.settings_appearance)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { m ->
                FilterChip(
                    selected = theme == m,
                    onClick = { vm.setTheme(m) },
                    label = {
                        Text(
                            stringResource(
                                when (m) {
                                    ThemeMode.SYSTEM -> R.string.theme_system
                                    ThemeMode.LIGHT -> R.string.theme_light
                                    ThemeMode.DARK -> R.string.theme_dark
                                    ThemeMode.AMOLED -> R.string.theme_amoled
                                }
                            )
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun DataStorageCard(vm: MainViewModel, onOpenDiagnostics: () -> Unit) {
    val ctx = LocalContext.current
    val wifiOnly by vm.settings.wifiOnlyDownloads.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    var confirmDays by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { vm.refreshUsage() }

    SectionCard(stringResource(R.string.settings_data)) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_wifi_only)) },
            supportingContent = { Text(stringResource(R.string.settings_wifi_only_desc)) },
            trailingContent = { Switch(checked = wifiOnly, onCheckedChange = { vm.settings.setWifiOnlyDownloads(it) }) },
        )
        HorizontalDivider()
        usage?.let { u ->
            InfoRow(stringResource(R.string.storage_database), Formatter.formatShortFileSize(ctx, u.databaseBytes))
            InfoRow(stringResource(R.string.storage_files), Formatter.formatShortFileSize(ctx, u.filesBytes))
            InfoRow(stringResource(R.string.storage_cache), Formatter.formatShortFileSize(ctx, u.cacheBytes))
            InfoRow(stringResource(R.string.storage_free), Formatter.formatShortFileSize(ctx, u.freeBytes))
        }
        OutlinedButton(onClick = { vm.clearCache() }, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.storage_clear_cache))
        }
        Text(stringResource(R.string.retention_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        Text(stringResource(R.string.retention_desc), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            listOf(30, 90, 365).forEach { d ->
                OutlinedButton(onClick = { confirmDays = d }) { Text(stringResource(R.string.retention_option, d)) }
            }
        }
        TextButton(onClick = onOpenDiagnostics, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.diag_open))
        }
    }

    confirmDays?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDays = null },
            title = { Text(stringResource(R.string.retention_title)) },
            text = { Text(stringResource(R.string.retention_confirm, d)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteOlderThan(d); confirmDays = null }) {
                    Text(stringResource(R.string.retention_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDays = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
