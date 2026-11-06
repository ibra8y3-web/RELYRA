package org.relyra.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.ui.components.BrandMark

@Composable
fun LockScreen(
    vm: MainViewModel,
    authenticate: (title: String, allowIfUnavailable: Boolean, onSuccess: () -> Unit) -> Unit,
) {
    val title = stringResource(R.string.unlock_auth_title)
    val request = { authenticate(title, false) { vm.markUnlocked() } }
    LaunchedEffect(Unit) { request() }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandMark(96.dp)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.locked_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        Button(onClick = { request() }) { Text(stringResource(R.string.unlock)) }
    }
}
