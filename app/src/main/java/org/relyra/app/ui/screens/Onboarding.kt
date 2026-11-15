package org.relyra.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.ui.components.BrandMark
import org.relyra.app.ui.components.rememberMessageHost

@Composable
fun OnboardingHost(vm: MainViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = rememberMessageHost(vm)
    BackHandler(enabled = step != 0) { step = 0 }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).imePadding()) {
            when (step) {
                0 -> Welcome(onCreate = { step = 1 }, onRestore = { step = 2 })
                1 -> CreateForm(vm, onBack = { step = 0 })
                else -> RestoreForm(vm, onBack = { step = 0 })
            }
        }
    }
}

@Composable
private fun Welcome(onCreate: () -> Unit, onRestore: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandMark(112.dp)
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.tagline),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.onb_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_create)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_restore)) }
    }
}

@Composable
private fun CreateForm(vm: MainViewModel, onBack: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var ack by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.create_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.create_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(64) },
            label = { Text(stringResource(R.string.field_display_name)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(checked = ack, onCheckedChange = { ack = it })
            Text(stringResource(R.string.create_ack), Modifier.padding(top = 12.dp))
        }
        Button(
            onClick = { vm.createIdentity(name) },
            enabled = name.isNotBlank() && ack && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.create_action)) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
}

@Composable
private fun RestoreForm(vm: MainViewModel, onBack: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.restore_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.restore_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(64) },
            label = { Text(stringResource(R.string.field_display_name)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { Text(stringResource(R.string.field_recovery_key)) },
            minLines = 3, modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.restoreIdentity(name, key) },
            enabled = name.isNotBlank() && key.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.restore_action)) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
}
