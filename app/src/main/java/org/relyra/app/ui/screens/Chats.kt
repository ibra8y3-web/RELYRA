package org.relyra.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.UiMessage
import org.relyra.app.core.database.AttachmentEntity
import org.relyra.app.core.database.ChatPrefEntity
import org.relyra.app.core.database.ContactEntity
import org.relyra.app.core.database.MessageEntity
import org.relyra.app.core.messaging.AttachmentPhase
import org.relyra.app.core.messaging.EnqueueResult
import org.relyra.app.core.messaging.EnvelopeCodec
import org.relyra.app.core.messaging.MessageState
import org.relyra.app.core.notify.Foreground
import org.relyra.app.core.network.CallPhase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.relyra.app.core.transfer.BlossomUrl
import org.relyra.app.ui.components.Avatar
import java.text.DateFormat
import java.util.Date

@Composable
fun DirectChatsDestination(vm: MainViewModel, onOpen: (String) -> Unit, onAddContact: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(value = query, onValueChange = { query = it.take(60) },
            placeholder = { Text(stringResource(R.string.chats_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
        DirectChats(vm, query, onOpen, onAddContact)
    }
}

/** Pin, mute, archive (and optionally clear) for one conversation. Choices are kept only on this device. */
@Composable
internal fun ConversationMenu(
    pref: ChatPrefEntity?,
    onPin: (Boolean) -> Unit,
    onMute: (Boolean) -> Unit,
    onArchive: (Boolean) -> Unit,
    onClear: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    val pinned = pref?.pinned == true
    val muted = pref?.muted == true
    val archived = pref?.archived == true
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (pinned) R.string.chat_menu_unpin else R.string.chat_menu_pin)) },
                onClick = { open = false; onPin(!pinned) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (muted) R.string.chat_menu_unmute else R.string.chat_menu_mute)) },
                onClick = { open = false; onMute(!muted) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (archived) R.string.chat_menu_unarchive else R.string.chat_menu_archive)) },
                onClick = { open = false; onArchive(!archived) },
            )
            if (onClear != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_clear)) },
                    onClick = { open = false; onClear() },
                )
            }
        }
    }
}

@Composable
internal fun TimeLabel(at: Long) {
    val text = if (DateUtils.isToday(at)) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
    else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(at))
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun UnreadBadge(count: Int) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
internal fun ClearChatDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_clear_title)) },
        text = { Text(stringResource(R.string.chat_clear_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.chat_clear_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DirectChats(vm: MainViewModel, query: String, onOpen: (String) -> Unit, onAddContact: () -> Unit) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val prefs by vm.chatPrefs.collectAsStateWithLifecycle()
    val listPreview by vm.settings.listPreview.collectAsStateWithLifecycle()
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var clearTarget by remember { mutableStateOf<String?>(null) }
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
            Button(onClick = onAddContact, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.chats_add_contact_cta))
            }
        }
        return
    }
    val byChat = summaries.associateBy { it.chatId }
    val q = query.trim()
    val matching = usable.filter {
        q.isEmpty() || it.displayName.contains(q, ignoreCase = true) ||
            byChat[it.publicId]?.lastBody?.contains(q, ignoreCase = true) == true
    }
    val archivedCount = matching.count { prefs[it.publicId]?.archived == true }
    val visible = matching
        .filter { (prefs[it.publicId]?.archived == true) == showArchived }
        .sortedWith(
            compareByDescending<ContactEntity> { prefs[it.publicId]?.pinned == true }
                .thenByDescending { byChat[it.publicId]?.lastAt ?: 0L }
        )
    LazyColumn(Modifier.fillMaxSize()) {
        if (archivedCount > 0 || showArchived) {
            item(key = "archived-toggle") {
                TextButton(onClick = { showArchived = !showArchived }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(
                        if (showArchived) stringResource(R.string.chats_archived_hide)
                        else stringResource(R.string.chats_archived_show, archivedCount)
                    )
                }
            }
        }
        if (visible.isEmpty()) {
            item(key = "none") {
                Text(
                    stringResource(if (q.isNotEmpty()) R.string.chats_no_match else R.string.chats_nothing_here),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp),
                )
            }
        }
        items(visible, key = { it.publicId }) { c ->
            val s = byChat[c.publicId]
            val pref = prefs[c.publicId]
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                    .clickable { onOpen(c.publicId) },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                ListItem(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (pref?.pinned == true) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    },
                    supportingContent = {
                        Text(
                            when {
                                s == null -> stringResource(R.string.chat_no_messages)
                                listPreview -> s.lastBody
                                s.unread > 0 -> stringResource(R.string.chat_unread_count, s.unread)
                                else -> stringResource(R.string.chat_preview_hidden)
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = { Avatar(c.displayName) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.End) {
                                if (s != null) TimeLabel(s.lastAt)
                                if (s != null && s.unread > 0) UnreadBadge(s.unread)
                            }
                            ConversationMenu(
                                pref,
                                onPin = { vm.setPinned(c.publicId, it) },
                                onMute = { vm.setMuted(c.publicId, it) },
                                onArchive = { vm.setArchived(c.publicId, it) },
                                onClear = { clearTarget = c.publicId },
                            )
                        }
                    },
                )
            }
        }
    }
    clearTarget?.let { id ->
        ClearChatDialog(onConfirm = { vm.clearChat(id); clearTarget = null }, onDismiss = { clearTarget = null })
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
    val clipboard = LocalClipboardManager.current
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var actionMsg by remember { mutableStateOf<MessageEntity?>(null) }
    var forwardBody by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = remember(messages, query) {
        val q = query.trim()
        if (q.isEmpty()) messages else messages.filter { it.body.contains(q, ignoreCase = true) }
    }
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
    var pendingVideoCall by remember { mutableStateOf(false) }
    val callPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(LocalContext.current, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cameraGranted = !pendingVideoCall || grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(LocalContext.current, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (audioGranted && cameraGranted) vm.startCall(chatId, pendingVideoCall)
        pendingVideoCall = false
    }
    val call by vm.callState.collectAsStateWithLifecycle().let { it }
    val localVideo by vm.localCallVideo.collectAsStateWithLifecycle()
    val remoteVideo by vm.remoteCallVideo.collectAsStateWithLifecycle()

    DisposableEffect(chatId) {
        Foreground.openChat = chatId
        vm.cancelNotification(chatId)
        onDispose { if (Foreground.openChat == chatId) Foreground.openChat = null }
    }
    LaunchedEffect(unread) { if (unread > 0) vm.markChatRead(chatId) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (canEncrypt) R.string.chat_banner else R.string.chat_banner_nokey),
                style = MaterialTheme.typography.bodySmall,
                color = if (canEncrypt) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f).padding(12.dp),
            )
            TextButton(onClick = {
                pendingVideoCall = false
                callPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }, enabled = canEncrypt && call.phase in listOf(CallPhase.IDLE, CallPhase.ENDED, CallPhase.FAILED)) {
                Text(stringResource(R.string.call_voice))
            }
            TextButton(onClick = {
                pendingVideoCall = true
                callPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
            }, enabled = canEncrypt && call.phase in listOf(CallPhase.IDLE, CallPhase.ENDED, CallPhase.FAILED)) {
                Text(stringResource(R.string.call_video))
            }
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.chat_search))
            }
            IconButton(onClick = { confirmClear = true }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.chat_menu_clear))
            }
        }
        if (searching) {
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(60) },
                placeholder = { Text(stringResource(R.string.chat_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (call.peerId == chatId && call.phase == CallPhase.INCOMING) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(if (call.video) R.string.call_incoming_video else R.string.call_incoming_voice),
                        style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::acceptCall) { Text(stringResource(R.string.call_accept)) }
                        TextButton(onClick = vm::declineCall) { Text(stringResource(R.string.call_decline)) }
                    }
                }
            }
        } else if (call.peerId == chatId && call.phase !in listOf(CallPhase.IDLE, CallPhase.ENDED)) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(when (call.phase) {
                        CallPhase.OUTGOING -> R.string.call_outgoing
                        CallPhase.CONNECTING -> R.string.call_connecting
                        CallPhase.ACTIVE -> R.string.call_active
                        CallPhase.FAILED -> R.string.call_failed
                        else -> R.string.call_connecting
                    }), style = MaterialTheme.typography.titleMedium)
                    if (call.video) {
                        CallVideoSurface(remoteVideo, vm, mirror = false)
                        CallVideoSurface(localVideo, vm, mirror = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { vm.setCallMuted(!call.muted) }) {
                            Text(stringResource(if (call.muted) R.string.call_unmute else R.string.call_mute))
                        }
                        TextButton(onClick = { vm.setCallSpeaker(!call.speakerEnabled) }) {
                            Text(stringResource(if (call.speakerEnabled) R.string.call_earpiece else R.string.call_speaker))
                        }
                        if (call.video) {
                            TextButton(onClick = { vm.setCallCamera(!call.cameraEnabled) }) {
                                Text(stringResource(if (call.cameraEnabled) R.string.call_camera_off else R.string.call_camera_on))
                            }
                            TextButton(onClick = vm::switchCallCamera) { Text(stringResource(R.string.call_switch_camera)) }
                        }
                        TextButton(onClick = vm::endCall) { Text(stringResource(R.string.call_end), color = MaterialTheme.colorScheme.error) }
                    }
                    call.detail?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.messageId }) { m ->
                val att = attById[m.messageId]
                Bubble(
                    m, att, progress[m.messageId],
                    onTap = { actionMsg = m },
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

    actionMsg?.let { m ->
        val isText = attById[m.messageId] == null
        AlertDialog(
            onDismissRequest = { actionMsg = null },
            confirmButton = { TextButton(onClick = { actionMsg = null }) { Text(stringResource(R.string.close)) } },
            text = {
                Column {
                    if (isText) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(m.body)); actionMsg = null }) {
                            Text(stringResource(R.string.msg_copy))
                        }
                        TextButton(onClick = { forwardBody = m.body; actionMsg = null }) {
                            Text(stringResource(R.string.msg_forward))
                        }
                    }
                    TextButton(onClick = { vm.deleteMessage(m.messageId); actionMsg = null }) {
                        Text(stringResource(R.string.msg_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }

    forwardBody?.let { body ->
        val targets = contacts.filter { !it.blocked && it.encKey != null && it.nostrKey != null && it.publicId != chatId }
        AlertDialog(
            onDismissRequest = { forwardBody = null },
            title = { Text(stringResource(R.string.msg_forward_title)) },
            text = {
                if (targets.isEmpty()) {
                    Text(stringResource(R.string.msg_forward_none))
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(targets, key = { it.publicId }) { c ->
                            ListItem(
                                headlineContent = { Text(c.displayName) },
                                leadingContent = { Avatar(c.displayName) },
                                modifier = Modifier.clickable {
                                    vm.sendMessage(c.publicId, body) { r ->
                                        vm.message.value = UiMessage(
                                            if (r is EnqueueResult.Queued) R.string.msg_forwarded else R.string.err_msg_forward
                                        )
                                    }
                                    forwardBody = null
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { forwardBody = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmClear) {
        ClearChatDialog(onConfirm = { vm.clearChat(chatId); confirmClear = false }, onDismiss = { confirmClear = false })
    }
}

@Composable
private fun Bubble(
    m: MessageEntity,
    att: AttachmentEntity?,
    progress: Long?,
    onTap: () -> Unit,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
    onSave: () -> Unit,
) {
    val mine = m.outgoing
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onTap)
                .padding(horizontal = 12.dp, vertical = 8.dp)
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

@Composable
private fun CallVideoSurface(track: VideoTrack?, vm: MainViewModel, mirror: Boolean) {
    if (track == null) return
    val context = LocalContext.current
    val renderer = remember(track) {
        SurfaceViewRenderer(context).apply {
            init(vm.callEglContext(), null)
            setEnableHardwareScaler(true)
            setMirror(mirror)
        }
    }
    DisposableEffect(track, renderer) {
        track.addSink(renderer)
        onDispose { track.removeSink(renderer); renderer.release() }
    }
    AndroidView(factory = { renderer }, modifier = Modifier.fillMaxWidth().height(180.dp))
}
