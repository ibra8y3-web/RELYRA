package org.relyra.app.ui.screens

import androidx.compose.foundation.clickable
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
    authenticate: (title: String, allowIfUnavailable: Boolean, onSuccess: () -> Unit) -> Unit,
    onOpenLog: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val lock by vm.lockEnabled.collectAsStateWithLifecycle()
    val receipts by vm.readReceipts.collectAsStateWithLifecycle()
    val authTitle = stringResource(R.string.lock_auth_title)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_receipts)) },
                supportingContent = { Text(stringResource(R.string.settings_receipts_desc)) },
                trailingContent = { Switch(checked = receipts, onCheckedChange = { vm.setReadReceipts(it) }) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_log)) },
                supportingContent = { Text(stringResource(R.string.settings_log_desc)) },
                modifier = Modifier.clickable(onClick = onOpenLog),
            )
        }
        NotificationSettingsCard(vm)
        PrivacyAppearanceCards(vm)
        SectionCard(stringResource(R.string.net_title)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.net_title)) },
                supportingContent = { Text(stringResource(R.string.settings_network_desc)) },
                modifier = Modifier.clickable(onClick = onOpenNetwork),
            )
        }
        DataStorageCard(vm, onOpenDiagnostics)
        SectionCard(stringResource(R.string.tools_title)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.tools_title)) },
                supportingContent = { Text(stringResource(R.string.tools_desc)) },
                modifier = Modifier.clickable(onClick = onOpenTools),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.diag_title)) },
                supportingContent = { Text(stringResource(R.string.diag_desc)) },
                modifier = Modifier.clickable(onClick = onOpenDiagnostics),
            )
        }
        SectionCard(stringResource(R.string.settings_about)) {
            Box(Modifier.padding(bottom = 8.dp)) { BrandMark(56.dp) }
            InfoRow(stringResource(R.string.app_name), stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
            Text(stringResource(R.string.tagline), color = MaterialTheme.colorScheme.secondary)
        }
        SectionCard(stringResource(R.string.settings_libs)) {
            Text("Google Tink — Apache-2.0")
            Text("SQLCipher for Android — BSD-style")
            Text("AndroidX (Room, Compose, Biometric, Navigation) — Apache-2.0")
            Text("ZXing — Apache-2.0")
            Text("ZXing Android Embedded — Apache-2.0")
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
