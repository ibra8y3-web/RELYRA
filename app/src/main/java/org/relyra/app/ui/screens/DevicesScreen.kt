package org.relyra.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.core.database.TrustedDeviceStatus

@Composable
fun DevicesScreen(vm: MainViewModel) {
    val devices by vm.trustedDevices.collectAsStateWithLifecycle()
    val requestCode by vm.deviceCode.collectAsStateWithLifecycle()
    val outputCode by vm.deviceSyncCode.collectAsStateWithLifecycle()
    val status by vm.deviceMessage.collectAsStateWithLifecycle()
    var deviceName by remember { mutableStateOf("") }
    var requestInput by remember { mutableStateOf("") }
    var approvalInput by remember { mutableStateOf("") }
    var syncInput by remember { mutableStateOf("") }
    var revocationInput by remember { mutableStateOf("") }
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val target = selectedDeviceId
        if (uri != null && target != null) vm.exportDeviceSyncToUri(target, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importDeviceSyncFromUri(uri)
    }
    LaunchedEffect(Unit) { vm.refreshTrustedDevices() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.devices_intro), style=MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.devices_register_primary_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick={vm.registerCurrentDevice()},modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.devices_register_primary)) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.devices_pair_title), style=MaterialTheme.typography.titleMedium)
                OutlinedTextField(value=deviceName,onValueChange={deviceName=it},label={Text(stringResource(R.string.devices_name))},modifier=Modifier.fillMaxWidth(),singleLine=true)
                Button(onClick={vm.createDevicePairingRequest(deviceName.ifBlank { "Android device" })},modifier=Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.devices_create_request))
                }
                requestCode?.let { OutlinedTextField(value=it,onValueChange={},readOnly=true,label={Text(stringResource(R.string.devices_request_code))},modifier=Modifier.fillMaxWidth(),minLines=3) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.devices_approve_title),style=MaterialTheme.typography.titleMedium)
                OutlinedTextField(value=requestInput,onValueChange={requestInput=it},label={Text(stringResource(R.string.devices_request_code))},modifier=Modifier.fillMaxWidth(),minLines=3)
                Button(onClick={vm.approveDeviceRequest(requestInput)},enabled=requestInput.isNotBlank(),modifier=Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.devices_approve))
                }
                OutlinedTextField(value=approvalInput,onValueChange={approvalInput=it},label={Text(stringResource(R.string.devices_approval_code))},modifier=Modifier.fillMaxWidth(),minLines=3)
                OutlinedButton(onClick={vm.acceptDeviceApproval(approvalInput)},enabled=approvalInput.isNotBlank(),modifier=Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.devices_accept_approval))
                }
                outputCode?.let { OutlinedTextField(value=it,onValueChange={},readOnly=true,label={Text(stringResource(R.string.devices_output_code))},modifier=Modifier.fillMaxWidth(),minLines=3) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.devices_sync_title),style=MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.devices_sync_warning),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                devices.filter { it.status==TrustedDeviceStatus.APPROVED && it.deviceId!=vm.localDeviceId() }.forEach { device ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(device.displayName,style=MaterialTheme.typography.titleSmall)
                            Text(device.deviceId,style=MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick={selectedDeviceId=device.deviceId; exportLauncher.launch("relyra-device-sync.rlyx")}) { Text(stringResource(R.string.devices_export)) }
                    }
                }
                OutlinedTextField(value=syncInput,onValueChange={syncInput=it},label={Text(stringResource(R.string.devices_sync_code))},modifier=Modifier.fillMaxWidth(),minLines=3)
                OutlinedTextField(value=revocationInput,onValueChange={revocationInput=it},label={Text(stringResource(R.string.devices_revocation_code))},modifier=Modifier.fillMaxWidth(),minLines=3)
                OutlinedButton(onClick={vm.applyDeviceRevocation(revocationInput)},enabled=revocationInput.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.devices_apply_revocation)) }
                Button(onClick={importLauncher.launch(arrayOf("application/octet-stream", "application/*", "*/*"))},modifier=Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.devices_import_file))
                }
                OutlinedButton(onClick={vm.importDeviceSync(syncInput)},enabled=syncInput.isNotBlank(),modifier=Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.devices_import_code))
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.devices_list_title),style=MaterialTheme.typography.titleMedium)
                if(devices.isEmpty()) Text(stringResource(R.string.devices_empty))
                devices.forEach { device ->
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(device.displayName,style=MaterialTheme.typography.titleSmall)
                        Text(device.platform + " • " + device.status,style=MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.devices_last_seen) + ": " + device.lastSeenAt,style=MaterialTheme.typography.bodySmall)
                        if(device.status==TrustedDeviceStatus.APPROVED && device.deviceId!=vm.localDeviceId()) {
                            OutlinedButton(onClick={vm.revokeDevice(device.deviceId)}) { Text(stringResource(R.string.devices_revoke)) }
                        }
                    }
                }
            }
        }
        status?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
    }
}
