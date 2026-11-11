package org.relyra.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.IdentityState
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.ui.components.Avatar
import org.relyra.app.ui.components.SectionCard
import org.relyra.app.ui.components.rememberNotificationRequester

/**
 * User-facing service dashboard. Only operational states and user actions are exposed here;
 * key material, database internals and diagnostic counters remain in their dedicated screens.
 */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onOpenNetwork: () -> Unit,
    onAddContact: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenPdfReader: () -> Unit,
) {
    val identity by vm.identity.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val relays by vm.relays.collectAsStateWithLifecycle()
    val connected by vm.relayConnected.collectAsStateWithLifecycle()
    val fileServers by vm.fileServers.collectAsStateWithLifecycle()
    val bluetoothEnabled by vm.bluetoothEnabled.collectAsStateWithLifecycle()
    val wifiDirectEnabled by vm.wifiDirectEnabled.collectAsStateWithLifecycle()
    val nearbyEnabled by vm.nearbyEnabled.collectAsStateWithLifecycle()
    val torSettings by vm.torProxySettings.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    val notifOn by vm.settings.notifications.collectAsStateWithLifecycle()
    var notificationRefresh by remember { mutableStateOf(0) }
    val notificationsReady = remember(notificationRefresh, notifOn) { vm.notificationsAllowed() && notifOn }
    val requestNotifications = rememberNotificationRequester { granted ->
        if (granted) vm.settings.setNotifications(true)
        notificationRefresh++
    }
    val keepConnected by vm.settings.keepConnected.collectAsStateWithLifecycle()
    val me = (identity as? IdentityState.Present)?.identity ?: return
    val enabledRelays = relays.count { it.enabled }
    val connectedRelays = connected.values.count { it }
    val enabledFiles = fileServers.count { it.enabled }
    val localTransports = listOf(bluetoothEnabled, wifiDirectEnabled, nearbyEnabled).count { it }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(me.displayName, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_dashboard_greeting), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(me.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        me.publicId.take(10) + "…" + me.publicId.takeLast(5),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenNetwork) { Text(stringResource(R.string.home_manage)) }
            }
        }

        SectionCard(stringResource(R.string.home_dashboard_services)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                ServiceTile(
                    title = stringResource(R.string.home_service_messages),
                    status = if (enabledRelays > 0) stringResource(R.string.home_service_configured) else stringResource(R.string.home_service_setup),
                    detail = stringResource(R.string.home_service_relays_detail, connectedRelays, enabledRelays),
                    onClick = onOpenNetwork,
                    modifier = Modifier.weight(1f),
                )
                ServiceTile(
                    title = stringResource(R.string.home_service_files),
                    status = if (enabledFiles > 0) stringResource(R.string.home_service_configured) else stringResource(R.string.home_service_setup),
                    detail = stringResource(R.string.home_service_files_detail, enabledFiles),
                    onClick = onOpenNetwork,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                ServiceTile(
                    title = stringResource(R.string.home_service_nearby),
                    status = if (localTransports > 0) stringResource(R.string.home_service_enabled) else stringResource(R.string.home_service_disabled),
                    detail = stringResource(R.string.home_service_nearby_detail, localTransports),
                    onClick = onOpenNetwork,
                    modifier = Modifier.weight(1f),
                )
                ServiceTile(
                    title = stringResource(R.string.home_service_tor),
                    status = if (torSettings.enabled) stringResource(R.string.state_on) else stringResource(R.string.state_off),
                    detail = stringResource(if (torSettings.strict) R.string.home_service_tor_strict else R.string.home_service_tor_flexible),
                    onClick = onOpenNetwork,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.home_network_details)) },
                supportingContent = { Text(stringResource(R.string.settings_network_desc)) },
                modifier = Modifier.clickable(onClick = onOpenNetwork),
            )
        }

        SectionCard(stringResource(R.string.home_dashboard_activity)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.nav_chats)) },
                supportingContent = { Text(stringResource(R.string.home_pending_detail, pending)) },
                trailingContent = { Text(pending.toString(), style = MaterialTheme.typography.titleMedium) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.nav_contacts)) },
                supportingContent = { Text(stringResource(R.string.home_contacts_detail, contacts.size)) },
                trailingContent = { Text(contacts.size.toString(), style = MaterialTheme.typography.titleMedium) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.home_connection_service)) },
                supportingContent = { Text(stringResource(if (keepConnected) R.string.home_connection_on else R.string.home_connection_off)) },
            )
        }

        SectionCard(stringResource(R.string.home_dashboard_tools)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.tools_title)) },
                supportingContent = { Text(stringResource(R.string.tools_desc)) },
                modifier = Modifier.clickable(onClick = onOpenTools),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.vault_title)) },
                supportingContent = { Text(stringResource(R.string.vault_desc)) },
                modifier = Modifier.clickable(onClick = onOpenVault),
            )
            HorizontalDivider()
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

        if (enabledRelays == 0 || contacts.isEmpty() || !notificationsReady) {
            SectionCard(stringResource(R.string.start_title)) {
                Text(stringResource(R.string.start_body), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (enabledRelays == 0) {
                    StartStep(R.string.start_relay, R.string.start_relay_desc, R.string.start_open_network, onOpenNetwork)
                }
                if (contacts.isEmpty()) {
                    StartStep(R.string.start_contact, R.string.start_contact_desc, R.string.contacts_add, onAddContact)
                }
                if (!notificationsReady) {
                    StartStep(R.string.start_notif, R.string.start_notif_desc, R.string.start_allow, requestNotifications)
                }
            }
        }
    }
}

@Composable
private fun ServiceTile(
    title: String,
    status: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StartStep(title: Int, desc: Int, action: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(desc)) },
        trailingContent = { TextButton(onClick = onClick) { Text(stringResource(action)) } },
    )
}
