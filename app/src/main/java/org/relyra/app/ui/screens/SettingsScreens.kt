package org.relyra.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.BuildConfig
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.database.EventType
import org.relyra.app.ui.components.BrandMark
import org.relyra.app.ui.components.InfoRow
import org.relyra.app.ui.components.SectionCard
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onOpenNetwork: () -> Unit,
    onOpenIdentity: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenBrowserDocs: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.settings_hub_subtitle), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsHubTile(R.string.settings_cat_connections, R.string.settings_cat_connections_desc,
            Icons.Default.Wifi, onOpenNetwork)
        SettingsHubTile(R.string.settings_cat_identity, R.string.settings_cat_identity_desc,
            Icons.Default.Person, onOpenIdentity)
        SettingsHubTile(R.string.settings_cat_privacy, R.string.settings_cat_privacy_desc,
            Icons.Default.Security, onOpenPrivacy)
        SettingsHubTile(R.string.settings_cat_storage, R.string.settings_cat_storage_desc,
            Icons.Default.Storage, onOpenStorage)
        SettingsHubTile(R.string.settings_cat_browser, R.string.settings_cat_browser_desc,
            Icons.Default.Language, onOpenBrowserDocs)
        SettingsHubTile(R.string.settings_cat_tools, R.string.settings_cat_tools_desc,
            Icons.Default.Build, onOpenTools)
        SettingsHubTile(R.string.settings_cat_notifications, R.string.settings_cat_notifications_desc,
            Icons.Default.Notifications, onOpenNotifications)
        SettingsHubTile(R.string.settings_cat_diagnostics, R.string.settings_cat_diagnostics_desc,
            Icons.Default.BugReport, onOpenDiagnostics)
        SectionCard(stringResource(R.string.settings_about)) {
            Box(Modifier.padding(bottom = 8.dp)) { BrandMark(48.dp) }
            InfoRow(stringResource(R.string.app_name), stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
            Text(stringResource(R.string.tagline), color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun SettingsHubTile(
    title: Int,
    description: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            androidx.compose.material3.Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                androidx.compose.material3.Icon(icon, contentDescription = null,
                    modifier = Modifier.padding(11.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(description), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PrivacySecuritySettingsScreen(
    vm: MainViewModel,
    authenticate: (title: String, allowIfUnavailable: Boolean, onSuccess: () -> Unit) -> Unit,
    onOpenLog: () -> Unit,
) {
    val lock by vm.lockEnabled.collectAsStateWithLifecycle()
    val receipts by vm.readReceipts.collectAsStateWithLifecycle()
    val authTitle = stringResource(R.string.lock_auth_title)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(stringResource(R.string.settings_security)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_lock)) },
                supportingContent = { Text(stringResource(R.string.settings_lock_desc)) },
                trailingContent = {
                    Switch(checked = lock, onCheckedChange = { want ->
                        authenticate(authTitle, false) { vm.setLockEnabled(want) }
                    })
                },
            )
        }
        PrivacyAppearanceCards(vm)
        SectionCard(stringResource(R.string.settings_log)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_log)) },
                supportingContent = { Text(stringResource(R.string.settings_log_desc)) },
                modifier = Modifier.clickable(onClick = onOpenLog),
            )
        }
    }
}

@Composable
fun NotificationsSettingsScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        NotificationSettingsCard(vm)
    }
}

@Composable
fun StorageSettingsScreen(
    vm: MainViewModel,
    onOpenVault: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(stringResource(R.string.settings_cat_storage)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.vault_title)) },
                supportingContent = { Text(stringResource(R.string.vault_desc)) },
                modifier = Modifier.clickable(onClick = onOpenVault),
            )
        }
        DataStorageCard(vm, onOpenDiagnostics)
        BackupCard(vm)
    }
}

@Composable
fun BrowserDocumentsSettingsScreen(
    onOpenBrowser: () -> Unit,
    onOpenPdfReader: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(stringResource(R.string.settings_cat_browser)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.browser_title)) },
                supportingContent = { Text(stringResource(R.string.browser_settings_desc)) },
                modifier = Modifier.clickable(onClick = onOpenBrowser),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.pdf_title)) },
                supportingContent = { Text(stringResource(R.string.pdf_settings_desc)) },
                modifier = Modifier.clickable(onClick = onOpenPdfReader),
            )
        }
    }
}

@Composable
fun LogScreen(vm: MainViewModel) {
    val events by vm.events.collectAsStateWithLifecycle()
    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.log_empty)) }
        return
    }
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(events, key = { it.id }) { e ->
            ListItem(
                headlineContent = { Text(ctx.getString(eventLabel(e.type)) + if (e.detail.isNotBlank()) ": ${e.detail}" else "") },
                supportingContent = { Text(fmt.format(Date(e.timestamp))) },
            )
            HorizontalDivider()
        }
    }
}

private fun eventLabel(type: String): Int = when (type) {
    EventType.IDENTITY_CREATED -> R.string.ev_identity_created
    EventType.IDENTITY_RESTORED -> R.string.ev_identity_restored
    EventType.RECOVERY_VIEWED -> R.string.ev_recovery_viewed
    EventType.CONTACT_ADDED -> R.string.ev_contact_added
    EventType.CONTACT_VERIFIED -> R.string.ev_contact_verified
    EventType.CONTACT_UNVERIFIED -> R.string.ev_contact_unverified
    EventType.CONTACT_BLOCKED -> R.string.ev_contact_blocked
    EventType.CONTACT_UNBLOCKED -> R.string.ev_contact_unblocked
    EventType.CONTACT_DELETED -> R.string.ev_contact_deleted
    EventType.APP_LOCK_ON -> R.string.ev_lock_on
    EventType.APP_LOCK_OFF -> R.string.ev_lock_off
    else -> R.string.ev_unknown
}
