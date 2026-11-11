package org.relyra.app.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.network.AddRelayResult
import org.relyra.app.core.network.NearbyLanStatus
import org.relyra.app.core.network.BluetoothTransportStatus
import org.relyra.app.core.network.WifiDirectTransportStatus
import org.relyra.app.core.network.RelayStatus
import org.relyra.app.core.transfer.ServerStatus
import org.relyra.app.ui.components.InfoRow
import org.relyra.app.ui.components.SectionCard

@Composable
fun NetworkScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val relays by vm.relays.collectAsStateWithLifecycle()
    val status by vm.relayStatus.collectAsStateWithLifecycle()
    val connected by vm.relayConnected.collectAsStateWithLifecycle()
    val nostrKey by vm.nostrKey.collectAsStateWithLifecycle()
    val nearbyEnabled by vm.nearbyEnabled.collectAsStateWithLifecycle()
    val nearbyPeers by vm.nearbyPeers.collectAsStateWithLifecycle()
    val nearbyStatus by vm.nearbyStatus.collectAsStateWithLifecycle()
    val bluetoothEnabled by vm.bluetoothEnabled.collectAsStateWithLifecycle()
    val bluetoothPeers by vm.bluetoothPeers.collectAsStateWithLifecycle()
    val bluetoothStatus by vm.bluetoothStatus.collectAsStateWithLifecycle()
    val wifiDirectEnabled by vm.wifiDirectEnabled.collectAsStateWithLifecycle()
    val wifiDirectPeers by vm.wifiDirectPeers.collectAsStateWithLifecycle()
    val wifiDirectVerifiedPeers by vm.wifiDirectVerifiedPeers.collectAsStateWithLifecycle()
    val wifiDirectStatus by vm.wifiDirectStatus.collectAsStateWithLifecycle()
    val torProxy by vm.torProxyConfig.collectAsStateWithLifecycle()
    val fileServers by vm.fileServers.collectAsStateWithLifecycle()
    val fileStatus by vm.fileServerStatus.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    var fileInput by rememberSaveable { mutableStateOf("") }
    var fileError by rememberSaveable { mutableStateOf<Int?>(null) }
    var torPort by rememberSaveable { mutableStateOf("9050") }
    var torPortError by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(torProxy.socksPort) {
        torPort = torProxy.socksPort.toString()
    }

    fun applyTorSettings(enabled: Boolean) {
        val port = torPort.toIntOrNull()
        if (port == null || port !in 1..65_535) {
            torPortError = true
            return
        }
        torPortError = !vm.setTorProxyConfig(enabled, port)
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.setBluetoothEnabled(true) }

    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.setWifiDirectEnabled(true) }

    fun toggleBluetooth(enabled: Boolean) {
        if (!enabled) {
            vm.setBluetoothEnabled(false)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        ) {
            vm.setBluetoothEnabled(true)
        } else {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun toggleWifiDirect(enabled: Boolean) {
        if (!enabled) {
            vm.setWifiDirectEnabled(false)
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            vm.setWifiDirectEnabled(true)
        } else {
            wifiPermissionLauncher.launch(permission)
        }
    }

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
        SectionCard(stringResource(R.string.net_tor_title)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.net_tor_strict_toggle), modifier = Modifier.weight(1f))
                Switch(
                    checked = torProxy.strictModeEnabled,
                    onCheckedChange = { applyTorSettings(it) },
                )
            }
            OutlinedTextField(
                value = torPort,
                onValueChange = { value ->
                    torPort = value.filter(Char::isDigit).take(5)
                    torPortError = false
                },
                label = { Text(stringResource(R.string.net_tor_port)) },
                singleLine = true,
                isError = torPortError,
                modifier = Modifier.fillMaxWidth(),
            )
            if (torPortError) {
                Text(stringResource(R.string.net_tor_invalid_port), color = MaterialTheme.colorScheme.error)
            }
            Text(
                stringResource(R.string.net_tor_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    if (torProxy.strictModeEnabled) R.string.net_tor_enabled
                    else R.string.net_tor_disabled
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (torProxy.strictModeEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { applyTorSettings(torProxy.strictModeEnabled) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.net_tor_apply))
            }
        }

        SectionCard(stringResource(R.string.net_bt_title)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.net_bt_toggle), modifier = Modifier.weight(1f))
                Switch(checked = bluetoothEnabled, onCheckedChange = ::toggleBluetooth)
            }
            Text(
                stringResource(R.string.net_bt_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    when (bluetoothStatus) {
                        BluetoothTransportStatus.Disabled -> R.string.net_bt_status_disabled
                        BluetoothTransportStatus.PermissionRequired -> R.string.net_bt_status_permission
                        BluetoothTransportStatus.Unsupported -> R.string.net_bt_status_unsupported
                        BluetoothTransportStatus.BluetoothOff -> R.string.net_bt_status_off
                        BluetoothTransportStatus.Listening -> R.string.net_bt_status_listening
                        BluetoothTransportStatus.Active -> R.string.net_bt_status_active
                        BluetoothTransportStatus.Failed -> R.string.net_bt_status_failed
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.net_bt_verified_count, bluetoothPeers.size))
            TextButton(
                onClick = vm::discoverBluetoothPeers,
                enabled = bluetoothEnabled,
            ) { Text(stringResource(R.string.net_bt_discover)) }
            bluetoothPeers.forEach { id ->
                Text(id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }) { Text(stringResource(R.string.net_bt_system_settings)) }
        }

        SectionCard(stringResource(R.string.net_wifi_direct_title)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.net_wifi_direct_toggle), modifier = Modifier.weight(1f))
                Switch(checked = wifiDirectEnabled, onCheckedChange = ::toggleWifiDirect)
            }
            Text(
                stringResource(R.string.net_wifi_direct_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    when (wifiDirectStatus) {
                        WifiDirectTransportStatus.Disabled -> R.string.net_wifi_direct_status_disabled
                        WifiDirectTransportStatus.PermissionRequired -> R.string.net_wifi_direct_status_permission
                        WifiDirectTransportStatus.Unsupported -> R.string.net_wifi_direct_status_unsupported
                        WifiDirectTransportStatus.WifiDirectUnavailable -> R.string.net_wifi_direct_status_unavailable
                        WifiDirectTransportStatus.Discovering -> R.string.net_wifi_direct_status_discovering
                        WifiDirectTransportStatus.Connecting -> R.string.net_wifi_direct_status_connecting
                        WifiDirectTransportStatus.Connected -> R.string.net_wifi_direct_status_connected
                        WifiDirectTransportStatus.Failed -> R.string.net_wifi_direct_status_failed
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoRow(stringResource(R.string.net_wifi_direct_verified), wifiDirectVerifiedPeers.size.toString())
            wifiDirectVerifiedPeers.forEach { peer ->
                Text(peer.publicId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = vm::refreshWifiDirectPeers, enabled = wifiDirectEnabled) {
                Text(stringResource(R.string.net_wifi_direct_refresh))
            }
            wifiDirectPeers.forEach { peer ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(peer.name.ifBlank { stringResource(R.string.net_wifi_direct_unknown_name) })
                Text(peer.deviceAddress, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                TextButton(
                    onClick = { vm.connectWifiDirect(peer.deviceAddress) },
                    enabled = wifiDirectEnabled,
                ) {
                    Text(stringResource(R.string.net_wifi_direct_connect))
                }
            }
        }

        SectionCard(stringResource(R.string.net_nearby_title)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.net_nearby_toggle), modifier = Modifier.weight(1f))
                Switch(checked = nearbyEnabled, onCheckedChange = vm::setNearbyEnabled)
            }
            Text(
                stringResource(R.string.net_nearby_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoRow(stringResource(R.string.net_nearby_peers), nearbyPeers.size.toString())
            Text(
                stringResource(
                    when (nearbyStatus) {
                        NearbyLanStatus.Disabled -> R.string.net_nearby_status_disabled
                        NearbyLanStatus.Starting -> R.string.net_nearby_status_starting
                        NearbyLanStatus.Searching -> R.string.net_nearby_status_searching
                        NearbyLanStatus.Active -> R.string.net_nearby_status_active
                        NearbyLanStatus.Failed -> R.string.net_nearby_status_failed
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (nearbyEnabled && nearbyPeers.isEmpty()) {
                Text(stringResource(R.string.net_nearby_empty), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            nearbyPeers.forEach { peer ->
                Text(peer.publicId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
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
