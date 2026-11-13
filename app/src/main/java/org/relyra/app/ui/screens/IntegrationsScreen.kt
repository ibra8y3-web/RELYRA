package org.relyra.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPlugin by remember { mutableStateOf<PluginInfo?>(null) }
    var inspectError by remember { mutableStateOf<String?>(null) }
    var matrixServer by remember { mutableStateOf("https://matrix.org") }
    var matrixUser by remember { mutableStateOf("") }
    var matrixPassword by remember { mutableStateOf("") }
    var matrixRoom by remember { mutableStateOf("") }
    var matrixText by remember { mutableStateOf("") }
    var matrixStatus by remember { mutableStateOf("Disconnected") }
    var xmppDomain by remember { mutableStateOf("") }
    var xmppHost by remember { mutableStateOf("") }
    var xmppPort by remember { mutableStateOf("5222") }
    var xmppUser by remember { mutableStateOf("") }
    var xmppPassword by remember { mutableStateOf("") }
    var xmppRecipient by remember { mutableStateOf("") }
    var xmppText by remember { mutableStateOf("") }
    var xmppStatus by remember { mutableStateOf("Disconnected") }\n    var xmppDeviceJid by remember { mutableStateOf("") }\n    var xmppFingerprints by remember { mutableStateOf<List<org.relyra.app.core.integrations.XmppOmemoFingerprint>>(emptyList()) }
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

    val ipfsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            ipfsStatus = "Uploading encrypted file…"
            runCatching {
                withContext(Dispatchers.IO) {
                    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
                    }.orEmpty()
                    require(displayName.endsWith(".enc")) { "Only RELYRA .enc ciphertext files can be uploaded" }
                    val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open selected file")
                    val temp = File(context.cacheDir, "ipfs-${System.nanoTime()}.enc")
                    input.use { source -> temp.outputStream().use { source.copyTo(it) } }
                    try { vm.ipfs.addCiphertext(ipfsApi, temp) } finally { temp.delete() }
                }
            }.onSuccess { ipfsCid = it; ipfsStatus = "Pinned ciphertext: $it" }
             .onFailure { ipfsStatus = it.message ?: "IPFS upload failed" }
        }
    }
    val ipfsSavePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            ipfsStatus = "Downloading encrypted object…"
            runCatching {
                withContext(Dispatchers.IO) {
                    val target = File(context.cacheDir, "${ipfsCid}.enc")
                    try {
                        vm.ipfs.fetchCiphertext(ipfsApi, ipfsCid, target)
                        val output = context.contentResolver.openOutputStream(uri) ?: error("Cannot open destination")
                        output.use { out -> target.inputStream().use { it.copyTo(out) } }
                    } finally { target.delete() }
                }
            }.onSuccess { ipfsStatus = "Encrypted object saved" }
             .onFailure { ipfsStatus = it.message ?: "IPFS download failed" }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (api.running) vm.localApi.stop() else vm.localApi.start(exposeIdentity)
                }) {
                    Text(stringResource(if (api.running) R.string.integrations_api_stop else R.string.integrations_api_start))
                }
                if (api.running) {
                    OutlinedButton(onClick = { vm.localApi.start(exposeIdentity) }) {
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
            Text("Matrix Rust SDK handles Olm/Megolm, device keys and encrypted room events. RELYRA blocks sending to rooms without E2EE enabled.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(matrixServer, { matrixServer = it }, label = { Text("Homeserver HTTPS URL") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(matrixUser, { matrixUser = it }, label = { Text("User ID") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(matrixPassword, { matrixPassword = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { runCatching { vm.matrixE2ee.login(matrixServer, matrixUser, matrixPassword) }.onSuccess { matrixStatus = "Matrix Rust SDK E2EE signed in: $it" }.onFailure { matrixStatus = it.message ?: "Login failed" } } }) { Text("Sign in with E2EE") }
                OutlinedButton(onClick = { scope.launch { runCatching { vm.matrixE2ee.restoreSavedSession() }.onSuccess { matrixStatus = "Restored E2EE session: $it" }.onFailure { matrixStatus = it.message ?: "Restore failed" } } }) { Text("Restore session") }
                OutlinedButton(onClick = { scope.launch { runCatching { vm.matrixE2ee.startSync() }.onSuccess { matrixStatus = "Encrypted Matrix sync started" }.onFailure { matrixStatus = it.message ?: "Sync failed" } } }) { Text("Sync") }
            }
            OutlinedTextField(matrixRoom, { matrixRoom = it }, label = { Text("Room ID or alias") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { scope.launch { runCatching { vm.matrixE2ee.joinRoom(matrixRoom) }.onSuccess { matrixRoom = it; matrixStatus = "Joined room: $it" }.onFailure { matrixStatus = it.message ?: "Join failed" } } }, enabled = matrixRoom.isNotBlank()) { Text("Join room") }
            OutlinedTextField(matrixText, { matrixText = it }, label = { Text("E2EE message") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { scope.launch { runCatching { vm.matrixE2ee.sendEncrypted(matrixRoom, matrixText) }.onSuccess { matrixStatus = "Encrypted message sent"; matrixText = "" }.onFailure { matrixStatus = it.message ?: "Encrypted send failed" } } }, enabled = matrixRoom.startsWith("!") && matrixText.isNotBlank()) { Text("Send encrypted message") }
            Text(matrixStatus, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("XMPP / OMEMO") {
            OutlinedTextField(xmppDomain, { xmppDomain = it }, label = { Text("XMPP domain") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(xmppHost, { xmppHost = it }, label = { Text("Server host") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(xmppPort, { xmppPort = it }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(xmppUser, { xmppUser = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(xmppPassword, { xmppPassword = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { vm.xmpp.connect(xmppDomain, xmppHost, xmppPort.toInt(), xmppUser, xmppPassword) } }.onSuccess { xmppStatus = "Connected as $it" }.onFailure { xmppStatus = it.message ?: "Connection failed" } } }) { Text("Connect") }
                OutlinedButton(onClick = { vm.xmpp.disconnect(); xmppStatus = "Disconnected" }) { Text("Disconnect") }
            }
            OutlinedTextField(xmppRecipient, { xmppRecipient = it }, label = { Text("Recipient JID") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(xmppText, { xmppText = it }, label = { Text("OMEMO message") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { vm.xmpp.sendEncrypted(xmppRecipient, xmppText) } }.onSuccess { xmppStatus = "Encrypted message sent"; xmppText = "" }.onFailure { xmppStatus = it.message ?: "OMEMO send failed" } } }, enabled = xmppRecipient.isNotBlank() && xmppText.isNotBlank()) { Text("Send encrypted") }
            OutlinedTextField(xmppDeviceJid, { xmppDeviceJid = it }, label = { Text("Contact JID for device verification") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { vm.xmpp.knownDevices(xmppDeviceJid) } }.onSuccess { xmppFingerprints = it; xmppStatus = "Loaded ${it.size} OMEMO devices" }.onFailure { xmppStatus = it.message ?: "Device lookup failed" } } }, enabled = xmppDeviceJid.isNotBlank()) { Text("Refresh device fingerprints") }
            xmppFingerprints.forEach { device ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Device ${device.device} · ${device.fingerprint}", style = MaterialTheme.typography.bodySmall)
                    Text(if (device.trusted) "Trusted" else "Not trusted", color = if (device.trusted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { vm.xmpp.setDeviceTrust(xmppDeviceJid, device.fingerprint, true) }; vm.xmpp.knownDevices(xmppDeviceJid) }.onSuccess { xmppFingerprints = it }.onFailure { xmppStatus = it.message ?: "Trust failed" } } }) { Text("Trust after verification") }
                        OutlinedButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { vm.xmpp.setDeviceTrust(xmppDeviceJid, device.fingerprint, false) }; vm.xmpp.knownDevices(xmppDeviceJid) }.onSuccess { xmppFingerprints = it }.onFailure { xmppStatus = it.message ?: "Distrust failed" } } }) { Text("Distrust") }
                    }
                }
            }
            Text("Trust fingerprints must be verified out-of-band. OMEMO key storage is not yet Keystore-backed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

            Text("Received decrypted OMEMO messages", style = MaterialTheme.typography.titleSmall)
            xmppIncoming.takeLast(30).forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.from, style = MaterialTheme.typography.labelSmall)
                    Text(item.body, style = MaterialTheme.typography.bodyMedium)
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.receivedAt)), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(xmppStatus, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("IPFS — encrypted attachments") {
            Text("Kubo RPC over HTTPS only. Select a RELYRA .enc ciphertext file; plaintext files are rejected.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(ipfsApi, { ipfsApi = it }, label = { Text("Kubo HTTPS API URL") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runCatching { vm.ipfs.configureEndpoint(ipfsApi); ipfsStatus = "Kubo endpoint saved; new attachments will use IPFS" }.onFailure { ipfsStatus = it.message ?: "Invalid endpoint" } }, enabled = ipfsApi.startsWith("https://")) { Text("Save endpoint") }
                OutlinedButton(onClick = { vm.ipfs.configureEndpoint(""); ipfsApi = ""; ipfsStatus = "IPFS disabled; Blossom remains active" }) { Text("Disable IPFS") }
            }
            Button(onClick = { ipfsPicker.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = ipfsApi.startsWith("https://")) { Text("Upload encrypted .enc file") }
            OutlinedTextField(ipfsCid, { ipfsCid = it }, label = { Text("CIDv1") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { ipfsSavePicker.launch("${ipfsCid}.enc") }, enabled = ipfsApi.startsWith("https://") && ipfsCid.startsWith("b")) { Text("Download ciphertext") }
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
