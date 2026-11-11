package org.relyra.app.ui.screens

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.relyra.app.R
import org.relyra.app.core.tools.BrowserUrlPolicy
import org.relyra.app.core.network.TorSocksProxySelector
import java.io.ByteArrayInputStream

/**
 * A privacy-conscious WebView browser. JavaScript and DOM storage are enabled to support modern
 * sites; persistent cookies remain opt-in. Local-file access and mixed content stay disabled.
 * WebView cannot use RELYRA's SOCKS5 selector, so navigation is blocked while Tor routing is on.
 */
@Composable
fun BrowserScreen(onOpenNetwork: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var address by rememberSaveable { mutableStateOf("") }
    var currentUrl by rememberSaveable { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var javaScriptEnabled by rememberSaveable { mutableStateOf(true) }
    var cookiesEnabled by rememberSaveable { mutableStateOf(false) }

    val webView = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
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
                    if (TorSocksProxySelector.isEnabled()) {
                        pageError = context.getString(R.string.browser_tor_proxy_blocked)
                        return true
                    }
                    val scheme = request.url.scheme?.lowercase()
                    return scheme != "https" && scheme != "http"
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!TorSocksProxySelector.isEnabled()) return null
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        403,
                        "Blocked: WebView does not use RELYRA SOCKS proxy",
                        mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(ByteArray(0)),
                    )
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

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse,
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        pageError = context.getString(R.string.browser_http_error, errorResponse.statusCode)
                    }
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    pageError = context.getString(R.string.browser_tls_blocked)
                }
            }
        }
    }

    DisposableEffect(webView, javaScriptEnabled, cookiesEnabled) {
        webView.settings.javaScriptEnabled = javaScriptEnabled
        webView.settings.domStorageEnabled = javaScriptEnabled
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(cookiesEnabled)
        cookies.setAcceptThirdPartyCookies(webView, false)
        if (!cookiesEnabled) {
            cookies.removeAllCookies(null)
            cookies.flush()
        }
        onDispose { }
    }

    fun openAddress() {
        if (TorSocksProxySelector.isEnabled()) {
            pageError = context.getString(R.string.browser_tor_proxy_blocked)
            return
        }
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

    BackHandler(enabled = webView.canGoBack()) { webView.goBack() }

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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically) {
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
            TextButton(onClick = { if (currentUrl.isNotBlank()) webView.reload() }, enabled = currentUrl.isNotBlank()) {
                Text(stringResource(R.string.browser_reload))
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.browser_javascript), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = javaScriptEnabled, onCheckedChange = { javaScriptEnabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.browser_cookies), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = cookiesEnabled, onCheckedChange = { cookiesEnabled = it })
                }
            }
        }
        if (progress in 1..99) {
            LinearProgressIndicator(progress = progress / 100f, modifier = Modifier.fillMaxWidth())
        }
        pageError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (TorSocksProxySelector.isEnabled()) {
                TextButton(onClick = onOpenNetwork) { Text(stringResource(R.string.browser_open_network)) }
            }
        }
        if (pageTitle.isNotBlank()) {
            Text(pageTitle, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
        Text(
            stringResource(R.string.browser_security_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
