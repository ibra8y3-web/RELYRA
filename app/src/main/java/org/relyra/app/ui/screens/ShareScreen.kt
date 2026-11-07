package org.relyra.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.messaging.EnvelopeCodec
import org.relyra.app.core.tools.AndroidTools
import org.relyra.app.ui.components.Avatar

/** Choose who receives something another app shared to RELYRA. Nothing is sent until a contact is tapped. */
@Composable
fun ShareScreen(vm: MainViewModel, onDone: (String?) -> Unit) {
    val ctx = LocalContext.current
    val req by vm.pendingShare.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.pendingShare.value = null } }
    val r = req
    if (r == null) {
        LaunchedEffect(Unit) { onDone(null) }
        return
    }
    val file = remember(r) { r.uri?.let { AndroidTools.describe(ctx.contentResolver, it) } }
    val tooLong = (r.text?.length ?: 0) > EnvelopeCodec.MAX_BODY
    val targets = contacts.filter { !it.blocked && it.encKey != null && it.nostrKey != null }

    fun send(id: String) {
        r.uri?.let { vm.sendFile(id, it) }
        val t = r.text
        if (!t.isNullOrBlank() && !tooLong) vm.sendMessage(id, t) { }
        onDone(id)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.share_intro), style = MaterialTheme.typography.titleMedium)
        r.text?.takeIf { it.isNotBlank() }?.let {
            Text(it.take(300) + if (it.length > 300) "…" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (file != null) Text(stringResource(R.string.share_file, file.name), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tooLong) {
            Text(stringResource(R.string.share_too_long, EnvelopeCodec.MAX_BODY), color = MaterialTheme.colorScheme.error)
        }
        if (targets.isEmpty()) {
            Text(stringResource(R.string.share_no_contacts), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(targets, key = { it.publicId }) { c ->
                ListItem(
                    headlineContent = { Text(c.displayName) },
                    leadingContent = { Avatar(c.displayName) },
                    modifier = Modifier.clickable(enabled = !tooLong || file != null) { send(c.publicId) },
                )
                HorizontalDivider()
            }
        }
        OutlinedButton(onClick = { onDone(null) }) { Text(stringResource(R.string.cancel)) }
    }
}
