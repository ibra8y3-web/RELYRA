package org.relyra.app.ui.screens

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.database.VaultItemEntity
import java.text.DateFormat
import java.util.Date

@Composable
fun VaultScreen(
    vm: MainViewModel,
    authenticate: (title: String, allowIfUnavailable: Boolean, onSuccess: () -> Unit) -> Unit,
) {
    val open by vm.vaultUnlocked.collectAsStateWithLifecycle()
    val title = stringResource(R.string.vault_unlock_title)
    if (!open) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.vault_locked), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.vault_locked_desc), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { authenticate(title, false) { vm.vaultUnlocked.value = true } }) {
                Text(stringResource(R.string.vault_unlock))
            }
        }
        return
    }
    VaultContent(vm)
}

@Composable
private fun VaultContent(vm: MainViewModel) {
    val ctx = LocalContext.current
    val files by vm.vaultItems.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<VaultItemEntity?>(null) }
    var renaming by remember { mutableStateOf<VaultItemEntity?>(null) }
    var deleting by remember { mutableStateOf<VaultItemEntity?>(null) }
    var savingId by remember { mutableStateOf<String?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        vm.externalInFlight = false
        if (uri != null) vm.vaultImport(uri)
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        vm.externalInFlight = false
        val id = savingId
        savingId = null
        if (uri != null && id != null) vm.vaultExport(id, uri)
    }

    val shown = if (query.isBlank()) files else files.filter { it.name.contains(query.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.vault_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { vm.externalInFlight = true; picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vault_add))
            }
            if (files.isNotEmpty()) {
                OutlinedTextField(value = query, onValueChange = { query = it.take(80) }, singleLine = true,
                    label = { Text(stringResource(R.string.vault_search)) }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (files.isEmpty()) {
            Text(stringResource(R.string.vault_empty), modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.name) },
                        supportingContent = {
                            Text(Formatter.formatShortFileSize(ctx, item.size) + " · " + fmt.format(Date(item.addedAt)))
                        },
                        modifier = Modifier.clickable { selected = item },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Formatter.formatShortFileSize(ctx, item.size) + " · " + item.mime)
                    Text("SHA-256", style = MaterialTheme.typography.labelSmall)
                    Text(item.plainSha256.joinToString("") { "%02x".format(it) }.take(32) + "…",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        savingId = item.id
                        selected = null
                        vm.externalInFlight = true
                        saver.launch(item.name)
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vault_save_copy)) }
                    OutlinedButton(onClick = { vm.vaultVerify(item.id); selected = null }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.vault_verify))
                    }
                    OutlinedButton(onClick = { renaming = item; selected = null }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.vault_rename))
                    }
                    OutlinedButton(onClick = { deleting = item; selected = null }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.vault_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.vault_close)) } },
        )
    }

    renaming?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.vault_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(120) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.vaultRename(item.id, name); renaming = null }) {
                    Text(stringResource(R.string.vault_rename))
                }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.vault_delete)) },
            text = { Text(stringResource(R.string.vault_delete_confirm, item.name)) },
            confirmButton = {
                TextButton(onClick = { vm.vaultDelete(item.id); deleting = null }) {
                    Text(stringResource(R.string.vault_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
