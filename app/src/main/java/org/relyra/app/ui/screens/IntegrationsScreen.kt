package org.relyra.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.integrations.PluginCommand
import org.relyra.app.core.integrations.PluginInfo
import org.relyra.app.ui.components.SectionCard

@Composable
fun IntegrationsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val api by vm.localApi.state.collectAsStateWithLifecycle()
    val plugins by vm.plugins.installed.collectAsStateWithLifecycle()
    val pluginEvents by vm.plugins.events.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var exposeIdentity by remember(api.exposesIdentity) { mutableStateOf(api.exposesIdentity) }
    var allowMessaging by remember(api.allowsMessaging) { mutableStateOf(api.allowsMessaging) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPlugin by remember { mutableStateOf<PluginInfo?>(null) }
    var inspectError by remember { mutableStateOf<String?>(null) }
    var matrixServer by remember { mutableStateOf("https://matrix.org") }
    var matrixUser by remember { mutableStateOf("") }
    var matrixPassword by remember { mutableStateOf("") }
    var matrixRoom by remember { mutableStateOf("") }
    var matrixText by remember { mutableStateOf("") }
    val disconnectedText = stringResource(R.string.integrations_status_disconnected)
    var matrixStatus by remember { mutableStateOf(disconnectedText) }
    var xmppDomain by remember { mutableStateOf("") }
    var xmppHost by remember { mutableStateOf("") }
    var xmppPort by remember { mutableStateOf("5222") }
    var xmppUser by remember { mutableStateOf("") }
    var xmppPassword by remember { mutableStateOf("") }
    var xmppRecipient by remember { mutableStateOf("") }
    var xmppText by remember { mutableStateOf("") }
    var xmppStatus by remember { mutableStateOf(disconnectedText) }
    var xmppDeviceJid by remember { mutableStateOf("") }
    var xmppFingerprints by remember { mutableStateOf<List<org.relyra.app.core.integrations.XmppOmemoFingerprint>>(emptyList()) }
    val xmppIncoming by vm.xmpp.incoming.collectAsStateWithLifecycle()
    var ipfsApi by remember(vm.ipfs.endpoint.value) { mutableStateOf(vm.ipfs.endpoint.value) }
    var ipfsCid by remember { mutableStateOf("") }
    var ipfsStatus by remember { mutableStateOf("") }

    val pluginPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                pendingUri = uri
                val inspected = vm.plugins.inspect(context.contentResolver, uri)
                if (inspected == null) {
                    inspectError = context.getString(R.string.integrations_plugin_invalid)
                    pendingUri = null
                } else {
                    pendingPlugin = inspected
                    inspectError = null
                }
            }
        }
    }

    val ipfsSavePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            ipfsStatus = context.getString(R.string.integrations_ipfs_downloading)
            runCatching {
                withContext(Dispatchers.IO) {
                    val target = File(context.cacheDir, "${ipfsCid}.enc")
                    try {
                        vm.ipfs.fetchCiphertext(ipfsApi, ipfsCid, target)
                        val output = context.contentResolver.openOutputStream(uri) ?: error(context.getString(R.string.integrations_cannot_open_destination))
                        output.use { out -> target.inputStream().use { it.copyTo(out) } }
                    } finally { target.delete() }
                }
            }.onSuccess { ipfsStatus = context.getString(R.string.integrations_ipfs_saved) }
             .onFailure { ipfsStatus = it.message ?: context.getString(R.string.integrations_ipfs_download_failed) }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(stringResource(R.string.integrations_local_api)) {
            Text(stringResource(R.string.integrations_local_api_desc), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ListItem(
                headlineContent = { Text(stringResource(R.string.integrations_api_identity_scope)) },
                supportingContent = { Text(stringResource(R.string.integrations_api_identity_scope_desc)) },
                trailingContent = { Switch(checked = exposeIdentity, onCheckedChange = { exposeIdentity = it }) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.integrations_api_messages_scope)) },
                supportingContent = { Text(stringResource(R.string.integrations_api_messages_scope_desc)) },
                trailingContent = { Switch(checked = allowMessaging, onCheckedChange = { allowMessaging = it }) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (api.running) vm.localApi.stop() else vm.localApi.start(exposeIdentity, allowMessaging)
                }) {
                    Text(stringResource(if (api.running) R.string.integrations_api_stop else R.string.integrations_api_start))
                }
                if (api.running) {
                    OutlinedButton(onClick = { vm.localApi.start(exposeIdentity, allowMessaging) }) {
                        Text(stringResource(R.string.integrations_api_rotate))
                    }
                }
            }
            if (api.running) {
                HorizontalDivider()
                Text(stringResource(R.string.integrations_api_endpoint, api.port ?: 0), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.integrations_api_token), style = MaterialTheme.typography.labelLarge)
                Text(api.token.orEmpty(), style = MaterialTheme.typography.bodySmall)
                api.expiresAtMillis?.let { expiry ->
                    Text(
                        stringResource(
                            R.string.integrations_api_expires,
                            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(expiry)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = {
                    api.token?.let { clipboard.setText(AnnotatedString(it)) }
                }) { Text(stringResource(R.string.integrations_api_copy_token)) }
                Text(stringResource(R.string.integrations_api_example), style = MaterialTheme.typography.bodySmall)
                Text(
                    "curl -H \"Authorization: Bearer ${api.token.orEmpty()}\" http://127.0.0.1:${api.port ?: 0}/v1/health",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (api.exposesIdentity) {
                    Text(stringResource(R.string.integrations_api_identity_enabled), color = MaterialTheme.colorScheme.tertiary)
                }
                if (api.allowsMessaging) {
                    Text(stringResource(R.string.integrations_api_messages_enabled), color = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.integrations_api_send_example), style = MaterialTheme.typography.bodySmall)
                    Text(
                        "curl -X POST -H \"Authorization: Bearer ${api.token.orEmpty()}\" -H \"Content-Type: application/json\" " +
                            "-d '{\"to\":\"CONTACT_PUBLIC_ID\",\"text\":\"hello\"}' http://127.0.0.1:${api.port ?: 0}/v1/messages",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            api.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.integrations_api_security), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(stringResource(R.string.integrations_plugins)) {
            Text(stringResource(R.string.integrations_plugins_desc), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { pluginPicker.launch(arrayOf("application/json", "text/*", "*/*")) }) {
                Text(stringResource(R.string.integrations_plugin_import))
            }
            inspectError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (plugins.isEmpty()) {
                Text(stringResource(R.string.integrations_plugin_empty), style = MaterialTheme.typography.bodySmall)
            }
            plugins.forEach { plugin ->
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(plugin.name) },
                    supportingContent = { Text("${plugin.id} · ${plugin.version}\n${plugin.description}") },
                    trailingContent = {
                        Switch(checked = plugin.enabled, onCheckedChange = { vm.plugins.setEnabled(plugin.id, it) })
                    },
                )
                Text(
                    stringResource(R.string.integrations_plugin_permissions,
                        if (plugin.grantedPermissions.isEmpty()) stringResource(R.string.integrations_plugin_no_permissions)
                        else plugin.grantedPermissions.joinToString()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                plugin.actions.forEach { action ->
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                when (val command = runCatching { vm.plugins.runActionIsolated(context, plugin.id, action.id) }.getOrNull()) {
                                    is PluginCommand.Notice -> snackbar.showSnackbar("${command.title}: ${command.text}")
                                    is PluginCommand.CopyText -> {
                                        clipboard.setText(AnnotatedString(command.text))
                                        snackbar.showSnackbar(context.getString(R.string.integrations_plugin_copied))
                                    }
                                    is PluginCommand.OpenUrl -> {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(command.url)))
                                        }.onFailure {
                                            snackbar.showSnackbar(context.getString(R.string.integrations_plugin_open_failed))
                                        }
                                    }
                                    null -> snackbar.showSnackbar(context.getString(R.string.integrations_plugin_not_enabled))
                                }
                            }
                        },
                        enabled = plugin.enabled,
                    ) { Text(action.title) }
                }
                TextButton(onClick = { vm.plugins.remove(plugin.id) }) {
                    Text(stringResource(R.string.integrations_plugin_remove))
                }
            }
        }

        SectionCard(stringResource(R.string.integrations_plugin_activity)) {
            if (pluginEvents.isEmpty()) {
                Text(stringResource(R.string.integrations_plugin_activity_empty), style = MaterialTheme.typography.bodySmall)
            } else {
                pluginEvents.take(30).forEach { event ->
                    ListItem(
                        headlineContent = { Text("${event.pluginId} · ${event.event}") },
                        supportingContent = {
                            Text(
                                (event.actionId.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") +
                                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(event.timestamp))
                            )
                        },
                    )
                }
            }
        }


        SectionCard("Matrix") {
            Text(stringResource(R.string.integrations_matrix_e2ee_note), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(matrixServer, { matrixServer = it }, label = { Text(stringResource(R.string.integrations_homeserver_url)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(matrixUser, { matrixUser = it }, label = { Text(stringResource(R.string.integrations_user_id)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(matrixPassword, { matrixPassword = it }, label = { Text(stringResource(R.string.integrations_password)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { runCatching { vm.matrixE2ee.login(matrixServer, matrixUser, matrixPassword) }.onSuccess { matrixStatus = context.getString(R.string.integrations_matrix_signed_in,it) }.onFailure { matrixStatus = it.message ?: context.getString(R.string.integrations_login_failed) } } }) { Text(stringResource(R.string.integrations_matrix_sign_in)) }
                OutlinedButton(onClick = { scope.launch { runCatching { vm.matrixE2ee.restoreSavedSession() }.onSuccess { matrixStatus = context.getString(R.string.integrations_restored_session,it) }.onFailure { matrixStatus = it.message ?: context.getString(R.string.integrations_restore_failed) } } }) { Text(stringResource(R.string.integrations_matrix_restore)) }
                OutlinedButton(onClick = { scope.launch { runCatching { vm.matrixE2ee.startSync() }.onSuccess { matrixStatus = context.getString(R.string.integrations_matrix_sync_started) }.onFailure { matrixStatus = it.message ?: context.getString(R.string.integrations_sync_failed) } } }) { Text(stringResource(R.string.integrations_sync)) }
            }
            OutlinedTextField(matrixRoom, { matrixRoom = it }, label = { Text(stringResource(R.string.integrations_room_id)) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { scope.launch { runCatching { vm.matrixE2ee.joinRoom(matrixRoom) }.onSuccess { matrixRoom = it; matrixStatus = context.getString(R.string.integrations_joined_room,it) }.onFailure { matrixStatus = it.message ?: context.getString(R.string.integrations_join_failed) } } }, enabled = matrixRoom.isNotBlank()) { Text(stringResource(R.string.integrations_join_room)) }
            OutlinedTextField(matrixText, { matrixText = it }, label = { Text(stringResource(R.string.integrations_e2ee_message)) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { scope.launch { runCatching { vm.matrixE2ee.sendEncrypted(matrixRoom, matrixText) }.onSuccess { matrixStatus = context.getString(R.string.integrations_encrypted_sent); matrixText = "" }.onFailure { matrixStatus = it.message ?: context.getString(R.string.integrations_encrypted_send_failed) } } }, enabled = matrixRoom.startsWith("!") && matrixText.isNotBlank()) { Text(stringResource(R.string.integrations_send_encrypted_message)) }
            Text(matrixStatus, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("XMPP / OMEMO") {
            OutlinedTextField(xmppDomain, { xmppDomain = it }, label = { Text(stringResource(R.string.integrations_xmpp_domain)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(xmppHost, { xmppHost = it }, label = { Text(stringResource(R.string.integrations_server_host)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(xmppPort, { xmppPort = it }, label = { Text(stringResource(R.string.integrations_port)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(xmppUser, { xmppUser = it }, label = { Text(stringResource(R.string.integrations_username)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(xmppPassword, { xmppPassword = it }, label = { Text(stringResource(R.string.integrations_password)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { vm.xmpp.connect(xmppDomain, xmppHost, xmppPort.toInt(), xmppUser, xmppPassword) } }.onSuccess { xmppStatus = context.getString(R.string.integrations_xmpp_connected_as,it) }.onFailure { xmppStatus = it.message ?: context.getString(R.string.integrations_connection_failed) } } }) { Text(stringResource(R.string.integrations_connect)) }
                OutlinedButton(onClick = { vm.xmpp.disconnect(); xmppStatus = disconnectedText }) { Text(stringResource(R.string.integrations_disconnect)) }
            }
            OutlinedTextField(xmppRecipient, { xmppRecipient = it }, label = { Text(stringResource(R.string.integrations_recipient_jid)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(xmppText, { xmppText = it }, label = { Text(stringResource(R.string.integrations_omemo_message)) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { vm.xmpp.sendEncrypted(xmppRecipient, xmppText) } }.onSuccess { xmppStatus = context.getString(R.string.integrations_encrypted_sent); xmppText = "" }.onFailure { xmppStatus = it.message ?: context.getString(R.string.integrations_omemo_send_failed) } } }, enabled = xmppRecipient.isNotBlank() && xmppText.isNotBlank()) { Text(stringResource(R.string.integrations_send_encrypted)) }
            OutlinedTextField(xmppDeviceJid, { xmppDeviceJid = it }, label = { Text(stringResource(R.string.integrations_contact_jid_verify)) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { vm.xmpp.knownDevices(xmppDeviceJid) } }.onSuccess { xmppFingerprints = it; xmppStatus = context.getString(R.string.integrations_xmpp_devices_loaded,it.size) }.onFailure { xmppStatus = it.message ?: context.getString(R.string.integrations_device_lookup_failed) } } }, enabled = xmppDeviceJid.isNotBlank()) { Text(stringResource(R.string.integrations_refresh_fingerprints)) }
            xmppFingerprints.forEach { device ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.integrations_device_line,device.device,device.fingerprint), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(if (device.trusted) R.string.integrations_trusted else R.string.integrations_not_trusted), color = if (device.trusted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { vm.xmpp.setDeviceTrust(xmppDeviceJid, device.fingerprint, true) }; vm.xmpp.knownDevices(xmppDeviceJid) }.onSuccess { xmppFingerprints = it }.onFailure { xmppStatus = it.message ?: context.getString(R.string.integrations_trust_failed) } } }) { Text(stringResource(R.string.integrations_trust_after_verify)) }
                        OutlinedButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { vm.xmpp.setDeviceTrust(xmppDeviceJid, device.fingerprint, false) }; vm.xmpp.knownDevices(xmppDeviceJid) }.onSuccess { xmppFingerprints = it }.onFailure { xmppStatus = it.message ?: context.getString(R.string.integrations_distrust_failed) } } }) { Text(stringResource(R.string.integrations_distrust)) }
                    }
                }
            }
            Text(stringResource(R.string.integrations_xmpp_trust_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

            Text(stringResource(R.string.integrations_omemo_received_title), style = MaterialTheme.typography.titleSmall)
            xmppIncoming.takeLast(30).forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.from, style = MaterialTheme.typography.labelSmall)
                    Text(item.body, style = MaterialTheme.typography.bodyMedium)
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.receivedAt)), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(xmppStatus, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard(stringResource(R.string.integrations_ipfs_title)) {
            Text(stringResource(R.string.integrations_ipfs_note), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(ipfsApi, { ipfsApi = it }, label = { Text(stringResource(R.string.integrations_kubo_url)) }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runCatching { vm.ipfs.configureEndpoint(ipfsApi); ipfsStatus = context.getString(R.string.integrations_ipfs_endpoint_saved) }.onFailure { ipfsStatus = it.message ?: context.getString(R.string.integrations_invalid_endpoint) } }, enabled = ipfsApi.startsWith("https://")) { Text(stringResource(R.string.integrations_save_endpoint)) }
                OutlinedButton(onClick = { vm.ipfs.configureEndpoint(""); ipfsApi = ""; ipfsStatus = context.getString(R.string.integrations_ipfs_disabled) }) { Text(stringResource(R.string.integrations_disable_ipfs)) }
            }
            OutlinedTextField(ipfsCid, { ipfsCid = it }, label = { Text("CIDv1") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { ipfsSavePicker.launch("${ipfsCid}.enc") }, enabled = ipfsApi.startsWith("https://") && ipfsCid.startsWith("b")) { Text(stringResource(R.string.integrations_download_ciphertext)) }
            Text(ipfsStatus, style = MaterialTheme.typography.bodySmall)
        }

        Text(stringResource(R.string.integrations_phase7_limitations), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SnackbarHost(hostState = snackbar)
    }

    val review = pendingPlugin
    if (review != null) {
        AlertDialog(
            onDismissRequest = { pendingPlugin = null; pendingUri = null },
            title = { Text(stringResource(R.string.integrations_plugin_review, review.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(review.description)
                    Text(stringResource(R.string.integrations_plugin_requested_permissions))
                    if (review.requestedPermissions.isEmpty()) {
                        Text(stringResource(R.string.integrations_plugin_no_permissions))
                    } else {
                        review.requestedPermissions.forEach { permission -> Text("• $permission") }
                    }
                    Text(stringResource(R.string.integrations_plugin_trust_warning),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingUri
                    if (uri != null) scope.launch {
                        when (val result = vm.plugins.install(context.contentResolver, uri, review.requestedPermissions.toSet())) {
                            is org.relyra.app.core.integrations.PluginInstallResult.Installed ->
                                snackbar.showSnackbar(context.getString(R.string.integrations_plugin_installed))
                            is org.relyra.app.core.integrations.PluginInstallResult.Rejected ->
                                snackbar.showSnackbar(result.reason)
                        }
                    }
                    pendingPlugin = null
                    pendingUri = null
                }) { Text(stringResource(R.string.integrations_plugin_approve_install)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPlugin = null; pendingUri = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
