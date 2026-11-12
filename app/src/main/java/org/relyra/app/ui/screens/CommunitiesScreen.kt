package org.relyra.app.ui.screens

import androidx.annotation.StringRes
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject
import org.relyra.app.IdentityState
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.community.CommunityManifest
import org.relyra.app.core.database.CommunityEntity

@Composable
fun CommunitiesScreen(vm: MainViewModel) {
    val saved by vm.savedCommunities.collectAsStateWithLifecycle()
    val discovered by vm.discoveredCommunities.collectAsStateWithLifecycle()
    val communityEvents by vm.communityEvents.collectAsStateWithLifecycle()
    val reportsByCommunity by vm.communityReports.collectAsStateWithLifecycle()
    val postsByCommunity by vm.communityPosts.collectAsStateWithLifecycle()
    val reportAudits by vm.communityReportAudits.collectAsStateWithLifecycle()
    val busy by vm.communityBusy.collectAsStateWithLifecycle()
    val identity by vm.identity.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showManage by rememberSaveable { mutableStateOf(false) }
    var showReport by rememberSaveable { mutableStateOf(false) }
    var showModerate by rememberSaveable { mutableStateOf(false) }
    var showReports by rememberSaveable { mutableStateOf(false) }
    var showInvite by rememberSaveable { mutableStateOf(false) }
    var showInvitationCode by rememberSaveable { mutableStateOf(false) }
    var showAcceptInvitation by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var visibility by rememberSaveable { mutableStateOf("public") }
    var moderators by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf("ban") }
    var subject by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("community_rules") }
    var reportCategory by rememberSaveable { mutableStateOf("spam") }
    var reportSummary by rememberSaveable { mutableStateOf("") }
    var inviteMemberId by rememberSaveable { mutableStateOf("") }
    var invitationCode by rememberSaveable { mutableStateOf("") }
    var acceptedInvitationCode by rememberSaveable { mutableStateOf("") }
    var postBody by rememberSaveable { mutableStateOf("") }
    var reviewNote by rememberSaveable { mutableStateOf("") }
    var auditReportId by rememberSaveable { mutableStateOf<String?>(null) }

    val savedById = saved.associateBy { it.communityId }
    val selectedEntity = selectedId?.let(savedById::get)
    val selectedManifest = selectedEntity?.let { runCatching { manifestFromEntity(it) }.getOrNull() }
        ?: selectedId?.let { id -> discovered.find { it.communityId == id } }
    val ownId = (identity as? IdentityState.Present)?.identity?.publicId
    val isOwner = selectedManifest != null && ownId?.equals(selectedManifest.ownerPublicId, true) == true
    val isModerator = selectedManifest != null && ownId != null && (
        isOwner || selectedManifest.moderatorPublicIds.any { it.equals(ownId, true) }
    )
    val events = selectedId?.let { communityEvents[it] }.orEmpty()
    val reports = selectedId?.let { reportsByCommunity[it] }.orEmpty()
    val posts = selectedId?.let { postsByCommunity[it] }.orEmpty()
    LaunchedEffect(selectedManifest?.communityId) {
        selectedManifest?.let { vm.loadCommunityPosts(it) }
    }

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
        OutlinedButton(
            onClick = { showAcceptInvitation = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        ) { Text(stringResource(R.string.communities_accept_invitation)) }
        if (busy) Text(stringResource(R.string.communities_working), style = MaterialTheme.typography.labelSmall)

        Text(stringResource(R.string.communities_saved), style = MaterialTheme.typography.titleMedium)
        if (saved.isEmpty() && discovered.isEmpty()) {
            Text(stringResource(R.string.communities_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(saved, key = { it.communityId }) { entity ->
                val manifest = runCatching { manifestFromEntity(entity) }.getOrNull()
                CommunityCard(
                    name = manifest?.name ?: entity.communityId,
                    detail = manifest?.description.orEmpty(),
                    visibility = manifest?.visibility.orEmpty(),
                    selected = entity.communityId == selectedId,
                    onClick = {
                        selectedId = entity.communityId
                        vm.loadCommunityEvents(entity.communityId)
                        manifest?.let { vm.loadCommunityPosts(it) }
                    },
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
                        onClick = {
                            selectedId = manifest.communityId
                            vm.saveDiscoveredCommunity(manifest)
                            vm.loadCommunityPosts(manifest)
                        },
                    )
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
            Text(
                stringResource(R.string.communities_owner, selectedManifest.ownerPublicId.take(14) + "…"),
                style = MaterialTheme.typography.bodySmall,
            )
            if (selectedEntity == null) {
                Button(onClick = { vm.saveDiscoveredCommunity(selectedManifest) }) {
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
            if (isOwner && selectedManifest.visibility == "private") {
                OutlinedButton(onClick = { showInvite = true }) {
                    Text(stringResource(R.string.communities_invite_member))
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
            if (isModerator) {
                OutlinedButton(onClick = {
                    vm.loadCommunityReports(selectedManifest)
                    showReports = true
                }) {
                    Text(stringResource(R.string.communities_view_reports))
                }
            }
            if (selectedManifest.visibility == "public" || isOwner || selectedEntity?.localRole == "MEMBER") {
                Text(stringResource(R.string.communities_posts_title), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = postBody,
                    onValueChange = { postBody = it.take(4_000) },
                    label = { Text(stringResource(R.string.communities_post_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )
                Button(
                    onClick = {
                        vm.publishCommunityPost(selectedManifest, postBody)
                        postBody = ""
                    },
                    enabled = postBody.isNotBlank(),
                ) { Text(stringResource(R.string.communities_post_publish)) }
            }
            if (posts.isEmpty()) {
                Text(stringResource(R.string.communities_post_empty), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                posts.take(20).forEach { post ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(post.authorPublicId.take(14) + " · #" + post.sequence,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary)
                        Text(post.body, style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider()
                    }
                }
            }
            if (showReports && isModerator) {
                Text(stringResource(R.string.communities_recent_reports), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = reviewNote,
                    onValueChange = { reviewNote = it.take(256) },
                    label = { Text(stringResource(R.string.communities_review_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (reports.isEmpty()) {
                    Text(
                        stringResource(R.string.communities_no_reports),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    reports.take(20).forEach { row ->
                        val report = row.report
                        val auditKey = "${selectedManifest.communityId}/${report.reportId}"
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                report.category + " · " + report.reporterPublicId.take(12) +
                                    " · " + stringResource(communityReportStatusLabel(row.status)),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(report.summary, style = MaterialTheme.typography.bodySmall)
                            row.decisionNote?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (row.status == "open" || (row.status == "in_review" && row.reviewerPublicId.equals(ownId, true))) {
                                    TextButton(onClick = {
                                        vm.reviewCommunityReport(selectedManifest, report.reportId, "in_review", reviewNote)
                                    }) { Text(stringResource(R.string.communities_review_take)) }
                                }
                                if (isOwner && row.status != "resolved" && row.status != "dismissed") {
                                    TextButton(
                                        onClick = {
                                            vm.reviewCommunityReport(selectedManifest, report.reportId, "resolved", reviewNote)
                                        },
                                        enabled = reviewNote.isNotBlank(),
                                    ) { Text(stringResource(R.string.communities_review_resolve)) }
                                    TextButton(
                                        onClick = {
                                            vm.reviewCommunityReport(selectedManifest, report.reportId, "dismissed", reviewNote)
                                        },
                                        enabled = reviewNote.isNotBlank(),
                                    ) { Text(stringResource(R.string.communities_review_dismiss)) }
                                }
                                TextButton(onClick = {
                                    auditReportId = report.reportId
                                    vm.loadCommunityReportAudit(selectedManifest, report.reportId)
                                }) { Text(stringResource(R.string.communities_review_audit)) }
                            }
                            if (auditReportId == report.reportId) {
                                reportAudits[auditKey].orEmpty().forEach { audit ->
                                    Text(
                                        "#${audit.order} · ${stringResource(communityReportStatusLabel(audit.review.status))} · ${audit.review.reviewerPublicId.take(10)}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    if (audit.review.note.isNotBlank()) {
                                        Text(audit.review.note, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
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

    if (showInvite && selectedId != null) {
        AlertDialog(
            onDismissRequest = { showInvite = false },
            title = { Text(stringResource(R.string.communities_invite_member)) },
            text = {
                OutlinedTextField(
                    inviteMemberId,
                    { inviteMemberId = it.take(120) },
                    label = { Text(stringResource(R.string.communities_invitee_id)) },
                    supportingText = { Text(stringResource(R.string.communities_invitation_privacy)) },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.createPrivateCommunityInvitation(selectedId!!, inviteMemberId.trim()) { code ->
                            invitationCode = code
                            showInvite = false
                            showInvitationCode = true
                        }
                    },
                    enabled = inviteMemberId.isNotBlank(),
                ) { Text(stringResource(R.string.communities_create_invitation)) }
            },
            dismissButton = { TextButton(onClick = { showInvite = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showInvitationCode) {
        AlertDialog(
            onDismissRequest = { showInvitationCode = false },
            title = { Text(stringResource(R.string.communities_invitation_created)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.communities_invitation_share_warning))
                    OutlinedTextField(
                        invitationCode,
                        onValueChange = {},
                        readOnly = true,
                        minLines = 4,
                        maxLines = 8,
                    )
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(invitationCode)) }) {
                        Text(stringResource(R.string.communities_copy_invitation))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showInvitationCode = false }) { Text(stringResource(R.string.close)) } },
        )
    }

    if (showAcceptInvitation) {
        AlertDialog(
            onDismissRequest = { showAcceptInvitation = false },
            title = { Text(stringResource(R.string.communities_accept_invitation)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.communities_accept_invitation_help))
                    OutlinedTextField(
                        acceptedInvitationCode,
                        { acceptedInvitationCode = it.take(16_384) },
                        label = { Text(stringResource(R.string.communities_invitation_code)) },
                        minLines = 4,
                        maxLines = 8,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.acceptPrivateCommunityInvitation(acceptedInvitationCode) { id ->
                            selectedId = id
                            showAcceptInvitation = false
                            acceptedInvitationCode = ""
                            vm.loadCommunityEvents(id)
                        }
                    },
                    enabled = acceptedInvitationCode.isNotBlank(),
                ) { Text(stringResource(R.string.communities_accept_invitation)) }
            },
            dismissButton = { TextButton(onClick = { showAcceptInvitation = false }) { Text(stringResource(R.string.cancel)) } },
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
                    val actionLabel = when (action) {
                        "ban" -> stringResource(R.string.communities_action_ban)
                        "mute" -> stringResource(R.string.communities_action_mute)
                        "slow_mode" -> stringResource(R.string.communities_action_slow_mode)
                        "allow_post" -> stringResource(R.string.communities_action_allow_post)
                        "unban" -> stringResource(R.string.communities_action_unban)
                        else -> stringResource(R.string.communities_action_remove_member)
                    }
                    OutlinedButton(onClick = { expanded = true }) { Text(actionLabel) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        val availableActions = if (isOwner) {
                            listOf(
                                "ban" to R.string.communities_action_ban,
                                "mute" to R.string.communities_action_mute,
                                "slow_mode" to R.string.communities_action_slow_mode,
                                "unban" to R.string.communities_action_unban,
                                "remove_member" to R.string.communities_action_remove_member,
                                "allow_post" to R.string.communities_action_allow_post,
                            )
                        } else {
                            listOf(
                                "ban" to R.string.communities_action_ban,
                                "mute" to R.string.communities_action_mute,
                                "slow_mode" to R.string.communities_action_slow_mode,
                            )
                        }
                        availableActions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = { action = value; expanded = false },
                            )
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

@StringRes
private fun communityReportStatusLabel(status: String): Int = when (status) {
    "in_review" -> R.string.communities_report_status_in_review
    "resolved" -> R.string.communities_report_status_resolved
    "dismissed" -> R.string.communities_report_status_dismissed
    else -> R.string.communities_report_status_open
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
