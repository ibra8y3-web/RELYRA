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
import org.relyra.app.core.voice.VoiceRecorder
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.relyra.app.core.messaging.ExtraResult
import org.relyra.app.core.messaging.ScheduleResult
import org.relyra.app.core.database.ReactionEntity
import org.relyra.app.core.database.ScheduledEntity
import androidx.compose.foundation.layout.width
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
    val context = LocalContext.current
    val flow = remember(chatId) { vm.chatMessages(chatId) }
    val messages by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val canEncrypt = contacts.find { it.publicId == chatId }?.let { !it.blocked && it.encKey != null && it.nostrKey != null } == true
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
    val reactionFlow = remember(chatId) { vm.chatReactions(chatId) }
    val reactionList by reactionFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val reactionsByMsg = remember(reactionList) { reactionList.groupBy { it.messageId } }
    val scheduledFlow = remember(chatId) { vm.scheduledFor(chatId) }
    val scheduledList by scheduledFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var editTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var scheduleDraft by remember { mutableStateOf<ScheduleDraft?>(null) }
    var showScheduled by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var recPaused by remember { mutableStateOf(false) }
    var recMs by remember { mutableStateOf(0L) }
    val sendRecording: () -> Unit = {
        val r = recorder.stop()
        recording = false; recPaused = false
        if (r == null) {
            vm.message.value = UiMessage(R.string.voice_too_short)
        } else {
            vm.sendFile(chatId, android.net.Uri.fromFile(r.file), VoiceRecorder.fileName(r.durationMs), "audio/mp4") { r.file.delete() }
        }
    }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    LaunchedEffect(recording, recPaused) {
        while (recording) {
            recMs = recorder.elapsedMs()
            if (recMs >= VoiceRecorder.MAX_MS) { sendRecording(); break }
            kotlinx.coroutines.delay(250)
        }
    }
    val beginRecording: () -> Unit = {
        if (recorder.start()) { recording = true; recPaused = false } else vm.message.value = UiMessage(R.string.voice_failed)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.externalInFlight = false
        if (granted) beginRecording() else vm.message.value = UiMessage(R.string.voice_no_permission)
    }
    val startVoice: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginRecording()
        } else {
            vm.externalInFlight = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
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
    var pendingAcceptCall by remember { mutableStateOf(false) }
    val callPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cameraGranted = !pendingVideoCall || grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (audioGranted && cameraGranted) {
            if (pendingAcceptCall) vm.acceptCall() else vm.startCall(chatId, pendingVideoCall)
        }
        pendingAcceptCall = false
        pendingVideoCall = false
    }
    val call by vm.callState.collectAsStateWithLifecycle()
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
                        Button(onClick = {
                            pendingVideoCall = call.video
                            pendingAcceptCall = true
                            val required = if (call.video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                                else arrayOf(Manifest.permission.RECORD_AUDIO)
                            callPermissions.launch(required)
                        }) { Text(stringResource(R.string.call_accept)) }
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
                    call.detail?.let { detail ->
                        val detailText = when (detail) {
                            "connection_interrupted" -> stringResource(R.string.call_connection_interrupted)
                            "connection_failed" -> stringResource(R.string.call_connection_failed)
                            "no_answer" -> stringResource(R.string.call_no_answer)
                            else -> detail
                        }
                        Text(detailText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
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
                    reactions = reactionsByMsg[m.messageId].orEmpty(),
                    peerId = chatId,
                    resolveAudio = { id -> vm.playableFile(id) },
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
        if (recording) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val secs = (recMs / 1000).toInt()
                Text(stringResource(R.string.voice_recording, "%d:%02d".format(secs / 60, secs % 60)), modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (recPaused) { recorder.resume() } else { recorder.pause() }
                    recPaused = recorder.paused
                }) { Text(stringResource(if (recPaused) R.string.voice_resume else R.string.voice_pause)) }
                TextButton(onClick = { recorder.cancel(); recording = false; recPaused = false }) {
                    Text(stringResource(R.string.voice_cancel), color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = sendRecording) { Text(stringResource(R.string.voice_send)) }
            }
        }
        if (scheduledList.isNotEmpty()) {
            TextButton(onClick = { showScheduled = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.chat_scheduled_count, scheduledList.size))
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
            if (text.isBlank() && canEncrypt && !recording) {
                TextButton(onClick = startVoice) { Text("\uD83C\uDFA4") }
            }
            TextButton(
                onClick = { scheduleDraft = ScheduleDraft(null, text, System.currentTimeMillis() + 3_600_000L) },
                enabled = canEncrypt && text.isNotBlank(),
            ) { Text(stringResource(R.string.chat_schedule)) }
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
                    if (m.state != MessageState.CANCELLED.name && m.state != MessageState.FAILED.name &&
                        !(m.outgoing && m.state == MessageState.QUEUED.name)
                    ) {
                        val mineNow = reactionsByMsg[m.messageId].orEmpty().firstOrNull { it.reactorId != chatId }?.emoji
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            REACTION_CHOICES.forEach { e ->
                                TextButton(onClick = {
                                    vm.react(chatId, m.messageId, if (mineNow == e) "" else e)
                                    actionMsg = null
                                }) { Text(e, style = MaterialTheme.typography.titleLarge) }
                            }
                        }
                    }
                    if (isText && m.outgoing && m.state != MessageState.CANCELLED.name && m.state != MessageState.FAILED.name &&
                        m.state != MessageState.EXPIRED.name
                    ) {
                        TextButton(onClick = { editTarget = m; actionMsg = null }) { Text(stringResource(R.string.msg_edit)) }
                    }
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

    editTarget?.let { m ->
        var draft by remember(m.messageId) { mutableStateOf(m.body) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text(stringResource(R.string.msg_edit_title)) },
            text = {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it.take(EnvelopeCodec.MAX_BODY) },
                    maxLines = 6, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank() && draft != m.body,
                    onClick = {
                        vm.editMessage(m.messageId, draft) { r ->
                            when (r) {
                                ExtraResult.OK, ExtraResult.UNCHANGED -> Unit
                                ExtraResult.TOO_LATE -> vm.message.value = UiMessage(R.string.msg_edit_too_late)
                                else -> vm.message.value = UiMessage(R.string.msg_edit_not_allowed)
                            }
                        }
                        editTarget = null
                    },
                ) { Text(stringResource(R.string.msg_edit_save)) }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    scheduleDraft?.let { d ->
        var body by remember(d) { mutableStateOf(d.body) }
        var at by remember(d) { mutableStateOf(d.sendAt) }
        AlertDialog(
            onDismissRequest = { scheduleDraft = null },
            title = { Text(stringResource(R.string.schedule_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = body, onValueChange = { body = it.take(EnvelopeCodec.MAX_BODY) },
                        maxLines = 5, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.schedule_at, formatWhen(context, at)))
                    TextButton(onClick = { pickDateTime(context, at) { at = it } }) { Text(stringResource(R.string.schedule_pick)) }
                    Text(stringResource(R.string.schedule_zone_note, java.util.TimeZone.getDefault().id),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = body.isNotBlank(),
                    onClick = {
                        val done: (ScheduleResult) -> Unit = { r ->
                            vm.message.value = UiMessage(scheduleMessageRes(r))
                            if (r == ScheduleResult.OK) { if (d.id == null) text = ""; scheduleDraft = null }
                        }
                        if (d.id == null) vm.scheduleMessage(chatId, body, at, done) else vm.rescheduleMessage(d.id, body, at, done)
                    },
                ) { Text(stringResource(R.string.schedule_confirm)) }
            },
            dismissButton = { TextButton(onClick = { scheduleDraft = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showScheduled) {
        AlertDialog(
            onDismissRequest = { showScheduled = false },
            title = { Text(stringResource(R.string.schedule_list_title)) },
            text = {
                if (scheduledList.isEmpty()) {
                    Text(stringResource(R.string.schedule_list_empty))
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(scheduledList, key = { it.id }) { s ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text(s.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(formatWhen(context, s.sendAt), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row {
                                    TextButton(onClick = { scheduleDraft = ScheduleDraft(s.id, s.body, s.sendAt); showScheduled = false }) {
                                        Text(stringResource(R.string.msg_edit))
                                    }
                                    TextButton(onClick = { vm.cancelScheduled(s.id) }) {
                                        Text(stringResource(R.string.schedule_cancel), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showScheduled = false }) { Text(stringResource(R.string.close)) } },
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
    reactions: List<ReactionEntity>,
    peerId: String,
    resolveAudio: suspend (String) -> java.io.File?,
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
            if (att == null) Text(m.body) else AttachmentCard(att, mine, progress, onDownload, onSave, resolveAudio)
        }
        if (m.editedAt != null) {
            Text(stringResource(R.string.msg_edited), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (reactions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                reactions.groupBy { it.emoji }.forEach { (emoji, who) ->
                    val mineReacted = who.any { it.reactorId != peerId }
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (mineReacted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text(if (who.size > 1) "$emoji ${who.size}" else emoji, style = MaterialTheme.typography.labelMedium) }
                }
            }
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
    resolveAudio: suspend (String) -> java.io.File?,
) {
    val ctx = LocalContext.current
    val voice = VoiceRecorder.isVoice(a.name, a.mime)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (voice) {
            Text(stringResource(R.string.voice_message, VoiceRecorder.durationSeconds(a.name) ?: 0), style = MaterialTheme.typography.bodyLarge)
            if (a.phase == AttachmentPhase.READY.name || mine) VoicePlayer(a.messageId, resolveAudio)
        } else {
            Text(a.name, style = MaterialTheme.typography.bodyLarge)
        }
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

private val REACTION_CHOICES = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE4F")

private class ScheduleDraft(val id: String?, val body: String, val sendAt: Long)

private fun formatWhen(ctx: android.content.Context, millis: Long): String =
    DateUtils.formatDateTime(ctx, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)

/** Date, then time, in the phone's current time zone. The result is an absolute instant. */
private fun pickDateTime(ctx: android.content.Context, initial: Long, onPicked: (Long) -> Unit) {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = initial }
    android.app.DatePickerDialog(ctx, { _, y, mo, d ->
        android.app.TimePickerDialog(ctx, { _, h, mi ->
            val out = java.util.Calendar.getInstance().apply { set(y, mo, d, h, mi, 0); set(java.util.Calendar.MILLISECOND, 0) }
            onPicked(out.timeInMillis)
        }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE),
            android.text.format.DateFormat.is24HourFormat(ctx)).show()
    }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
}

private fun scheduleMessageRes(r: ScheduleResult): Int = when (r) {
    ScheduleResult.OK -> R.string.schedule_ok
    ScheduleResult.IN_THE_PAST -> R.string.schedule_past
    ScheduleResult.TOO_FAR -> R.string.schedule_far
    ScheduleResult.TOO_LONG -> R.string.err_msg_too_long
    ScheduleResult.BLOCKED -> R.string.err_msg_blocked
    ScheduleResult.NO_KEY -> R.string.err_msg_no_key
    ScheduleResult.NOT_PENDING, ScheduleResult.NOT_FOUND -> R.string.schedule_not_pending
    else -> R.string.schedule_failed
}

@Composable
private fun VoicePlayer(messageId: String, resolve: suspend (String) -> java.io.File?) {
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(messageId) { onDispose { player?.release(); player = null } }
    TextButton(onClick = {
        if (playing) {
            player?.release(); player = null; playing = false
        } else {
            scope.launch {
                val f = resolve(messageId) ?: return@launch
                val p = android.media.MediaPlayer()
                try {
                    p.setDataSource(f.path)
                    p.setOnCompletionListener { it.release(); player = null; playing = false }
                    p.prepare()
                    p.start()
                    player = p; playing = true
                } catch (e: Exception) {
                    runCatching { p.release() }
                }
            }
        }
    }) { Text(stringResource(if (playing) R.string.voice_stop else R.string.voice_play)) }
}
