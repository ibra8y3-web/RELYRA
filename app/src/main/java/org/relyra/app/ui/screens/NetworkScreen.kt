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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.network.AddRelayResult
import org.relyra.app.core.network.NearbyLanStatus
import org.relyra.app.core.network.BluetoothTransportStatus
import org.relyra.app.core.network.WifiDirectTransportStatus
import org.relyra.app.core.network.TorProxyStatus
import org.relyra.app.core.network.RelayStatus
import org.relyra.app.core.network.InternetPeerState
import org.relyra.app.core.network.DhtDiscoveryStatus
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
    val meshEnabled by vm.meshEnabled.collectAsStateWithLifecycle()
    val meshRelayEnabled by vm.meshRelayEnabled.collectAsStateWithLifecycle()
    val meshQuotaMb by vm.meshQuotaMb.collectAsStateWithLifecycle()
    val meshStatus by vm.meshStatus.collectAsStateWithLifecycle()
    val meshStats by vm.meshStats.collectAsStateWithLifecycle()
    val wifiDirectEnabled by vm.wifiDirectEnabled.collectAsStateWithLifecycle()
    val wifiDirectPeers by vm.wifiDirectPeers.collectAsStateWithLifecycle()
    val wifiDirectVerifiedPeers by vm.wifiDirectVerifiedPeers.collectAsStateWithLifecycle()
    val wifiDirectStatus by vm.wifiDirectStatus.collectAsStateWithLifecycle()
    val lastTransportPath by vm.lastTransportPath.collectAsStateWithLifecycle()
    val internetPeerStates by vm.internetPeerStates.collectAsStateWithLifecycle()
    val incomingInternetOffers by vm.incomingInternetOffers.collectAsStateWithLifecycle()
    val internetSignal by vm.internetSignal.collectAsStateWithLifecycle()
    val discoveryGateways by vm.discoveryGateways.collectAsStateWithLifecycle()
    val publicDiscoveryEnabled by vm.publicDiscoveryEnabled.collectAsStateWithLifecycle()
    val discoveryStatus by vm.discoveryStatus.collectAsStateWithLifecycle()
    val discoveredPeer by vm.discoveredPeer.collectAsStateWithLifecycle()
    val discoveryGatewayTests by vm.discoveryGatewayTests.collectAsStateWithLifecycle()
    val torSettings by vm.torProxySettings.collectAsStateWithLifecycle()
    val torStatus by vm.torProxyStatus.collectAsStateWithLifecycle()
    val fileServers by vm.fileServers.collectAsStateWithLifecycle()
    val fileStatus by vm.fileServerStatus.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    var fileInput by rememberSaveable { mutableStateOf("") }
    var fileError by rememberSaveable { mutableStateOf<Int?>(null) }
    var torPortInput by rememberSaveable(torSettings.port) { mutableStateOf(torSettings.port.toString()) }
    var torPortError by rememberSaveable { mutableStateOf(false) }
    var p2pPeerId by rememberSaveable { mutableStateOf("") }
    var p2pRemoteSignal by rememberSaveable { mutableStateOf("") }
    var turnUrl by rememberSaveable { mutableStateOf("") }
    var turnUsername by rememberSaveable { mutableStateOf("") }
    var turnCredential by remember { mutableStateOf("") }
    var turnError by rememberSaveable { mutableStateOf(false) }
    var discoveryGatewayInput by rememberSaveable { mutableStateOf("") }
    var discoveryGatewayError by rememberSaveable { mutableStateOf<Int?>(null) }
    var meshQuotaInput by rememberSaveable(meshQuotaMb) { mutableStateOf(meshQuotaMb.toString()) }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.setBluetoothEnabled(true) }

    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.setWifiDirectEnabled(true) }
    val meshPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> if (grants.isNotEmpty() && grants.values.all { it }) vm.setMeshEnabled(true) }


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
    fun toggleMesh(enabled: Boolean) {
        if (!enabled) {
            vm.setMeshEnabled(false)
            return
        }
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.setMeshEnabled(true) else meshPermissionLauncher.launch(missing.toTypedArray())
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
            Text(
                stringResource(R.string.net_tor_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.net_tor_enable), modifier = Modifier.weight(1f))
                Switch(
                    checked = torSettings.enabled,
                    onCheckedChange = {
                        vm.updateTorProxySettings(it, torSettings.strict, torSettings.port)
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.net_tor_strict), modifier = Modifier.weight(1f))
                Switch(
                    checked = torSettings.strict,
                    enabled = torSettings.enabled,
                    onCheckedChange = {
                        vm.updateTorProxySettings(torSettings.enabled, it, torSettings.port)
                    },
                )
            }
            Text(
                stringResource(if (torSettings.strict) R.string.net_tor_strict_note else R.string.net_tor_fallback_warning),
                style = MaterialTheme.typography.bodySmall,
                color = if (torSettings.strict) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            OutlinedTextField(
                value = torPortInput,
                onValueChange = { value ->
                    torPortInput = value.filter(Char::isDigit).take(5)
                    torPortError = false
                },
                label = { Text(stringResource(R.string.net_tor_port)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = torPortError,
            )
            if (torPortError) {
                Text(stringResource(R.string.net_tor_port_invalid), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    val port = torPortInput.toIntOrNull()
                    if (port == null || !vm.updateTorProxySettings(torSettings.enabled, torSettings.strict, port)) {
                        torPortError = true
                    } else {
                        torPortError = false
                        vm.testTorProxy()
                    }
                },
                enabled = torSettings.enabled && torStatus != TorProxyStatus.Testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.net_tor_test))
            }
            Text(
                when (val state = torStatus) {
                    TorProxyStatus.Disabled -> stringResource(R.string.net_tor_status_disabled)
                    TorProxyStatus.ReadyToTest -> stringResource(R.string.net_tor_status_ready)
                    TorProxyStatus.Testing -> stringResource(R.string.net_tor_status_testing)
                    is TorProxyStatus.Verified -> stringResource(R.string.net_tor_status_verified, state.exitIp)
                    TorProxyStatus.NotTor -> stringResource(R.string.net_tor_status_not_tor)
                    is TorProxyStatus.Failed -> stringResource(R.string.net_tor_status_failed, state.reason)
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (torStatus) {
                    is TorProxyStatus.Verified -> MaterialTheme.colorScheme.primary
                    is TorProxyStatus.Failed, TorProxyStatus.NotTor -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        SectionCard(stringResource(R.string.net_dht_title)) {
            Text(
                stringResource(R.string.net_dht_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.net_dht_presence_toggle), Modifier.weight(1f))
                Switch(
                    checked = publicDiscoveryEnabled,
                    onCheckedChange = vm::setPublicDiscoveryEnabled,
                )
            }
            Text(
                stringResource(when (discoveryStatus) {
                    DhtDiscoveryStatus.Disabled -> R.string.net_dht_status_disabled
                    DhtDiscoveryStatus.NoGateway -> R.string.net_dht_status_no_gateway
                    DhtDiscoveryStatus.Publishing -> R.string.net_dht_status_publishing
                    DhtDiscoveryStatus.Published -> R.string.net_dht_status_published
                    DhtDiscoveryStatus.Unavailable -> R.string.net_dht_status_unavailable
                }),
                style = MaterialTheme.typography.bodySmall,
                color = if (discoveryStatus == DhtDiscoveryStatus.Unavailable)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            discoveryGateways.forEach { gateway ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(gateway.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val gatewayTest = discoveryGatewayTests[gateway.url]
                        Text(
                            stringResource(when (gatewayTest) {
                                true -> R.string.net_dht_gateway_test_ok
                                false -> R.string.net_dht_gateway_test_failed
                                null -> R.string.net_dht_gateway_untested
                            }),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (gatewayTest == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { vm.testDiscoveryGateway(gateway.url) }) {
                            Text(stringResource(R.string.net_dht_gateway_test))
                        }
                        TextButton(onClick = { vm.removeDiscoveryGateway(gateway.url) }) {
                            Text(stringResource(R.string.net_dht_gateway_remove), color = MaterialTheme.colorScheme.error)
                        }
                        Switch(
                            checked = gateway.enabled,
                            onCheckedChange = { vm.setDiscoveryGatewayEnabled(gateway.url, it) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = discoveryGatewayInput,
                onValueChange = { discoveryGatewayInput = it; discoveryGatewayError = null },
                label = { Text(stringResource(R.string.net_dht_gateway_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            discoveryGatewayError?.let { errorId ->
                Text(stringResource(errorId), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    when (vm.addDiscoveryGateway(discoveryGatewayInput)) {
                        AddRelayResult.Added -> { discoveryGatewayInput = ""; discoveryGatewayError = null }
                        AddRelayResult.Invalid -> discoveryGatewayError = R.string.net_dht_gateway_invalid
                        AddRelayResult.Duplicate -> discoveryGatewayError = R.string.net_dht_gateway_duplicate
                    }
                },
                enabled = discoveryGatewayInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.net_dht_gateway_add)) }
        }

        SectionCard(stringResource(R.string.net_p2p_title)) {
            Text(
                if (lastTransportPath == null) stringResource(R.string.net_transport_no_path)
                else stringResource(R.string.net_transport_last_path, lastTransportPath.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.net_p2p_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = p2pPeerId, onValueChange = { p2pPeerId = it.trim() }, label = { Text(stringResource(R.string.net_p2p_peer_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { vm.lookupDhtPeer(p2pPeerId) },
                enabled = p2pPeerId.isNotBlank() && discoveryGateways.any { it.enabled },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.net_dht_lookup)) }
            discoveredPeer?.takeIf { it.publicId == p2pPeerId.trim().lowercase() }?.let { presence ->
                Text(
                    stringResource(R.string.net_dht_peer_found, presence.expiresAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (incomingInternetOffers.isNotEmpty()) {
                Text(stringResource(R.string.net_p2p_incoming_offer), style = MaterialTheme.typography.titleSmall)
                incomingInternetOffers.values.forEach { offer ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(offer.peerId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.approveIncomingInternetOffer(offer.peerId) },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.net_p2p_approve)) }
                            TextButton(
                                onClick = { vm.declineIncomingInternetOffer(offer.peerId) },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.net_p2p_decline)) }
                        }
                    }
                }
            }
            internetPeerStates.forEach { (peer, state) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(peer, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(when (state) {
                        InternetPeerState.NEW -> R.string.net_p2p_state_new
                        InternetPeerState.CONNECTING -> R.string.net_p2p_state_connecting
                        InternetPeerState.CONNECTED -> R.string.net_p2p_state_connected
                        InternetPeerState.DISCONNECTED -> R.string.net_p2p_state_disconnected
                        InternetPeerState.FAILED -> R.string.net_p2p_state_failed
                    }), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.closeInternetPeer(peer) }) { Text(stringResource(R.string.net_p2p_close)) }
                }
            }
            Button(onClick = { vm.createInternetOffer(p2pPeerId) }, enabled = p2pPeerId.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.net_p2p_create_offer)) }
            OutlinedTextField(value = p2pRemoteSignal, onValueChange = { p2pRemoteSignal = it }, label = { Text(stringResource(R.string.net_p2p_remote_signal)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.acceptInternetOffer(p2pPeerId, p2pRemoteSignal) }, enabled = p2pPeerId.isNotBlank() && p2pRemoteSignal.isNotBlank(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.net_p2p_accept_offer)) }
                Button(onClick = { vm.acceptInternetAnswer(p2pPeerId, p2pRemoteSignal) }, enabled = p2pPeerId.isNotBlank() && p2pRemoteSignal.isNotBlank(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.net_p2p_accept_answer)) }
            }
            if (internetSignal.isNotBlank()) {
                Text(stringResource(R.string.net_p2p_generated), style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(internetSignal, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider()
            Text(stringResource(R.string.net_p2p_turn_optional), style = MaterialTheme.typography.titleSmall)
            TextButton(
                onClick = vm::fetchDhtTurnCredentials,
                enabled = discoveryGateways.any { it.enabled },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.net_dht_turn_fetch)) }
            OutlinedTextField(value = turnUrl, onValueChange = { turnUrl = it; turnError = false }, label = { Text(stringResource(R.string.net_p2p_turn_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = turnUsername, onValueChange = { turnUsername = it; turnError = false }, label = { Text(stringResource(R.string.net_p2p_turn_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = turnCredential, onValueChange = { turnCredential = it; turnError = false }, label = { Text(stringResource(R.string.net_p2p_turn_credential)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            if (turnError) Text(stringResource(R.string.net_p2p_turn_invalid), color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { turnError = !vm.setInternetTurnServer(turnUrl, turnUsername, turnCredential) }) { Text(stringResource(R.string.net_p2p_turn_apply)) }
                TextButton(onClick = { vm.resetInternetIceServers(); turnUrl = ""; turnUsername = ""; turnCredential = ""; turnError = false }) { Text(stringResource(R.string.net_p2p_turn_reset)) }
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

        SectionCard(stringResource(R.string.mesh_title)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.mesh_enable), modifier = Modifier.weight(1f))
                Switch(checked = meshEnabled, onCheckedChange = ::toggleMesh)
            }
            Text(
                stringResource(R.string.mesh_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.mesh_relay_consent), modifier = Modifier.weight(1f))
                Switch(
                    checked = meshRelayEnabled,
                    enabled = meshEnabled,
                    onCheckedChange = vm::setMeshRelayEnabled,
                )
            }
            Text(
                stringResource(R.string.mesh_relay_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoRow(stringResource(R.string.mesh_neighbors), meshStats.neighborCount.toString())
            InfoRow(stringResource(R.string.mesh_queue), meshStats.queuedPackets.toString())
            InfoRow(stringResource(R.string.mesh_storage),
                (meshStats.queuedBytes / (1024 * 1024)).toString() + " MB / " + meshQuotaMb + " MB")
            InfoRow(stringResource(R.string.mesh_expired), meshStats.expiredPackets.toString())
            Text(
                stringResource(
                    when (meshStatus) {
                        org.relyra.app.core.network.BleMeshStatus.Disabled -> R.string.mesh_status_disabled
                        org.relyra.app.core.network.BleMeshStatus.PermissionRequired -> R.string.mesh_status_permission
                        org.relyra.app.core.network.BleMeshStatus.Unsupported -> R.string.mesh_status_unsupported
                        org.relyra.app.core.network.BleMeshStatus.BluetoothOff -> R.string.mesh_status_bluetooth_off
                        org.relyra.app.core.network.BleMeshStatus.Starting -> R.string.mesh_status_starting
                        org.relyra.app.core.network.BleMeshStatus.Scanning -> R.string.mesh_status_scanning
                        org.relyra.app.core.network.BleMeshStatus.Active -> R.string.mesh_status_active
                        org.relyra.app.core.network.BleMeshStatus.Failed -> R.string.mesh_status_failed
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = meshQuotaInput,
                onValueChange = { meshQuotaInput = it.filter(Char::isDigit).take(3) },
                label = { Text(stringResource(R.string.mesh_quota_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val quota = meshQuotaInput.toIntOrNull()
                    if (quota != null && quota in 5..100) vm.setMeshQuotaMb(quota)
                    else meshQuotaInput = meshQuotaMb.toString()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.mesh_quota_save)) }
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
