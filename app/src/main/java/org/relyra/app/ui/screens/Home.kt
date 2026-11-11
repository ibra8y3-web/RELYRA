package org.relyra.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
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
import org.relyra.app.ui.components.InfoRow
import org.relyra.app.ui.components.SectionCard
import org.relyra.app.ui.components.rememberNotificationRequester
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onOpenNetwork: () -> Unit,
    onAddContact: () -> Unit,
) {
    val identity by vm.identity.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val eventCount by vm.eventCount.collectAsStateWithLifecycle()
    val lock by vm.lockEnabled.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    val relays by vm.relays.collectAsStateWithLifecycle()
    val fileServers by vm.fileServers.collectAsStateWithLifecycle()
    val connected by vm.relayConnected.collectAsStateWithLifecycle()
    val bluetoothEnabled by vm.bluetoothEnabled.collectAsStateWithLifecycle()
    val wifiDirectEnabled by vm.wifiDirectEnabled.collectAsStateWithLifecycle()
    val nearbyEnabled by vm.nearbyEnabled.collectAsStateWithLifecycle()
    val torSettings by vm.torProxySettings.collectAsStateWithLifecycle()
    val notifOn by vm.settings.notifications.collectAsStateWithLifecycle()
    val keep by vm.settings.keepConnected.collectAsStateWithLifecycle()
    var tick by remember { mutableStateOf(0) }
    val notifAllowed = remember(tick, notifOn) { vm.notificationsAllowed() && notifOn }
    val askNotifications = rememberNotificationRequester { granted ->
        if (granted) vm.settings.setNotifications(true)
        tick += 1
    }
    val me = (identity as? IdentityState.Present)?.identity ?: return

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(me.displayName, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(me.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        me.publicId.take(12) + "…" + me.publicId.takeLast(6),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val relayDone = relays.any { it.enabled }
        val contactDone = contacts.isNotEmpty()
        if (!relayDone || !contactDone || !notifAllowed) {
            SectionCard(stringResource(R.string.start_title)) {
                Text(stringResource(R.string.start_body), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                StartStep(relayDone, R.string.start_relay, R.string.start_relay_desc, R.string.start_open_network, onOpenNetwork)
                HorizontalDivider()
                StartStep(contactDone, R.string.start_contact, R.string.start_contact_desc, R.string.contacts_add, onAddContact)
                HorizontalDivider()
                StartStep(notifAllowed, R.string.start_notif, R.string.start_notif_desc, R.string.start_allow, askNotifications)
            }
        }
        SectionCard(stringResource(R.string.home_security)) {
            InfoRow(stringResource(R.string.home_identity), stringResource(
                R.string.home_identity_value, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(me.createdAt))))
            InfoRow(stringResource(R.string.home_database), stringResource(R.string.home_database_value))
            InfoRow(stringResource(R.string.home_keys), stringResource(R.string.home_keys_value))
            InfoRow(
                stringResource(R.string.home_lock),
                stringResource(if (lock) R.string.state_on else R.string.state_off),
            )
            InfoRow(stringResource(R.string.home_events), eventCount.toString())
        }
        SectionCard(stringResource(R.string.nav_contacts)) {
            InfoRow(stringResource(R.string.home_contacts), contacts.size.toString())
            InfoRow(stringResource(R.string.home_verified), contacts.count { it.verified }.toString())
            InfoRow(stringResource(R.string.home_blocked), contacts.count { it.blocked }.toString())
            InfoRow(stringResource(R.string.home_pending), pending.toString())
        }
        SectionCard(stringResource(R.string.net_title)) {
            InfoRow(stringResource(R.string.home_relays), "${relays.count { it.enabled }} / ${relays.size}")
            InfoRow(stringResource(R.string.home_relays_connected), connected.values.count { it }.toString())
            InfoRow(stringResource(R.string.home_keep), stringResource(if (keep) R.string.state_on else R.string.state_off))
            InfoRow(stringResource(R.string.net_transport), stringResource(if (relays.any { it.enabled }) R.string.net_transport_active_short else R.string.net_transport_inactive_short))
            InfoRow(stringResource(R.string.home_file_servers), "${fileServers.count { it.enabled }} / ${fileServers.size}")
            InfoRow(stringResource(R.string.home_bluetooth), stringResource(if (bluetoothEnabled) R.string.state_on else R.string.state_off))
            InfoRow(stringResource(R.string.home_wifi_direct), stringResource(if (wifiDirectEnabled) R.string.state_on else R.string.state_off))
            InfoRow(stringResource(R.string.home_nearby_lan), stringResource(if (nearbyEnabled) R.string.state_on else R.string.state_off))
            InfoRow(stringResource(R.string.home_tor), stringResource(if (torSettings.enabled) R.string.state_on else R.string.state_off))
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.home_manage_network)) },
                supportingContent = { Text(stringResource(R.string.settings_network_desc)) },
                modifier = Modifier.clickable(onClick = onOpenNetwork),
            )
        }
        SectionCard(stringResource(R.string.home_build)) {
            Text(stringResource(R.string.home_build_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StartStep(done: Boolean, title: Int, desc: Int, action: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text((if (done) "✓  " else "○  ") + stringResource(title)) },
        supportingContent = { Text(stringResource(desc)) },
        trailingContent = {
            if (!done) TextButton(onClick = onClick) { Text(stringResource(action)) }
        },
    )
}
