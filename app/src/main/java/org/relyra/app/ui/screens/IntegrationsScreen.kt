package org.relyra.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    val snackbar = remember { SnackbarHostState() }
    var exposeIdentity by remember(api.exposesIdentity) { mutableStateOf(api.exposesIdentity) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPlugin by remember { mutableStateOf<PluginInfo?>(null) }
    var inspectError by remember { mutableStateOf<String?>(null) }

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
                            when (val command = vm.plugins.runAction(plugin.id, action.id)) {
                                is PluginCommand.Notice -> scope.launch { snackbar.showSnackbar("${command.title}: ${command.text}") }
                                is PluginCommand.CopyText -> {
                                    clipboard.setText(AnnotatedString(command.text))
                                    scope.launch { snackbar.showSnackbar(context.getString(R.string.integrations_plugin_copied)) }
                                }
                                is PluginCommand.OpenUrl -> {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(command.url)))
                                    }.onFailure {
                                        scope.launch { snackbar.showSnackbar(context.getString(R.string.integrations_plugin_open_failed)) }
                                    }
                                }
                                null -> scope.launch { snackbar.showSnackbar(context.getString(R.string.integrations_plugin_not_enabled)) }
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
