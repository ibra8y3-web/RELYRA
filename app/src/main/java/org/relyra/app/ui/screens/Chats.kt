package org.relyra.app.ui.screens

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.UiMessage
import org.relyra.app.core.database.AttachmentEntity
import org.relyra.app.core.database.MessageEntity
import org.relyra.app.core.messaging.AttachmentPhase
import org.relyra.app.core.messaging.EnqueueResult
import org.relyra.app.core.messaging.EnvelopeCodec
import org.relyra.app.core.messaging.MessageState
import org.relyra.app.core.transfer.BlossomUrl
import org.relyra.app.ui.components.Avatar
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatsScreen(vm: MainViewModel, onOpen: (String) -> Unit, onOpenGroup: (String) -> Unit, onCreateGroup: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.groups_tab_direct)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.groups_tab_groups)) })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) DirectChats(vm, onOpen) else GroupsList(vm, onOpenGroup, onCreateGroup)
        }
    }
}

@Composable
private fun DirectChats(vm: MainViewModel, onOpen: (String) -> Unit) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val usable = contacts.filter { !it.blocked }
    if (usable.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.chats_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.chats_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
        return
    }
    val byChat = summaries.associateBy { it.chatId }
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    LazyColumn(Modifier.fillMaxSize()) {
        items(usable.sortedByDescending { byChat[it.publicId]?.lastAt ?: 0L }, key = { it.publicId }) { c ->
            val s = byChat[c.publicId]
            ListItem(
                headlineContent = { Text(c.displayName) },
                supportingContent = {
                    Text(
                        when {
                            s == null -> stringResource(R.string.chat_no_messages)
                            s.unread > 0 -> stringResource(R.string.chat_unread_count, s.unread)
                            else -> fmt.format(Date(s.lastAt))
                        }
                    )
                },
                leadingContent = { Avatar(c.displayName) },
                modifier = Modifier.clickable { onOpen(c.publicId) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun ChatScreen(vm: MainViewModel, chatId: String) {
    val flow = remember(chatId) { vm.chatMessages(chatId) }
    val messages by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val canEncrypt = contacts.find { it.publicId == chatId }?.let { it.encKey != null && it.nostrKey != null } == true
    val unread = messages.count { !it.outgoing && !it.readLocally }
    val attFlow = remember(chatId) { vm.attachmentsOf(chatId) }
    val atts by attFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val attById = remember(atts) { atts.associateBy { it.messageId } }
    val progress by vm.downloadProgress.collectAsStateWithLifecycle()
    var exportId by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.externalInFlight = false
        if (uri != null) vm.sendFile(chatId, uri)
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.externalInFlight = false
        val id = exportId
        exportId = null
        if (uri != null && id != null) vm.exportAttachment(id, uri)
    }

    LaunchedEffect(unread) { if (unread > 0) vm.markChatRead(chatId) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Text(
            stringResource(if (canEncrypt) R.string.chat_banner else R.string.chat_banner_nokey),
            style = MaterialTheme.typography.bodySmall,
            color = if (canEncrypt) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.messageId }) { m ->
                val att = attById[m.messageId]
                Bubble(
                    m, att, progress[m.messageId],
                    onCancel = { vm.cancelMessage(m.messageId) },
                    onDownload = { vm.downloadAttachment(m.messageId) },
                    onSave = {
                        exportId = m.messageId
                        vm.externalInFlight = true
                        saver.launch(att?.name ?: "file")
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            TextButton(
                onClick = {
                    vm.externalInFlight = true
                    picker.launch(arrayOf("*/*"))
                },
                enabled = canEncrypt,
            ) { Text(stringResource(R.string.chat_attach)) }
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(EnvelopeCodec.MAX_BODY) },
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                maxLines = 5, modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val body = text
                    vm.sendMessage(chatId, body) { r ->
                        when (r) {
                            is EnqueueResult.Queued -> text = ""
                            EnqueueResult.Blocked -> vm.message.value = UiMessage(R.string.err_msg_blocked)
                            EnqueueResult.NoEncryptionKey -> vm.message.value = UiMessage(R.string.err_msg_no_key)
                            EnqueueResult.TooLong -> vm.message.value = UiMessage(R.string.err_msg_too_long)
                            else -> Unit
                        }
                    }
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp),
            ) { Text(stringResource(R.string.chat_send)) }
        }
    }
}

@Composable
private fun Bubble(
    m: MessageEntity,
    att: AttachmentEntity?,
    progress: Long?,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
    onSave: () -> Unit,
) {
    val mine = m.outgoing
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Box(
            Modifier.background(
                if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(16.dp),
            ).padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (att == null) Text(m.body) else AttachmentCard(att, mine, progress, onDownload, onSave)
        }
        if (mine) {
            val label = when (MessageState.valueOf(m.state)) {
                MessageState.QUEUED -> R.string.msg_state_queued
                MessageState.SENT -> R.string.msg_state_sent
                MessageState.RELAYED -> R.string.msg_state_relayed
                MessageState.DELIVERED -> R.string.msg_state_delivered
                MessageState.READ -> R.string.msg_state_read
                MessageState.FAILED -> R.string.msg_state_failed
                MessageState.EXPIRED -> R.string.msg_state_expired
                MessageState.CANCELLED -> R.string.msg_state_cancelled
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(label), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (m.state == MessageState.QUEUED.name) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.chat_cancel), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
private fun AttachmentCard(
    a: AttachmentEntity,
    mine: Boolean,
    progress: Long?,
    onDownload: () -> Unit,
    onSave: () -> Unit,
) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(a.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            Formatter.formatShortFileSize(ctx, a.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (AttachmentPhase.valueOf(a.phase)) {
            AttachmentPhase.AWAITING_UPLOAD -> Text(
                stringResource(R.string.file_uploading), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AttachmentPhase.UPLOADED -> {
                if (mine) {
                    Text(
                        stringResource(R.string.file_uploaded, BlossomUrl.hostOf(a.serverUrl)),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AttachmentPhase.REMOTE -> {
                Text(
                    stringResource(R.string.file_on_server, BlossomUrl.hostOf(a.serverUrl)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDownload) { Text(stringResource(R.string.file_download)) }
            }
            AttachmentPhase.DOWNLOADING -> {
                val pct = if (a.blobSize > 0 && progress != null) (progress * 100 / a.blobSize).toInt().coerceIn(0, 100) else 0
                Text(stringResource(R.string.file_downloading, pct), style = MaterialTheme.typography.labelSmall)
            }
            AttachmentPhase.READY -> TextButton(onClick = onSave) { Text(stringResource(R.string.file_save)) }
            AttachmentPhase.FAILED -> {
                if (mine) {
                    Text(
                        stringResource(R.string.file_failed_send), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        stringResource(R.string.file_failed_receive), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onDownload) { Text(stringResource(R.string.file_retry)) }
                }
            }
        }
    }
}
