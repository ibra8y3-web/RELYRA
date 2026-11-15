package org.relyra.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.database.SecurityEventEntity
import org.relyra.app.core.security.EventCategory
import org.relyra.app.core.security.EventStatus
import org.relyra.app.core.security.SecurityClassifier
import org.relyra.app.core.security.SecurityEventFilter
import org.relyra.app.core.security.Severity
import java.text.DateFormat
import java.util.Date

private const val DAY = 24L * 3600 * 1000

@Composable
fun SecurityCenterScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val events by vm.events.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(SecurityEventFilter()) }
    var selected by remember { mutableStateOf<SecurityEventEntity?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val label = { type: String -> ctx.getString(eventLabel(type)) }
    val shown = remember(events, filter) { filter.apply(events, label) }
    val unread = events.count { it.readAt == null && !it.handled }
    val critical = events.count { SecurityClassifier.severity(it.type) >= Severity.WARNING && !it.handled }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sc_summary, events.size, unread), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.sc_attention, critical), style = MaterialTheme.typography.bodySmall,
                    color = if (critical > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { vm.markAllSecurityRead() }, enabled = unread > 0) { Text(stringResource(R.string.sc_mark_all)) }
        }
        OutlinedTextField(
            value = filter.query, onValueChange = { filter = filter.copy(query = it) }, singleLine = true,
            label = { Text(stringResource(R.string.tool_search)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        ChipRow {
            FilterChip(filter.minSeverity == null, { filter = filter.copy(minSeverity = null) }, { Text(stringResource(R.string.sc_all)) })
            Severity.entries.forEach { s ->
                FilterChip(filter.minSeverity == s, { filter = filter.copy(minSeverity = if (filter.minSeverity == s) null else s) },
                    { Text(stringResource(severityLabel(s))) })
            }
        }
        ChipRow {
            FilterChip(filter.category == null, { filter = filter.copy(category = null) }, { Text(stringResource(R.string.sc_all)) })
            EventCategory.entries.forEach { c ->
                FilterChip(filter.category == c, { filter = filter.copy(category = if (filter.category == c) null else c) },
                    { Text(stringResource(categoryLabel(c))) })
            }
        }
        ChipRow {
            EventStatus.entries.forEach { st ->
                FilterChip(filter.status == st, { filter = filter.copy(status = st) }, { Text(stringResource(statusLabel(st))) })
            }
        }
        ChipRow {
            listOf(null to R.string.sc_any_time, DAY to R.string.sc_24h, 7 * DAY to R.string.sc_7d, 30 * DAY to R.string.sc_30d).forEach { (ms, res) ->
                FilterChip(filter.sinceMs == ms, { filter = filter.copy(sinceMs = ms) }, { Text(stringResource(res)) })
            }
        }
        HorizontalDivider()
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(if (events.isEmpty()) R.string.log_empty else R.string.sc_no_match))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { e ->
                    val unreadRow = e.readAt == null && !e.handled
                    ListItem(
                        modifier = Modifier.clickable { selected = e; if (unreadRow) vm.markSecurityRead(e.id) },
                        leadingContent = { Box(Modifier.size(12.dp).background(severityColor(SecurityClassifier.severity(e.type)), CircleShape)) },
                        headlineContent = {
                            Text(label(e.type) + if (e.detail.isNotBlank()) ": ${e.detail}" else "", maxLines = 2,
                                style = if (unreadRow) MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                else MaterialTheme.typography.bodyLarge)
                        },
                        supportingContent = {
                            Text(fmt.format(Date(e.timestamp)) + if (e.handled) " · " + stringResource(R.string.sc_handled) else "")
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    selected?.let { e ->
        val sev = SecurityClassifier.severity(e.type)
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(label(e.type)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.sc_d_severity, stringResource(severityLabel(sev))))
                    Text(stringResource(R.string.sc_d_category, stringResource(categoryLabel(SecurityClassifier.category(e.type)))))
                    Text(stringResource(R.string.sc_d_source, SecurityClassifier.source(e.type)))
                    Text(stringResource(R.string.sc_d_time, fmt.format(Date(e.timestamp))))
                    Text(stringResource(R.string.sc_d_type, e.type), style = MaterialTheme.typography.bodySmall)
                    if (e.detail.isNotBlank()) Text(stringResource(R.string.sc_d_detail, e.detail))
                    Text(stringResource(if (e.handled) R.string.sc_handled else if (e.readAt != null) R.string.sc_read else R.string.sc_unread),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                if (!e.handled) TextButton(onClick = { vm.markSecurityHandled(e.id); selected = null }) { Text(stringResource(R.string.sc_mark_handled)) }
                else TextButton(onClick = { selected = null }) { Text(stringResource(R.string.close)) }
            },
            dismissButton = { if (!e.handled) TextButton(onClick = { selected = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

private fun severityColor(s: Severity): Color = when (s) {
    Severity.INFO -> Color(0xFF4CAF50)
    Severity.NOTICE -> Color(0xFF2196F3)
    Severity.WARNING -> Color(0xFFFF9800)
    Severity.CRITICAL -> Color(0xFFF44336)
}

private fun severityLabel(s: Severity) = when (s) {
    Severity.INFO -> R.string.sev_info
    Severity.NOTICE -> R.string.sev_notice
    Severity.WARNING -> R.string.sev_warning
    Severity.CRITICAL -> R.string.sev_critical
}

private fun categoryLabel(c: EventCategory) = when (c) {
    EventCategory.IDENTITY -> R.string.cat_identity
    EventCategory.CONTACTS -> R.string.cat_contacts
    EventCategory.DEVICES -> R.string.cat_devices
    EventCategory.ACCESS -> R.string.cat_access
    EventCategory.BACKUP -> R.string.cat_backup
    EventCategory.ABUSE -> R.string.cat_abuse
    EventCategory.NETWORK -> R.string.cat_network
    EventCategory.OTHER -> R.string.cat_other
}

private fun statusLabel(s: EventStatus) = when (s) {
    EventStatus.ALL -> R.string.sc_all
    EventStatus.OPEN -> R.string.sc_open
    EventStatus.UNREAD -> R.string.sc_unread
    EventStatus.HANDLED -> R.string.sc_handled
}
