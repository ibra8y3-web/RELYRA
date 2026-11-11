package org.relyra.app.ui.screens

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.relyra.app.R
import org.relyra.app.core.tools.BrowserUrlPolicy

/**
 * Minimal, user-driven WebView. JavaScript, cookies, DOM storage, mixed content and local-file access
 * are disabled by default. TLS certificate errors are always cancelled.
 * This is not a Tor browser, tracker blocker, multi-tab browser or a promise of anonymity.
 */
@Composable
fun BrowserScreen() {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var address by rememberSaveable { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            CookieManager.getInstance().setAcceptCookie(false)

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress.coerceIn(0, 100)
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    pageTitle = title.orEmpty()
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    return scheme != "https" && scheme != "http"
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    currentUrl = url.orEmpty()
                    pageError = null
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    currentUrl = url.orEmpty()
                    address = currentUrl
                    progress = 100
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        pageError = error.description?.toString() ?: context.getString(R.string.browser_load_failed)
                    }
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    pageError = context.getString(R.string.browser_tls_blocked)
                }
            }
        }
    }

    fun openAddress() {
        val normalized = BrowserUrlPolicy.normalize(address)
        if (normalized == null) {
            pageError = context.getString(R.string.browser_invalid_address)
            return
        }
        keyboard?.hide()
        pageError = null
        address = normalized
        webView.loadUrl(normalized)
    }

    BackHandler(enabled = webView.canGoBack()) {
        webView.goBack()
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; pageError = null },
                label = { Text(stringResource(R.string.browser_address)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { openAddress() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { openAddress() }, enabled = address.isNotBlank()) {
                Text(stringResource(R.string.browser_go))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { if (webView.canGoBack()) webView.goBack() }, enabled = webView.canGoBack()) {
                Text(stringResource(R.string.browser_back))
            }
            TextButton(onClick = { if (webView.canGoForward()) webView.goForward() }, enabled = webView.canGoForward()) {
                Text(stringResource(R.string.browser_forward))
            }
            TextButton(onClick = { webView.reload() }, enabled = currentUrl.isNotBlank()) {
                Text(stringResource(R.string.browser_reload))
            }
        }
        if (progress in 1..99) {
            LinearProgressIndicator(progress = progress / 100f, modifier = Modifier.fillMaxWidth())
        }
        pageError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (pageTitle.isNotBlank()) {
            Text(pageTitle, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        AndroidView(
            factory = { webView },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Text(
            stringResource(R.string.browser_security_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
