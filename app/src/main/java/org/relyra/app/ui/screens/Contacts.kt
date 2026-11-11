package org.relyra.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.UiMessage
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.database.ContactEntity
import org.relyra.app.core.identity.AddResult
import org.relyra.app.ui.components.Avatar
import org.relyra.app.ui.components.InfoRow

@Composable
fun ContactsScreen(vm: MainViewModel, onAdd: () -> Unit) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val visibleContacts = contacts.filter { query.isBlank() || it.displayName.contains(query, true) || it.publicId.contains(query, true) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it.take(80) },
            placeholder = { Text(stringResource(R.string.contacts_search_hint)) },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
        if (contacts.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.contacts_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.contacts_empty_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp, top = 4.dp)) {
                if (visibleContacts.isEmpty()) {
                    item { Text(stringResource(R.string.chats_no_match), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
                }
                items(visibleContacts, key = { it.publicId }) { c ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { selectedId = c.publicId },
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        ListItem(
                            headlineContent = { Text(c.displayName, maxLines = 1) },
                            supportingContent = { Text(stringResource(statusRes(c))) },
                            leadingContent = { Avatar(c.displayName, 44.dp) },
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.contacts_add)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
        }
    }

    contacts.find { it.publicId == selectedId }?.let { ContactDialog(it, vm) { selectedId = null } }
}

private fun statusRes(c: ContactEntity): Int = when {
    c.blocked -> R.string.status_blocked
    c.verified -> R.string.status_verified
    else -> R.string.status_unverified
}

@Composable
private fun ContactDialog(c: ContactEntity, vm: MainViewModel, onDismiss: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.displayName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow(stringResource(R.string.label_status), stringResource(statusRes(c)))
                InfoRow(
                    stringResource(R.string.label_encryption),
                    stringResource(if (c.encKey != null && c.nostrKey != null) R.string.enc_ready else R.string.enc_missing),
                )
                InfoRow(
                    stringResource(R.string.label_card),
                    stringResource(if (c.cardSigned) R.string.card_signed else R.string.card_unsigned),
                )
                Text(stringResource(R.string.label_fingerprint), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(IdentityCrypto.fingerprint(c.publicKey), fontFamily = FontFamily.Monospace)
                }
                Text(stringResource(R.string.fingerprint_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.label_public_id), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(c.publicId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { vm.setVerified(c, !c.verified) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (c.verified) R.string.action_unverify else R.string.action_verify))
                }
                OutlinedButton(onClick = { vm.setBlocked(c, !c.blocked) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (c.blocked) R.string.action_unblock else R.string.action_block))
                }
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_contact_title)) },
            text = { Text(stringResource(R.string.delete_contact_body, c.displayName)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteContact(c); confirmDelete = false; onDismiss() }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun AddContactScreen(vm: MainViewModel, onDone: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val incoming by vm.incomingLink.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(incoming) {
        incoming?.let { input = it; vm.incomingLink.value = null }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { input = it; error = null }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.add_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = {
                vm.externalInFlight = true
                scanner.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("")
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.add_scan)) }
        OutlinedButton(
            onClick = { clipboard.getText()?.text?.let { input = it; error = null } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.add_paste)) }
        OutlinedTextField(
            value = input, onValueChange = { input = it; error = null },
            label = { Text(stringResource(R.string.add_field_input)) },
            minLines = 2, modifier = Modifier.fillMaxWidth(),
        )
        if (input.isNotBlank() && !input.trim().startsWith("relyra://")) {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(64) },
                label = { Text(stringResource(R.string.field_contact_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.add_unsigned_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                vm.addContact(input, name) { result ->
                    when (result) {
                        is AddResult.Added -> {
                            vm.message.value = UiMessage(R.string.contact_added, listOf(result.name))
                            onDone()
                        }
                        is AddResult.Updated -> {
                            vm.message.value = UiMessage(R.string.contact_updated, listOf(result.name))
                            onDone()
                        }
                        AddResult.Malformed -> error = R.string.err_add_malformed
                        AddResult.BadSignature -> error = R.string.err_add_signature
                        AddResult.IsSelf -> error = R.string.err_add_self
                        AddResult.AlreadyExists -> error = R.string.err_add_exists
                        AddResult.NameRequired -> error = R.string.err_add_name
                    }
                }
            },
            enabled = input.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.add_action)) }
    }
}
