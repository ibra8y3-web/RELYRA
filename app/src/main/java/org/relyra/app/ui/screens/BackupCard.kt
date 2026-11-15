package org.relyra.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.backup.BackupCrypto
import org.relyra.app.ui.components.SectionCard
import org.relyra.app.ui.components.findActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val pending by vm.restorePending.collectAsStateWithLifecycle()
    var createDialog by remember { mutableStateOf(false) }
    var exportPass by remember { mutableStateOf<CharArray?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.externalInFlight = false
        val p = exportPass
        exportPass = null
        if (uri != null && p != null) vm.exportBackup(uri, p) else p?.fill('\u0000')
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.externalInFlight = false
        if (uri != null) restoreUri = uri
    }

    SectionCard(stringResource(R.string.settings_backup)) {
        Text(stringResource(R.string.backup_desc), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { createDialog = true }) { Text(stringResource(R.string.backup_create)) }
            OutlinedButton(onClick = { vm.externalInFlight = true; picker.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.backup_restore))
            }
        }
    }

    if (createDialog) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        val ok = p1.length >= BackupCrypto.MIN_PASSPHRASE && p1 == p2
        AlertDialog(
            onDismissRequest = { createDialog = false },
            title = { Text(stringResource(R.string.backup_pass_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_pass_warning, BackupCrypto.MIN_PASSPHRASE), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = p1, onValueChange = { p1 = it }, singleLine = true,
                        label = { Text(stringResource(R.string.backup_pass_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    OutlinedTextField(value = p2, onValueChange = { p2 = it }, singleLine = true,
                        label = { Text(stringResource(R.string.backup_pass_again)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = {
                    exportPass = p1.toCharArray()
                    createDialog = false
                    vm.externalInFlight = true
                    saver.launch("relyra-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".rlybk")
                }) { Text(stringResource(R.string.backup_continue)) }
            },
            dismissButton = { TextButton(onClick = { createDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    restoreUri?.let { uri ->
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text(stringResource(R.string.backup_restore)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_restore_warning), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(value = pass, onValueChange = { pass = it }, singleLine = true,
                        label = { Text(stringResource(R.string.backup_pass_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = {
                TextButton(enabled = pass.isNotEmpty(), onClick = {
                    vm.stageRestore(uri, pass.toCharArray())
                    restoreUri = null
                }) { Text(stringResource(R.string.backup_verify)) }
            },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (pending) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.backup_ready_title)) },
            text = { Text(stringResource(R.string.backup_ready_body)) },
            confirmButton = {
                TextButton(onClick = {
                    ctx.findActivity()?.finishAffinity()
                    kotlin.system.exitProcess(0)
                }) { Text(stringResource(R.string.backup_close_app)) }
            },
            dismissButton = { TextButton(onClick = { vm.cancelRestore() }) { Text(stringResource(R.string.backup_cancel_restore)) } },
        )
    }
}
