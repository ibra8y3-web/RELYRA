package org.relyra.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.network.AddRelayResult
import org.relyra.app.core.network.RelayStatus
import org.relyra.app.core.transfer.ServerStatus
import org.relyra.app.ui.components.InfoRow
import org.relyra.app.ui.components.SectionCard

@Composable
fun NetworkScreen(vm: MainViewModel) {
    val relays by vm.relays.collectAsStateWithLifecycle()
    val status by vm.relayStatus.collectAsStateWithLifecycle()
    val connected by vm.relayConnected.collectAsStateWithLifecycle()
    val nostrKey by vm.nostrKey.collectAsStateWithLifecycle()
    val fileServers by vm.fileServers.collectAsStateWithLifecycle()
    val fileStatus by vm.fileServerStatus.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    var fileInput by rememberSaveable { mutableStateOf("") }
    var fileError by rememberSaveable { mutableStateOf<Int?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(stringResource(R.string.net_transport)) {
            InfoRow(stringResource(R.string.home_relays), "${relays.count { it.enabled }} / ${relays.size}")
            Text(
                stringResource(if (relays.any { it.enabled }) R.string.net_transport_active else R.string.net_transport_inactive),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        nostrKey?.let { key ->
            SectionCard(stringResource(R.string.net_your_key)) {
                SelectionContainer { Text(key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.net_your_key_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionCard(stringResource(R.string.net_relays)) {
            if (relays.isEmpty()) Text(stringResource(R.string.net_no_relays), color = MaterialTheme.colorScheme.onSurfaceVariant)
            relays.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(r.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(if (connected[r.url] == true) R.string.net_conn_yes else R.string.net_conn_no),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(statusText(status[r.url] ?: RelayStatus.Untested), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.testRelay(r.url) }) { Text(stringResource(R.string.net_test)) }
                    TextButton(onClick = { vm.removeRelay(r.url) }) {
                        Text(stringResource(R.string.net_remove), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.net_enabled), Modifier.weight(1f).padding(start = 8.dp))
                    Switch(checked = r.enabled, onCheckedChange = { vm.setRelayEnabled(r.url, it) })
                }
            }
        }
        SectionCard(stringResource(R.string.net_add_title)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it; error = null },
                label = { Text(stringResource(R.string.net_add_field)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.net_add_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Button(
                onClick = {
                    when (vm.addRelay(input)) {
                        AddRelayResult.Added -> { input = ""; error = null }
                        AddRelayResult.Invalid -> error = R.string.err_relay_invalid
                        AddRelayResult.Duplicate -> error = R.string.err_relay_duplicate
                    }
                },
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.net_add_action)) }
        }
        SectionCard(stringResource(R.string.files_servers)) {
            if (fileServers.isEmpty()) Text(stringResource(R.string.files_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            fileServers.forEachIndexed { i, srv ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(srv.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Text(fileStatusText(fileStatus[srv.url] ?: ServerStatus.Untested), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.testFileServer(srv.url) }) { Text(stringResource(R.string.net_test)) }
                    TextButton(onClick = { vm.removeFileServer(srv.url) }) {
                        Text(stringResource(R.string.net_remove), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.net_enabled), Modifier.weight(1f).padding(start = 8.dp))
                    Switch(checked = srv.enabled, onCheckedChange = { vm.setFileServerEnabled(srv.url, it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.files_sign_identity), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = srv.signWithIdentity, onCheckedChange = { vm.setFileServerSign(srv.url, it) })
                }
            }
        }
        SectionCard(stringResource(R.string.files_add_title)) {
            OutlinedTextField(
                value = fileInput, onValueChange = { fileInput = it; fileError = null },
                label = { Text(stringResource(R.string.files_add_field)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            fileError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.files_add_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Button(
                onClick = {
                    when (vm.addFileServer(fileInput)) {
                        AddRelayResult.Added -> { fileInput = ""; fileError = null }
                        AddRelayResult.Invalid -> fileError = R.string.err_server_invalid
                        AddRelayResult.Duplicate -> fileError = R.string.err_server_duplicate
                    }
                },
                enabled = fileInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.files_add_action)) }
        }
        val suggestedRelays = SUGGESTED_RELAYS.filter { u -> relays.none { it.url.trimEnd('/') == u } }
        val suggestedServers = SUGGESTED_FILE_SERVERS.filter { u -> fileServers.none { it.url.trimEnd('/') == u } }
        if (suggestedRelays.isNotEmpty() || suggestedServers.isNotEmpty()) {
            SectionCard(stringResource(R.string.net_suggested_title)) {
                Text(stringResource(R.string.net_suggested_body), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                suggestedRelays.forEach { u ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(u, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.addRelay(u) }) { Text(stringResource(R.string.net_suggested_add)) }
                    }
                }
                suggestedServers.forEach { u ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(u, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.addFileServer(u) }) { Text(stringResource(R.string.net_suggested_add)) }
                    }
                }
            }
        }
    }
}

private val SUGGESTED_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")
private val SUGGESTED_FILE_SERVERS = listOf("https://blossom.primal.net")

@Composable
internal fun statusText(s: RelayStatus): String = when (s) {
    RelayStatus.Untested -> stringResource(R.string.net_status_untested)
    RelayStatus.Testing -> stringResource(R.string.net_status_testing)
    is RelayStatus.Ok -> stringResource(R.string.net_status_ok, s.connectMs, s.eoseMs)
    is RelayStatus.Failed -> stringResource(R.string.net_status_failed, s.reason)
}

@Composable
internal fun fileStatusText(s: ServerStatus): String = when (s) {
    ServerStatus.Untested -> stringResource(R.string.net_status_untested)
    ServerStatus.Testing -> stringResource(R.string.net_status_testing)
    is ServerStatus.Reachable -> stringResource(R.string.files_status_reachable, s.httpCode, s.ms)
    is ServerStatus.Failed -> stringResource(R.string.net_status_failed, s.reason)
}
