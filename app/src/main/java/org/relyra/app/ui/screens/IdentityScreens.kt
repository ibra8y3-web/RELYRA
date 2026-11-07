package org.relyra.app.ui.screens

import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.relyra.app.IdentityState
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.ui.components.QrImage
import org.relyra.app.ui.components.SectionCard
import org.relyra.app.ui.components.findActivity

@Composable
fun IdentityScreen(vm: MainViewModel, onRecovery: () -> Unit) {
    val identity by vm.identity.collectAsStateWithLifecycle()
    val card by vm.myCard.collectAsStateWithLifecycle()
    val me = (identity as? IdentityState.Present)?.identity ?: return
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(me.displayName, style = MaterialTheme.typography.headlineSmall)
        val link = card
        if (link == null) CircularProgressIndicator() else QrImage(link)
        Text(
            stringResource(R.string.identity_qr_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                link ?: return@Button
                vm.externalInFlight = true
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link)
                }
                ctx.startActivity(Intent.createChooser(send, null))
            },
            enabled = link != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.identity_share)) }

        SectionCard(stringResource(R.string.label_fingerprint)) {
            SelectionContainer { Text(IdentityCrypto.fingerprint(me.publicKey), fontFamily = FontFamily.Monospace) }
            Text(stringResource(R.string.fingerprint_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard(stringResource(R.string.label_public_id)) {
            SelectionContainer { Text(me.publicId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
        OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.identity_rename))
        }
        OutlinedButton(onClick = onRecovery, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.identity_recovery))
        }
        TextButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.identity_reset), color = MaterialTheme.colorScheme.error)
        }
    }

    if (editing) {
        var text by remember { mutableStateOf(me.displayName) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.identity_rename)) },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it.take(64) }, singleLine = true,
                    label = { Text(stringResource(R.string.field_display_name)) })
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { vm.rename(text); editing = false }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reset_title)) },
            text = { Text(stringResource(R.string.reset_body)) },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; vm.resetIdentity() }) {
                    Text(stringResource(R.string.reset_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun RecoveryScreen(
    vm: MainViewModel,
    authenticate: (title: String, allowIfUnavailable: Boolean, onSuccess: () -> Unit) -> Unit,
    onDone: () -> Unit,
) {
    var key by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val authTitle = stringResource(R.string.recovery_auth_title)

    // Block screenshots and recents thumbnails while this screen is open.
    DisposableEffect(Unit) {
        val window = ctx.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            key = null
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.recovery_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        val shown = key
        if (shown == null) {
            Button(
                onClick = { authenticate(authTitle, true) { scope.launch { key = vm.loadRecoveryKey() } } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.recovery_show)) }
        } else {
            SectionCard(stringResource(R.string.label_recovery_key)) {
                SelectionContainer {
                    Text(
                        shown.split('-').chunked(4).joinToString("\n") { it.joinToString("-") },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Text(stringResource(R.string.recovery_warning), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { key = null }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.recovery_hide))
            }
        }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
    }
}
