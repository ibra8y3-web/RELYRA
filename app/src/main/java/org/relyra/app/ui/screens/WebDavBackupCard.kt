package org.relyra.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.backup.BackupCrypto
import org.relyra.app.ui.components.SectionCard

@Composable
fun WebDavBackupCard(vm: MainViewModel) {
    val cfg by vm.webDavConfig.collectAsStateWithLifecycle()
    val files by vm.webDavFiles.collectAsStateWithLifecycle()
    val hasPassword by vm.webDavHasPassword.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadWebDav() }

    var url by remember(cfg) { mutableStateOf(cfg.url) }
    var user by remember(cfg) { mutableStateOf(cfg.user) }
    var pass by remember(cfg) { mutableStateOf("") }
    var folder by remember(cfg) { mutableStateOf(cfg.folder) }
    var allowHttp by remember(cfg) { mutableStateOf(cfg.allowHttp) }
    var uploadDialog by remember { mutableStateOf(false) }
    var restoreName by remember { mutableStateOf<String?>(null) }

    SectionCard(stringResource(R.string.webdav_title)) {
        Text(stringResource(R.string.webdav_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.webdav_url)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(user, { user = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.webdav_user)) })
            OutlinedTextField(pass, { pass = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(if (hasPassword) R.string.webdav_pass_keep else R.string.webdav_pass)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(folder, { folder = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.webdav_folder)) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.webdav_allow_http))
                    Text(stringResource(R.string.webdav_allow_http_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(allowHttp, { allowHttp = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.saveWebDav(url, user, pass, folder, allowHttp); pass = "" }) { Text(stringResource(R.string.webdav_save)) }
                OutlinedButton(onClick = { vm.webDavTest() }, enabled = cfg.configured) { Text(stringResource(R.string.webdav_test)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { uploadDialog = true }, enabled = cfg.configured) { Text(stringResource(R.string.webdav_backup_now)) }
                OutlinedButton(onClick = { vm.webDavRefresh() }, enabled = cfg.configured) { Text(stringResource(R.string.webdav_refresh)) }
            }
            files.forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                        Text(android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, f.size),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { restoreName = f.name }) { Text(stringResource(R.string.backup_restore)) }
                }
            }
        }
    }

    if (uploadDialog) {
        PassphraseDialog(title = R.string.backup_pass_title, confirmTwice = true, onDismiss = { uploadDialog = false }) {
            uploadDialog = false
            vm.webDavBackup(it)
        }
    }
    restoreName?.let { name ->
        PassphraseDialog(title = R.string.backup_restore, confirmTwice = false, warning = R.string.backup_restore_warning, onDismiss = { restoreName = null }) {
            restoreName = null
            vm.webDavRestore(name, it)
        }
    }
}

@Composable
private fun PassphraseDialog(title: Int, confirmTwice: Boolean, warning: Int? = null, onDismiss: () -> Unit, onOk: (CharArray) -> Unit) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    val ok = if (confirmTwice) p1.length >= BackupCrypto.MIN_PASSPHRASE && p1 == p2 else p1.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirmTwice) Text(stringResource(R.string.backup_pass_warning, BackupCrypto.MIN_PASSPHRASE), style = MaterialTheme.typography.bodySmall)
                if (warning != null) Text(stringResource(warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(p1, { p1 = it }, singleLine = true, label = { Text(stringResource(R.string.backup_pass_hint)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (confirmTwice) OutlinedTextField(p2, { p2 = it }, singleLine = true, label = { Text(stringResource(R.string.backup_pass_again)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onOk(p1.toCharArray()) }) { Text(stringResource(R.string.backup_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
