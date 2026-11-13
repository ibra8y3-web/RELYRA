package org.relyra.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.ui.components.InfoRow
import org.relyra.app.ui.components.SectionCard
import java.text.DateFormat
import java.util.Date

@Composable
fun DeveloperToolsScreen(vm: MainViewModel, onOpenDiagnostics: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val api by vm.localApi.state.collectAsStateWithLifecycle()
    val plugins by vm.plugins.installed.collectAsStateWithLifecycle()
    val events by vm.plugins.events.collectAsStateWithLifecycle()
    val relays by vm.relays.collectAsStateWithLifecycle()
    val relayConnections by vm.relayConnected.collectAsStateWithLifecycle()
    val meshStatus by vm.meshStatus.collectAsStateWithLifecycle()
    var showToken by remember(api.token) { mutableStateOf(false) }
    var removePluginId by remember { mutableStateOf<String?>(null) }
    var pluginActionMessage by remember { mutableStateOf<Int?>(null) }
    val activeToken = api.token

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.developer_tools_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionCard(stringResource(R.string.developer_tools_api)) {
            InfoRow(
                stringResource(R.string.developer_tools_status),
                stringResource(if (api.running) R.string.developer_tools_running else R.string.developer_tools_stopped),
            )
            api.port?.let { port ->
                InfoRow(stringResource(R.string.developer_tools_endpoint), "http://127.0.0.1:$port")
                Text("GET /v1/health · /v1/diagnostics · /v1/openapi.json",
                    style = MaterialTheme.typography.bodySmall)
                Text("GET /v1/identity (only when identity.read is enabled)",
                    style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.developer_tools_adb_hint, port),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString("adb forward tcp:8787 tcp:$port"))
                }) { Text(stringResource(R.string.developer_tools_copy_forward)) }
            }
            if (api.expiresAtMillis != null) {
                InfoRow(
                    stringResource(R.string.developer_tools_token_expires),
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(api.expiresAtMillis)),
                )
            }
            if (activeToken != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(activeToken))
                    }) { Text(stringResource(R.string.developer_tools_copy_token)) }
                    TextButton(onClick = { showToken = !showToken }) {
                        Text(stringResource(if (showToken) R.string.developer_tools_hide_token else R.string.developer_tools_show_token))
                    }
                }
                if (showToken) {
                    Text(activeToken, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.developer_tools_token_warning),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            api.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.localApi.start(exposeIdentity = api.exposesIdentity) },
                    enabled = !api.running,
                ) { Text(stringResource(R.string.developer_tools_start)) }
                OutlinedButton(onClick = { vm.localApi.stop() }, enabled = api.running) {
                    Text(stringResource(R.string.developer_tools_stop))
                }
            }
        }

        SectionCard(stringResource(R.string.developer_tools_protocols)) {
            InfoRow(stringResource(R.string.developer_tools_relays),
                stringResource(R.string.developer_tools_relay_summary,
                    relays.count { it.enabled }, relays.size, relayConnections.values.count { it }))
            InfoRow(stringResource(R.string.developer_tools_plugins),
                stringResource(R.string.developer_tools_plugin_summary,
                    plugins.count { it.enabled }, plugins.size))
            InfoRow(stringResource(R.string.developer_tools_mesh), meshStatus.toString())
            Text(stringResource(R.string.developer_tools_redacted_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onOpenDiagnostics) { Text(stringResource(R.string.developer_tools_open_diagnostics)) }
        }

        SectionCard(stringResource(R.string.developer_tools_plugins)) {
            if (plugins.isEmpty()) {
                Text(stringResource(R.string.developer_tools_no_plugins),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                plugins.forEach { plugin ->
                    InfoRow(plugin.name, "${plugin.version} · " +
                        stringResource(if (plugin.enabled) R.string.state_on else R.string.state_off))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            vm.plugins.setEnabled(plugin.id, !plugin.enabled)
                            pluginActionMessage = null
                        }) {
                            Text(stringResource(if (plugin.enabled) R.string.developer_tools_disable_plugin else R.string.developer_tools_enable_plugin))
                        }
                        TextButton(onClick = { removePluginId = plugin.id }) {
                            Text(stringResource(R.string.developer_tools_remove_plugin),
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            pluginActionMessage?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.developer_tools_plugin_history),
                style = MaterialTheme.typography.titleSmall)
            if (events.isEmpty()) {
                Text(stringResource(R.string.developer_tools_no_events),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                events.takeLast(20).asReversed().forEach { event ->
                    Text("${event.pluginId} / ${event.actionId} · ${event.event}",
                        style = MaterialTheme.typography.bodySmall)
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(event.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    val selectedPlugin = plugins.firstOrNull { it.id == removePluginId }
    if (selectedPlugin != null) {
        AlertDialog(
            onDismissRequest = { removePluginId = null },
            title = { Text(stringResource(R.string.developer_tools_remove_plugin_title)) },
            text = { Text(stringResource(R.string.developer_tools_remove_plugin_confirm, selectedPlugin.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val removed = vm.plugins.remove(selectedPlugin.id)
                    pluginActionMessage = if (removed) R.string.developer_tools_plugin_removed
                        else R.string.developer_tools_plugin_remove_failed
                    removePluginId = null
                }) {
                    Text(stringResource(R.string.developer_tools_remove_plugin),
                        color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removePluginId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
