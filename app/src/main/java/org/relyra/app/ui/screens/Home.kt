package org.relyra.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onOpenNetwork: () -> Unit,
    onAddContact: () -> Unit,
) {
    val identity by vm.identity.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val relays by vm.relays.collectAsStateWithLifecycle()
    val connected by vm.relayConnected.collectAsStateWithLifecycle()
    val servers by vm.fileServers.collectAsStateWithLifecycle()
    val bluetooth by vm.bluetoothEnabled.collectAsStateWithLifecycle()
    val wifiDirect by vm.wifiDirectEnabled.collectAsStateWithLifecycle()
    val nearby by vm.nearbyEnabled.collectAsStateWithLifecycle()
    val tor by vm.torProxySettings.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    val me = (identity as? IdentityState.Present)?.identity ?: return
    val relayEnabled = relays.count { it.enabled }
    val relayConnected = connected.values.count { it }
    val fileEnabled = servers.count { it.enabled }
    val nearbyEnabled = listOf(bluetooth, wifiDirect, nearby).count { it }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(me.displayName, 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.home_dashboard_greeting),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(me.displayName, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(me.publicId.take(10) + "…" + me.publicId.takeLast(5),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SectionCard(stringResource(R.string.home_dashboard_services)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeStatusTile(
                    stringResource(R.string.home_service_messages),
                    stringResource(if (relayEnabled > 0) R.string.home_service_configured else R.string.home_service_setup),
                    stringResource(R.string.home_service_relays_detail, relayConnected, relayEnabled),
                    onOpenNetwork, Modifier.weight(1f),
                )
                HomeStatusTile(
                    stringResource(R.string.home_service_files),
                    stringResource(if (fileEnabled > 0) R.string.home_service_configured else R.string.home_service_setup),
                    stringResource(R.string.home_service_files_detail, fileEnabled),
                    onOpenNetwork, Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeStatusTile(
                    stringResource(R.string.home_service_nearby),
                    stringResource(if (nearbyEnabled > 0) R.string.home_service_enabled else R.string.home_service_disabled),
                    stringResource(R.string.home_service_nearby_detail, nearbyEnabled),
                    onOpenNetwork, Modifier.weight(1f),
                )
                HomeStatusTile(
                    stringResource(R.string.home_service_tor),
                    stringResource(if (tor.enabled) R.string.state_on else R.string.state_off),
                    stringResource(when {
                        !tor.enabled -> R.string.home_service_tor_off_detail
                        tor.strict -> R.string.home_service_tor_strict
                        else -> R.string.home_service_tor_flexible
                    }),
                    onOpenNetwork, Modifier.weight(1f),
                )
            }
        }

        SectionCard(stringResource(R.string.home_dashboard_activity)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeCountTile(stringResource(R.string.nav_chats), pending, Modifier.weight(1f))
                HomeCountTile(stringResource(R.string.nav_contacts), contacts.size, Modifier.weight(1f))
            }
            if (contacts.isEmpty()) {
                Text(stringResource(R.string.contacts_empty_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
                androidx.compose.material3.TextButton(onClick = onAddContact) {
                    Text(stringResource(R.string.contacts_add))
                }
            }
        }
    }
}

@Composable
private fun HomeStatusTile(
    title: String,
    status: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2)
        }
    }
}

@Composable
private fun HomeCountTile(title: String, count: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
        }
    }
}
