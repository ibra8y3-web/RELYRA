package org.relyra.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject
import org.relyra.app.MainViewModel
import org.relyra.app.IdentityState
import org.relyra.app.R
import org.relyra.app.core.database.CommunityEntity
import org.relyra.app.core.community.CommunityManifest

@Composable
fun CommunitiesScreen(vm: MainViewModel) {
    val saved by vm.savedCommunities.collectAsStateWithLifecycle()
    val discovered by vm.discoveredCommunities.collectAsStateWithLifecycle()
    val communityEvents by vm.communityEvents.collectAsStateWithLifecycle()
    val busy by vm.communityBusy.collectAsStateWithLifecycle()
    val identity by vm.identity.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showManage by rememberSaveable { mutableStateOf(false) }
    var showReport by rememberSaveable { mutableStateOf(false) }
    var showModerate by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var visibility by rememberSaveable { mutableStateOf("public") }
    var moderators by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf("mute") }
    var subject by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("community_rules") }
    var reportCategory by rememberSaveable { mutableStateOf("spam") }
    var reportSummary by rememberSaveable { mutableStateOf("") }

    val savedById = saved.associateBy { it.communityId }
    val selectedEntity = selectedId?.let(savedById::get)
    val selectedPublic = selectedId?.let { id -> discovered.find { it.communityId == id } }
    val selectedManifest = selectedEntity?.let(::manifestFromEntity) ?: selectedPublic
    val ownId = (identity as? IdentityState.Present)?.identity?.publicId
    val isOwner = selectedManifest != null && ownId?.equals(selectedManifest.ownerPublicId, true) == true
    val isModerator = selectedManifest != null && ownId != null && (
        ownId.equals(selectedManifest.ownerPublicId, true) ||
            selectedManifest.moderatorPublicIds.any { it.equals(ownId, true) }
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.communities_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { vm.discoverCommunities() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.communities_discover))
            }
            OutlinedButton(onClick = { showCreate = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.communities_create))
            }
        }
        if (busy) Text(stringResource(R.string.communities_working), style = MaterialTheme.typography.labelSmall)

        Text(stringResource(R.string.communities_saved), style = MaterialTheme.typography.titleMedium)
        if (saved.isEmpty()) {
            Text(stringResource(R.string.communities_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                modifier = Modifier.weight(if (selectedManifest == null) 1f else 0.65f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(saved, key = { it.communityId }) { entity ->
                    val manifest = runCatching { manifestFromEntity(entity) }.getOrNull()
                    CommunityCard(
                        name = manifest?.name ?: entity.communityId,
                        detail = manifest?.description.orEmpty(),
                        visibility = manifest?.visibility.orEmpty(),
                        selected = entity.communityId == selectedId,
                        onClick = { selectedId = entity.communityId; vm.loadCommunityEvents(entity.communityId) },
                    )
                }
                if (discovered.isNotEmpty()) {
                    item { Text(stringResource(R.string.communities_public_results), style = MaterialTheme.typography.titleMedium) }
                    items(discovered.filter { it.communityId !in savedById }, key = { it.communityId }) { manifest ->
                        CommunityCard(
                            name = manifest.name,
                            detail = manifest.description,
                            visibility = manifest.visibility,
                            selected = manifest.communityId == selectedId,
                            onClick = { selectedId = manifest.communityId },
                        )
                    }
                }
            }
        }

        if (selectedManifest != null && selectedId != null) {
            HorizontalDivider()
            Text(selectedManifest.name, style = MaterialTheme.typography.titleLarge)
            Text(selectedManifest.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    if (selectedManifest.visibility == "public") R.string.communities_visibility_public
                    else R.string.communities_visibility_private
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(stringResource(R.string.communities_owner, selectedManifest.ownerPublicId.take(14) + "…"),
                style = MaterialTheme.typography.bodySmall)
            if (selectedEntity == null) {
                Button(onClick = { vm.saveDiscoveredCommunity(selectedManifest); selectedId = selectedManifest.communityId }) {
                    Text(stringResource(R.string.communities_save))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.loadCommunityEvents(selectedManifest.communityId) }) {
                    Text(stringResource(R.string.communities_refresh_events))
                }
                OutlinedButton(onClick = { showReport = true }) {
                    Text(stringResource(R.string.communities_report))
                }
            }
            if (isOwner) {
                OutlinedButton(onClick = {
                    moderators = selectedManifest.moderatorPublicIds.joinToString(",")
                    showManage = true
                }) { Text(stringResource(R.string.communities_manage_moderators)) }
            }
            if (isModerator) {
                OutlinedButton(onClick = { showModerate = true }) {
                    Text(stringResource(R.string.communities_moderate))
                }
            }
            val events = communityEvents[selectedManifest.communityId].orEmpty()
            if (events.isNotEmpty()) {
                Text(stringResource(R.string.communities_recent_actions), style = MaterialTheme.typography.titleSmall)
                events.takeLast(8).asReversed().forEach { event ->
                    Text(
                        event.action + " · " + event.reasonCode + " · " + event.actorPublicId.take(10),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (saved.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.communities_create_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.communities_name)) })
                    OutlinedTextField(description, { description = it.take(1000) }, label = { Text(stringResource(R.string.communities_description_field)) })
                    Row {
                        RadioButton(selected = visibility == "public", onClick = { visibility = "public" })
                        Text(stringResource(R.string.communities_visibility_public), modifier = Modifier.padding(top = 12.dp))
                        RadioButton(selected = visibility == "private", onClick = { visibility = "private" })
                        Text(stringResource(R.string.communities_visibility_private), modifier = Modifier.padding(top = 12.dp))
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.createCommunity(name, description, visibility) { id ->
                        selectedId = id
                        showCreate = false
                        name = ""
                        description = ""
                    }
                }, enabled = name.isNotBlank()) { Text(stringResource(R.string.communities_create)) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showManage && selectedManifest != null && selectedId != null) {
        AlertDialog(
            onDismissRequest = { showManage = false },
            title = { Text(stringResource(R.string.communities_manage_moderators)) },
            text = {
                OutlinedTextField(
                    moderators, { moderators = it.take(32 * 100) },
                    label = { Text(stringResource(R.string.communities_moderators_hint)) },
                    supportingText = { Text(stringResource(R.string.communities_moderators_help)) },
                    minLines = 3,
                )
            },
            confirmButton = {
                Button(onClick = {
                    val ids = moderators.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    vm.updateCommunityModerators(selectedId!!, ids)
                    showManage = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { showManage = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showModerate && selectedManifest != null && selectedId != null) {
        AlertDialog(
            onDismissRequest = { showModerate = false },
            title = { Text(stringResource(R.string.communities_moderate)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var expanded by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { expanded = true }) { Text(action) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("ban", "mute", "slow_mode", "remove_member", "allow_post").forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { action = option; expanded = false })
                        }
                    }
                    OutlinedTextField(subject, { subject = it.take(100) }, label = { Text(stringResource(R.string.communities_subject_id)) })
                    OutlinedTextField(reason, { reason = it.take(32) }, label = { Text(stringResource(R.string.communities_reason_code)) })
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.moderateCommunity(selectedId!!, action, subject, reason)
                    showModerate = false
                    subject = ""
                }, enabled = subject.isNotBlank()) { Text(stringResource(R.string.communities_apply_action)) }
            },
            dismissButton = { TextButton(onClick = { showModerate = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showReport && selectedManifest != null && selectedId != null) {
        AlertDialog(
            onDismissRequest = { showReport = false },
            title = { Text(stringResource(R.string.communities_report)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(reportCategory, { reportCategory = it.take(48) }, label = { Text(stringResource(R.string.communities_report_category)) })
                    OutlinedTextField(reportSummary, { reportSummary = it.take(512) }, label = { Text(stringResource(R.string.communities_report_summary)) }, minLines = 3)
                    Text(stringResource(R.string.communities_report_privacy), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.reportCommunity(selectedId!!, reportCategory, reportSummary)
                    showReport = false
                    reportSummary = ""
                }, enabled = reportCategory.isNotBlank() && reportSummary.isNotBlank()) {
                    Text(stringResource(R.string.communities_report_submit))
                }
            },
            dismissButton = { TextButton(onClick = { showReport = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CommunityCard(
    name: String,
    detail: String,
    visibility: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            if (visibility.isNotBlank()) Text(visibility, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private fun manifestFromEntity(entity: CommunityEntity): CommunityManifest {
    val json = JSONObject(entity.manifestJson)
    val mods = json.optJSONArray("moderatorPublicIds")
    val moderatorIds = mods?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty()
    return CommunityManifest(
        json.getString("communityId"), json.getString("ownerPublicId"),
        json.getString("name"), json.optString("description"), json.getString("visibility"),
        moderatorIds, json.getLong("policyVersion"), json.getLong("createdAt"),
        json.getLong("updatedAt"), json.getString("signature"),
    )
}

