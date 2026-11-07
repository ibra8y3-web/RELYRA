package org.relyra.app

import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import org.relyra.app.core.notify.Foreground
import org.relyra.app.core.notify.Notifier
import org.relyra.app.ui.components.findActivity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relyra.app.ui.nav.AppNav
import org.relyra.app.ui.screens.LockScreen
import org.relyra.app.ui.screens.OnboardingHost
import org.relyra.app.ui.theme.RelyraTheme

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { vm.storage.value is StorageState.Opening }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val themeMode by vm.settings.theme.collectAsStateWithLifecycle()
            RelyraTheme(themeMode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RelyraRoot(vm, ::authenticate)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.externalInFlight = false
    }

    override fun onStart() {
        super.onStart()
        Foreground.visible = true
    }

    override fun onStop() {
        super.onStop()
        Foreground.visible = false
        if (!isChangingConfigurations && !vm.externalInFlight) vm.relock()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val data = i.dataString
        if (data != null && data.startsWith("relyra://contact")) {
            vm.incomingLink.value = data
            return
        }
        when (i.action) {
            Intent.ACTION_SEND -> {
                val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val uri = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                if (!text.isNullOrBlank() || uri != null) vm.pendingShare.value = ShareRequest(text, uri)
            }
            Notifier.ACTION_OPEN_CHAT -> {
                val id = i.getStringExtra(Notifier.EXTRA_CHAT)
                if (id != null) vm.openRequest.value = OpenRequest(id, i.getBooleanExtra(Notifier.EXTRA_IS_GROUP, false))
            }
        }
    }

    /** Device biometrics or screen-lock credential. Never falls back to anything weaker. */
    private fun authenticate(title: String, allowIfUnavailable: Boolean, onSuccess: () -> Unit) {
        val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            if (allowIfUnavailable) onSuccess() else vm.message.value = UiMessage(R.string.auth_unavailable)
            return
        }
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(authenticators).build()
        )
    }
}

@Composable
private fun RelyraRoot(
    vm: MainViewModel,
    authenticate: (String, Boolean, () -> Unit) -> Unit,
) {
    val storage by vm.storage.collectAsStateWithLifecycle()
    val identity by vm.identity.collectAsStateWithLifecycle()
    val lockEnabled by vm.lockEnabled.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val blockShots by vm.settings.blockScreenshots.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(blockShots) {
        val w = ctx.findActivity()?.window
        if (blockShots) w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    when {
        storage is StorageState.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Text(stringResource(R.string.storage_failed_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.storage_failed_body), modifier = Modifier.padding(top = 8.dp))
            Text((storage as StorageState.Failed).detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        storage is StorageState.Opening || identity is IdentityState.Loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        identity is IdentityState.None -> OnboardingHost(vm)
        lockEnabled && !unlocked -> LockScreen(vm, authenticate)
        else -> AppNav(vm, authenticate)
    }
}
