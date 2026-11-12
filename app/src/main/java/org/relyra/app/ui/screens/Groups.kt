package org.relyra.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.IdentityState
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.database.ContactEntity
import org.relyra.app.core.database.GroupMemberEntity
import org.relyra.app.core.database.GroupEntity
import org.relyra.app.core.messaging.EnvelopeCodec
import org.relyra.app.core.messaging.GroupKind
import org.relyra.app.core.messaging.GroupLimits
import org.relyra.app.core.messaging.GroupRole
import org.relyra.app.core.messaging.GroupState
import org.relyra.app.core.notify.Foreground
import org.relyra.app.ui.components.Avatar
import java.text.DateFormat
import java.util.Date

@StringRes
private fun roleLabel(role: String): Int = when (GroupRole.valueOf(role)) {
    GroupRole.OWNER -> R.string.groups_role_owner
    GroupRole.ADMIN -> R.string.groups_role_admin
    GroupRole.MEMBER -> R.string.groups_role_member
}

/** Contacts that can be put in a group: not blocked and with the encryption keys needed to write to them. */
private fun usable(c: ContactEntity) = !c.blocked && c.encKey != null && c.nostrKey != null

@Composable
private fun myPublicId(vm: MainViewModel): String? {
    val id by vm.identity.collectAsStateWithLifecycle()
    return (id as? IdentityState.Present)?.identity?.publicId
}

// ---- list --------------------------------------------------------------------------------------------------

@Composable
fun GroupsList(vm: MainViewModel, query: String, onOpen: (String) -> Unit, onCreate: () -> Unit, kindFilter: GroupKind? = null) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val sums by vm.groupSummaries.collectAsStateWithLifecycle()
    val prefs by vm.chatPrefs.collectAsStateWithLifecycle()
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var clearTarget by remember { mutableStateOf<String?>(null) }
    val byId = sums.associateBy { it.groupId }
    val q = query.trim()
    val matching = groups.filter { (kindFilter == null || GroupKind.valueOf(it.kind) == kindFilter) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
    val archivedCount = matching.count { prefs[it.groupId]?.archived == true }
    val visible = matching
        .filter { (prefs[it.groupId]?.archived == true) == showArchived }
        .sortedWith(
            compareByDescending<GroupEntity> { prefs[it.groupId]?.pinned == true }
                .thenByDescending { byId[it.groupId]?.lastAt ?: it.createdAt }
        )
    Box(Modifier.fillMaxSize()) {
        if (matching.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (q.isNotEmpty()) {
                    Text(stringResource(R.string.chats_no_match), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(if (kindFilter == GroupKind.CHANNEL) R.string.channels_empty_title else R.string.groups_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (kindFilter == GroupKind.CHANNEL) R.string.channels_empty_body else R.string.groups_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
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
                items(visible, key = { it.groupId }) { g ->
                    val s = byId[g.groupId]
                    val pref = prefs[g.groupId]
                    val state = GroupState.valueOf(g.state)
                    val kind = stringResource(
                        if (GroupKind.valueOf(g.kind) == GroupKind.CHANNEL) R.string.groups_kind_channel_short else R.string.groups_kind_group_short
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                            .clickable { onOpen(g.groupId) },
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        ListItem(
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (pref?.pinned == true) {
                                        Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                            },
                            supportingContent = {
                                Text(
                                    when {
                                        state == GroupState.INVITED -> stringResource(R.string.groups_state_invited)
                                        state == GroupState.LEFT -> stringResource(R.string.groups_state_left)
                                        state == GroupState.REMOVED -> stringResource(R.string.groups_state_removed)
                                        s != null && s.unread > 0 -> kind + " · " + stringResource(R.string.chat_unread_count, s.unread)
                                        else -> kind
                                    },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = { Avatar(g.name) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        if (s != null) TimeLabel(s.lastAt)
                                        if (s != null && s.unread > 0) UnreadBadge(s.unread)
                                    }
                                    ConversationMenu(
                                        pref,
                                        onPin = { vm.setPinned(g.groupId, it) },
                                        onMute = { vm.setMuted(g.groupId, it) },
                                        onArchive = { vm.setArchived(g.groupId, it) },
                                        onClear = { clearTarget = g.groupId },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onCreate,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(stringResource(if (kindFilter == GroupKind.CHANNEL) R.string.channels_new else R.string.groups_new)) },
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        )
    }
    clearTarget?.let { id ->
        ClearChatDialog(onConfirm = { vm.clearGroupChat(id); clearTarget = null }, onDismiss = { clearTarget = null })
    }
}

@Composable
fun GroupsSectionScreen(vm: MainViewModel, kind: GroupKind, onOpen: (String) -> Unit, onCreate: () -> Unit) {
    var query by rememberSaveable(kind) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(value = query, onValueChange = { query = it.take(60) },
            placeholder = { Text(stringResource(if (kind == GroupKind.CHANNEL) R.string.channels_search_hint else R.string.groups_search_hint)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
        GroupsList(vm, query, onOpen, onCreate, kindFilter = kind)
    }
}

// ---- create ------------------------------------------------------------------------------------------------

@Composable
fun CreateGroupScreen(vm: MainViewModel, onCreated: (String) -> Unit, onAddContact: () -> Unit, initialKind: GroupKind = GroupKind.GROUP) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val candidates = contacts.filter { usable(it) }
    var name by remember { mutableStateOf("") }
    val kind = initialKind
    var picked by remember { mutableStateOf(setOf<String>()) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(GroupLimits.MAX_NAME) },
            label = { Text(stringResource(R.string.groups_field_name)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(if (kind == GroupKind.GROUP) R.string.groups_kind_group else R.string.groups_kind_channel),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.groups_pick_members), style = MaterialTheme.typography.titleSmall)
        if (candidates.isEmpty()) {
            Text(stringResource(R.string.groups_no_contacts), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onAddContact) { Text(stringResource(R.string.chats_add_contact_cta)) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(candidates, key = { it.publicId }) { c ->
                val on = c.publicId in picked
                ListItem(
                    headlineContent = { Text(c.displayName) },
                    leadingContent = { Avatar(c.displayName) },
                    trailingContent = { Checkbox(checked = on, onCheckedChange = null) },
                    modifier = Modifier.clickable { picked = if (on) picked - c.publicId else picked + c.publicId },
                )
            }
        }
        Button(
            onClick = {
                vm.createGroup(name, kind, picked.toList()) { out -> out.groupId?.let(onCreated) }
            },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.groups_create_action)) }
    }
}

// ---- conversation ------------------------------------------------------------------------------------------

@Composable
fun GroupChatScreen(vm: MainViewModel, groupId: String) {
    val group by remember(groupId) { vm.groupOf(groupId) }.collectAsStateWithLifecycle(initialValue = null)
    val messages by remember(groupId) { vm.groupMessages(groupId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val members by remember(groupId) { vm.groupMembers(groupId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val delivery by remember(groupId) { vm.groupDelivery(groupId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val me = myPublicId(vm)
    val g = group
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val deliveryById = remember(delivery) { delivery.associateBy { it.messageId } }
    val unread = messages.count { !it.outgoing && !it.readLocally }
    val youLabel = stringResource(R.string.groups_you)
    val unknownLabel = stringResource(R.string.groups_unknown_member)
    fun nameOf(id: String): String = when {
        id == me -> youLabel
        else -> contacts.find { it.publicId == id }?.displayName ?: unknownLabel
    }

    val clipboard = LocalClipboardManager.current
    var actionMsg by remember { mutableStateOf<org.relyra.app.core.database.GroupMessageEntity?>(null) }
    DisposableEffect(groupId) {
        Foreground.openChat = groupId
        vm.cancelNotification(groupId)
        onDispose { if (Foreground.openChat == groupId) Foreground.openChat = null }
    }
    LaunchedEffect(unread) { if (unread > 0) vm.markGroupRead(groupId) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    if (g == null) return
    val state = GroupState.valueOf(g.state)
    val kind = GroupKind.valueOf(g.kind)
    val myRole = members.find { it.memberId == me }?.role?.let { GroupRole.valueOf(it) }
    val canPost = state == GroupState.ACTIVE && myRole != null && (kind == GroupKind.GROUP || myRole != GroupRole.MEMBER)

    Column(Modifier.fillMaxSize().imePadding()) {
        Text(
            stringResource(if (kind == GroupKind.CHANNEL) R.string.groups_banner_channel else R.string.groups_banner_group),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
        )
        if (state == GroupState.INVITED) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.groups_invite_body, nameOf(g.invitedBy), g.name))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.acceptGroup(groupId) }) { Text(stringResource(R.string.groups_accept)) }
                    OutlinedButton(onClick = { vm.leaveGroup(groupId) }) { Text(stringResource(R.string.groups_decline)) }
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.messageId }) { m ->
                val mine = m.outgoing
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { actionMsg = m }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (!mine) Text(nameOf(m.senderId), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(m.body)
                    }
                    val d = deliveryById[m.messageId]
                    if (mine && d != null) {
                        Text(
                            stringResource(R.string.groups_delivered, d.delivered, d.total) +
                                if (d.readCount > 0) " · " + stringResource(R.string.groups_read, d.readCount, d.total) else "",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (canPost) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(EnvelopeCodec.MAX_BODY) },
                    placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                    maxLines = 5, modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { val body = text; vm.sendGroupMessage(groupId, body) { text = "" } },
                    enabled = text.isNotBlank(), modifier = Modifier.padding(start = 8.dp),
                ) { Text(stringResource(R.string.chat_send)) }
            }
        } else if (state == GroupState.ACTIVE) {
            Text(stringResource(R.string.groups_readonly), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        } else if (state != GroupState.INVITED) {
            Text(stringResource(R.string.groups_inactive), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
    }

    actionMsg?.let { m ->
        AlertDialog(
            onDismissRequest = { actionMsg = null },
            confirmButton = { TextButton(onClick = { actionMsg = null }) { Text(stringResource(R.string.close)) } },
            text = {
                Column {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(m.body)); actionMsg = null }) {
                        Text(stringResource(R.string.msg_copy))
                    }
                    TextButton(onClick = { vm.deleteGroupMessage(m.messageId); actionMsg = null }) {
                        Text(stringResource(R.string.msg_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }
}

// ---- info and management ---------------------------------------------------------------------------------

private enum class Confirm { LEAVE, DELETE }

@Composable
fun GroupInfoScreen(vm: MainViewModel, groupId: String, onGone: () -> Unit) {
    val group by remember(groupId) { vm.groupOf(groupId) }.collectAsStateWithLifecycle(initialValue = null)
    val members by remember(groupId) { vm.groupMembers(groupId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val me = myPublicId(vm)
    var picked by remember { mutableStateOf<GroupMemberEntity?>(null) }
    var transferTo by remember { mutableStateOf<GroupMemberEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val g = group ?: return
    val state = GroupState.valueOf(g.state)
    val myRole = members.find { it.memberId == me }?.role?.let { GroupRole.valueOf(it) }
    val youLabel = stringResource(R.string.groups_you)
    val unknownLabel = stringResource(R.string.groups_unknown_member)
    fun nameOf(id: String): String = when {
        id == me -> youLabel
        else -> contacts.find { it.publicId == id }?.displayName ?: unknownLabel
    }
    val active = state == GroupState.ACTIVE
    val staff = active && (myRole == GroupRole.OWNER || myRole == GroupRole.ADMIN)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(g.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(if (GroupKind.valueOf(g.kind) == GroupKind.CHANNEL) R.string.groups_kind_channel_short else R.string.groups_kind_group_short) +
                        " · " + stringResource(R.string.groups_members_count, members.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (staff) {
                        OutlinedButton(onClick = { showRename = true }) { Text(stringResource(R.string.groups_rename)) }
                        OutlinedButton(onClick = { showAdd = true }) { Text(stringResource(R.string.groups_add_members)) }
                    }
                }
            }
            HorizontalDivider()
        }
        items(members.sortedByDescending { GroupRole.valueOf(it.role).ordinal }, key = { it.memberId }) { m ->
            val inContacts = m.memberId == me || contacts.any { it.publicId == m.memberId }
            ListItem(
                headlineContent = { Text(nameOf(m.memberId)) },
                supportingContent = {
                    Text(
                        stringResource(roleLabel(m.role)) +
                            if (!inContacts) " · " + stringResource(R.string.groups_member_not_contact) else ""
                    )
                },
                leadingContent = { Avatar(nameOf(m.memberId)) },
                modifier = Modifier.clickable(enabled = active && m.memberId != me) { picked = m },
            )
            HorizontalDivider()
        }
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active || state == GroupState.INVITED) {
                    if (myRole == GroupRole.OWNER) {
                        Text(stringResource(R.string.groups_leave_owner), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        OutlinedButton(onClick = { confirm = Confirm.LEAVE }) { Text(stringResource(R.string.groups_leave)) }
                    }
                } else {
                    OutlinedButton(onClick = { confirm = Confirm.DELETE }) { Text(stringResource(R.string.groups_delete_local)) }
                }
            }
        }
    }

    // Actions on one member, offered only when this device's own copy of the group allows them.
    picked?.let { m ->
        val target = GroupRole.valueOf(m.role)
        val isOwner = myRole == GroupRole.OWNER
        val canRemove = target != GroupRole.OWNER && (isOwner || (myRole == GroupRole.ADMIN && target == GroupRole.MEMBER))
        AlertDialog(
            onDismissRequest = { picked = null },
            confirmButton = { TextButton(onClick = { picked = null }) { Text(stringResource(R.string.close)) } },
            title = { Text(nameOf(m.memberId)) },
            text = {
                Column {
                    if (isOwner && target == GroupRole.MEMBER) {
                        TextButton(onClick = { vm.setGroupAdmin(groupId, m.memberId, true); picked = null }) {
                            Text(stringResource(R.string.groups_make_admin))
                        }
                    }
                    if (isOwner && target == GroupRole.ADMIN) {
                        TextButton(onClick = { vm.setGroupAdmin(groupId, m.memberId, false); picked = null }) {
                            Text(stringResource(R.string.groups_remove_admin))
                        }
                    }
                    if (isOwner && target != GroupRole.OWNER) {
                        TextButton(onClick = { transferTo = m; picked = null }) { Text(stringResource(R.string.groups_transfer)) }
                    }
                    if (canRemove) {
                        TextButton(onClick = { vm.removeGroupMember(groupId, m.memberId); picked = null }) {
                            Text(stringResource(R.string.groups_remove), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
        )
    }

    transferTo?.let { m ->
        AlertDialog(
            onDismissRequest = { transferTo = null },
            title = { Text(stringResource(R.string.groups_confirm_title)) },
            text = { Text(stringResource(R.string.groups_confirm_transfer)) },
            confirmButton = {
                TextButton(onClick = { vm.transferGroup(groupId, m.memberId); transferTo = null }) {
                    Text(stringResource(R.string.groups_transfer))
                }
            },
            dismissButton = { TextButton(onClick = { transferTo = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.groups_confirm_title)) },
            text = { Text(stringResource(if (c == Confirm.LEAVE) R.string.groups_confirm_leave else R.string.groups_confirm_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (c == Confirm.LEAVE) vm.leaveGroup(groupId) { onGone() } else vm.deleteGroup(groupId) { onGone() }
                }) {
                    Text(stringResource(if (c == Confirm.LEAVE) R.string.groups_leave else R.string.groups_delete_local),
                        color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showRename) {
        var newName by remember { mutableStateOf(g.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.groups_rename)) },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it.take(GroupLimits.MAX_NAME) }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { vm.renameGroup(groupId, newName); showRename = false }, enabled = newName.isNotBlank()) {
                    Text(stringResource(R.string.groups_rename_action))
                }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showAdd) {
        val existing = members.map { it.memberId }.toSet()
        val candidates = contacts.filter { usable(it) && it.publicId !in existing }
        var chosen by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.groups_add_members)) },
            text = {
                if (candidates.isEmpty()) {
                    Text(stringResource(if (contacts.none { usable(it) }) R.string.groups_add_no_contacts else R.string.groups_add_none))
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(candidates, key = { it.publicId }) { c ->
                            val on = c.publicId in chosen
                            ListItem(
                                headlineContent = { Text(c.displayName) },
                                trailingContent = { Checkbox(checked = on, onCheckedChange = null) },
                                modifier = Modifier.clickable { chosen = if (on) chosen - c.publicId else chosen + c.publicId },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.addGroupMembers(groupId, chosen.toList()); showAdd = false }, enabled = chosen.isNotEmpty()) {
                    Text(stringResource(R.string.groups_add_members))
                }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
