package org.relyra.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.relyra.app.ui.components.InfoRow
import org.relyra.app.ui.components.SectionCard
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(vm: MainViewModel) {
    val identity by vm.identity.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val eventCount by vm.eventCount.collectAsStateWithLifecycle()
    val lock by vm.lockEnabled.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    val relays by vm.relays.collectAsStateWithLifecycle()
    val fileServers by vm.fileServers.collectAsStateWithLifecycle()
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
            InfoRow(stringResource(R.string.net_transport), stringResource(if (relays.any { it.enabled }) R.string.net_transport_active_short else R.string.net_transport_inactive_short))
            InfoRow(stringResource(R.string.home_file_servers), "${fileServers.count { it.enabled }} / ${fileServers.size}")
        }
        SectionCard(stringResource(R.string.home_build)) {
            Text(stringResource(R.string.home_build_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
