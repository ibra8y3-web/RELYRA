package org.relyra.app.ui.screens

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.relyra.app.R
import org.relyra.app.core.network.TorSocksProxySelector
import org.relyra.app.core.tools.BrowserSiteStore
import org.relyra.app.core.tools.BrowserStore
import org.relyra.app.core.tools.BrowserUrlPolicy
import org.relyra.app.core.tools.SiteDecision
import org.relyra.app.core.tools.SiteRules
import org.relyra.app.core.tools.SiteRulesCodec
import java.io.ByteArrayInputStream
import java.io.File

private const val MAX_TABS = 8

private class PendingDownload(val url: String, val name: String, val mime: String)

/** A camera or microphone request from a page that is waiting for the user's answer. */
private class SitePrompt(val host: String, val request: PermissionRequest, val camera: Boolean, val microphone: Boolean)

/** Everything a tab needs from the screen that owns it. */
private interface TabHost {
    fun torBlocked(): Boolean
    fun showError(tab: TabState?, message: String?)
    fun onDownload(url: String?, contentDisposition: String?, mime: String?)
    fun onNavigate(tab: TabState, url: String)
    fun onPageFinished(tab: TabState, url: String, title: String)
    fun onPermission(request: PermissionRequest)
    fun onPermissionCanceled(request: PermissionRequest)
}

/** One browser tab: its own WebView plus the page state the screen displays. */
private class TabState(val id: Int, private val context: Context, private val host: TabHost, surfaceColor: Int) {
    var title by mutableStateOf("")
    var url by mutableStateOf("")
    var started by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var pendingLoad by mutableStateOf<String?>(null)

    val webView: WebView = WebView(context)

    init {
        val tab = this
        webView.apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(surfaceColor)
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
            CookieManager.getInstance().setAcceptCookie(false) // privacy default until the site rules are applied

            setDownloadListener { downloadUrl, _, contentDisposition, mimetype, _ ->
                host.onDownload(downloadUrl, contentDisposition, mimetype)
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    tab.progress = newProgress.coerceIn(0, 100)
                }

                override fun onReceivedTitle(view: WebView, pageTitle: String?) {
                    tab.title = pageTitle.orEmpty()
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    host.onPermission(request)
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest) {
                    host.onPermissionCanceled(request)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (host.torBlocked()) {
                        host.showError(tab, context.getString(R.string.browser_tor_proxy_blocked))
                        return true
                    }
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme != "https" && scheme != "http") return true
                    if (request.isForMainFrame) host.onNavigate(tab, request.url.toString())
                    return false
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!host.torBlocked()) return null
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        403,
                        "Blocked: WebView does not use RELYRA SOCKS proxy",
                        mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }

                override fun doUpdateVisitedHistory(view: WebView, visitedUrl: String?, isReload: Boolean) {
                    tab.canBack = view.canGoBack()
                    tab.canForward = view.canGoForward()
                }

                override fun onPageStarted(view: WebView, startedUrl: String?, favicon: Bitmap?) {
                    tab.url = startedUrl.orEmpty()
                    host.onNavigate(tab, tab.url)
                    host.showError(tab, null)
                }

                override fun onPageFinished(view: WebView, finishedUrl: String?) {
                    tab.url = finishedUrl.orEmpty()
                    tab.progress = 100
                    tab.canBack = view.canGoBack()
                    tab.canForward = view.canGoForward()
                    host.onPageFinished(tab, tab.url, view.title.orEmpty())
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        host.showError(tab, error.description?.toString() ?: context.getString(R.string.browser_load_failed))
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse,
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        host.showError(tab, context.getString(R.string.browser_http_error, errorResponse.statusCode))
                    }
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    host.showError(tab, context.getString(R.string.browser_tls_blocked))
                }
            }
        }
    }

    fun destroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.destroy()
    }
}

private fun nextDecision(current: SiteDecision?): SiteDecision? = when (current) {
    null -> SiteDecision.ALLOW
    SiteDecision.ALLOW -> SiteDecision.BLOCK
    SiteDecision.BLOCK -> null
}

@Composable
private fun SiteRuleRow(label: String, value: SiteDecision?, defaultLabel: String, onCycle: () -> Unit) {
    val valueLabel = when (value) {
        SiteDecision.ALLOW -> stringResource(R.string.browser_rule_allow)
        SiteDecision.BLOCK -> stringResource(R.string.browser_rule_block)
        null -> defaultLabel
    }
    TextButton(onClick = onCycle, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.browser_site_rule_line, label, valueLabel))
    }
}

/**
 * A privacy-conscious tabbed WebView browser. JavaScript and DOM storage are enabled by default to
 * support modern sites; persistent cookies remain opt-in. Each site can override JavaScript, cookies,
 * camera and microphone. Local-file access and mixed content stay disabled. WebView cannot use
 * RELYRA's SOCKS5 selector, so navigation is blocked while Tor routing is on.
 *
 * A tab's page view is only shown after its first navigation. Until then a start panel with
 * bookmarks and (optional, encrypted) history is shown, so an empty white web view can never cover
 * the address bar and controls.
 */
@Composable
fun BrowserScreen(onOpenNetwork: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val store = remember(context) { BrowserStore(context) }
    val sites = remember(context) { BrowserSiteStore(context) }
    var address by rememberSaveable { mutableStateOf("") }
    var savedUrls by rememberSaveable { mutableStateOf("") }
    var savedActive by rememberSaveable { mutableIntStateOf(0) }
    var activeId by rememberSaveable { mutableIntStateOf(-1) }
    var nextTabId by remember { mutableIntStateOf(1) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var javaScriptEnabled by rememberSaveable { mutableStateOf(true) }
    var cookiesEnabled by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSite by rememberSaveable { mutableStateOf(false) }
    var rulesTick by remember { mutableIntStateOf(0) }
    var bookmarks by remember { mutableStateOf(store.bookmarks()) }
    var history by remember { mutableStateOf(store.history()) }
    var historyEnabled by remember { mutableStateOf(store.historyEnabled) }
    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
    var pendingExternal by remember { mutableStateOf<String?>(null) }
    var pendingPrompt by remember { mutableStateOf<SitePrompt?>(null) }
    var pendingAndroidGrant by remember { mutableStateOf<SitePrompt?>(null) }
    val surfaceColor = MaterialTheme.colorScheme.surface.toArgb()
    val tabs = remember { mutableStateListOf<TabState>() }

    fun currentTab(): TabState = tabs.firstOrNull { it.id == activeId } ?: tabs.first()

    fun applyCookieRule(tab: TabState, rules: SiteRules) {
        val accept = when (rules.cookies) {
            SiteDecision.ALLOW -> true
            SiteDecision.BLOCK -> false
            null -> cookiesEnabled
        }
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(accept)
        cookies.setAcceptThirdPartyCookies(tab.webView, false)
    }

    fun applySiteRules(tab: TabState, url: String) {
        val rules = sites.rulesFor(url)
        val script = when (rules.javaScript) {
            SiteDecision.ALLOW -> true
            SiteDecision.BLOCK -> false
            null -> javaScriptEnabled
        }
        tab.webView.settings.javaScriptEnabled = script
        tab.webView.settings.domStorageEnabled = script
        if (tab.id == activeId) applyCookieRule(tab, rules)
    }

    fun setRule(url: String, change: (SiteRules) -> SiteRules) {
        sites.update(url, change(sites.rulesFor(url)))
        rulesTick++
    }

    fun grantResources(prompt: SitePrompt) {
        val resources = ArrayList<String>()
        if (prompt.camera) resources.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        if (prompt.microphone) resources.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        prompt.request.grant(resources.toTypedArray())
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val prompt = pendingAndroidGrant
        pendingAndroidGrant = null
        if (prompt != null) {
            val cameraOk = !prompt.camera || result[Manifest.permission.CAMERA] == true
            val microphoneOk = !prompt.microphone || result[Manifest.permission.RECORD_AUDIO] == true
            if (cameraOk && microphoneOk) {
                grantResources(prompt)
            } else {
                prompt.request.deny()
                pageError = context.getString(R.string.browser_perm_android_denied)
            }
        }
    }

    fun grantWithAndroidCheck(prompt: SitePrompt) {
        val missing = ArrayList<String>()
        if (prompt.camera && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA)
        }
        if (prompt.microphone && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isEmpty()) {
            grantResources(prompt)
        } else {
            pendingAndroidGrant = prompt
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    val host = remember {
        object : TabHost {
            override fun torBlocked(): Boolean = TorSocksProxySelector.isEnabled()

            override fun showError(tab: TabState?, message: String?) {
                if (tab == null || tab.id == activeId) pageError = message
            }

            override fun onDownload(url: String?, contentDisposition: String?, mime: String?) {
                if (TorSocksProxySelector.isEnabled()) {
                    pageError = context.getString(R.string.browser_tor_proxy_blocked)
                    return
                }
                val safeUrl = BrowserUrlPolicy.normalize(url.orEmpty())
                if (safeUrl == null) {
                    pageError = context.getString(R.string.browser_invalid_address)
                } else {
                    pendingDownload = PendingDownload(safeUrl, URLUtil.guessFileName(safeUrl, contentDisposition, mime), mime.orEmpty())
                }
            }

            override fun onNavigate(tab: TabState, url: String) {
                applySiteRules(tab, url)
            }

            override fun onPageFinished(tab: TabState, url: String, title: String) {
                if (tab.id == activeId) address = url
                if (url.isNotBlank()) {
                    store.addHistory(url, title)
                    if (store.historyEnabled) history = store.history()
                }
            }

            override fun onPermission(request: PermissionRequest) {
                val camera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val microphone = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                val origin = request.origin.toString()
                val siteHost = SiteRulesCodec.siteKey(origin)
                if ((!camera && !microphone) || siteHost == null || TorSocksProxySelector.isEnabled()) {
                    request.deny()
                    return
                }
                val rules = sites.rulesFor(origin)
                if ((camera && rules.camera == SiteDecision.BLOCK) || (microphone && rules.microphone == SiteDecision.BLOCK)) {
                    request.deny()
                    return
                }
                val prompt = SitePrompt(siteHost, request, camera, microphone)
                val alreadyAllowed = (!camera || rules.camera == SiteDecision.ALLOW) &&
                    (!microphone || rules.microphone == SiteDecision.ALLOW)
                if (alreadyAllowed) {
                    grantWithAndroidCheck(prompt)
                } else {
                    pendingPrompt?.request?.deny()
                    pendingPrompt = prompt
                }
            }

            override fun onPermissionCanceled(request: PermissionRequest) {
                if (pendingPrompt?.request === request) pendingPrompt = null
            }
        }
    }

    fun createTab(): TabState {
        val id = nextTabId
        nextTabId = id + 1
        return TabState(id, context, host, surfaceColor)
    }

    remember {
        val urls = savedUrls.split("\n").filter { it.isNotBlank() }.take(MAX_TABS)
        if (urls.isEmpty()) {
            val first = createTab()
            tabs.add(first)
            activeId = first.id
        } else {
            urls.forEach { saved ->
                val tab = createTab()
                tab.started = true
                tab.url = saved
                tab.pendingLoad = saved
                tabs.add(tab)
            }
            val active = tabs[savedActive.coerceIn(0, tabs.size - 1)]
            activeId = active.id
            address = active.url
        }
        true
    }

    val activeTab = currentTab()
    val currentUrl = activeTab.url
    val currentSite = SiteRulesCodec.siteKey(currentUrl)
    val currentRules = remember(currentUrl, rulesTick) { sites.rulesFor(currentUrl) }
    val isBookmarked = bookmarks.any { it.url == currentUrl }
    val loading = activeTab.progress in 1..99
    val newTabLabel = stringResource(R.string.browser_new_tab)
    val closeTabLabel = stringResource(R.string.browser_close_tab)

    SideEffect {
        val live = tabs.filter { it.started && it.url.isNotBlank() }
        savedUrls = live.joinToString("\n") { it.url }
        savedActive = live.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
    }

    LaunchedEffect(javaScriptEnabled, cookiesEnabled) {
        tabs.forEach { applySiteRules(it, it.url) }
    }

    LaunchedEffect(cookiesEnabled) {
        if (!cookiesEnabled) {
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
        }
    }

    LaunchedEffect(activeTab.id) {
        applyCookieRule(activeTab, sites.rulesFor(activeTab.url))
    }

    // The page view joins the layout first; the load starts afterwards.
    LaunchedEffect(activeTab.id, activeTab.pendingLoad, activeTab.started) {
        val target = activeTab.pendingLoad
        if (target != null && activeTab.started) {
            applySiteRules(activeTab, target)
            activeTab.webView.loadUrl(target)
            activeTab.pendingLoad = null
        }
    }

    fun openUrl(raw: String) {
        if (TorSocksProxySelector.isEnabled()) {
            pageError = context.getString(R.string.browser_tor_proxy_blocked)
            return
        }
        val normalized = BrowserUrlPolicy.normalize(raw)
        if (normalized == null) {
            pageError = context.getString(R.string.browser_invalid_address)
            return
        }
        keyboard?.hide()
        pageError = null
        notice = null
        address = normalized
        val tab = currentTab()
        tab.url = normalized
        tab.started = true
        tab.pendingLoad = normalized
    }

    fun switchTo(tab: TabState) {
        activeId = tab.id
        address = tab.url
        pageError = null
        notice = null
    }

    fun newTab() {
        if (tabs.size >= MAX_TABS) {
            notice = context.getString(R.string.browser_max_tabs)
            return
        }
        val tab = createTab()
        tabs.add(tab)
        switchTo(tab)
    }

    fun closeTab(tab: TabState) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.remove(tab)
        tab.destroy()
        if (tabs.isEmpty()) {
            val replacement = createTab()
            tabs.add(replacement)
            switchTo(replacement)
        } else if (tab.id == activeId) {
            switchTo(tabs[index.coerceAtMost(tabs.size - 1)])
        }
    }

    fun startDownload(item: PendingDownload) {
        val safeName = File(item.name).name.ifBlank { "download" }
        runCatching {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(item.url))
                .setTitle(safeName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, safeName)
            if (item.mime.isNotBlank()) request.setMimeType(item.mime)
            manager.enqueue(request)
        }.onSuccess {
            pageError = null
            notice = context.getString(R.string.browser_download_started, safeName)
        }.onFailure {
            pageError = context.getString(R.string.browser_download_failed)
        }
    }

    BackHandler(enabled = activeTab.started && activeTab.canBack) { activeTab.webView.goBack() }

    DisposableEffect(Unit) {
        onDispose { tabs.toList().forEach { it.destroy() } }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            tabs.toList().forEach { tab ->
                val label = tab.title.ifBlank { SiteRulesCodec.siteKey(tab.url) ?: newTabLabel }.take(18)
                if (tab.id == activeId) {
                    Button(onClick = { }) { Text(label, maxLines = 1) }
                } else {
                    OutlinedButton(onClick = { switchTo(tab) }) { Text(label, maxLines = 1) }
                }
                TextButton(
                    onClick = { closeTab(tab) },
                    modifier = Modifier.semantics { contentDescription = closeTabLabel },
                ) { Text("\u2715") }
            }
            TextButton(onClick = { newTab() }) { Text(stringResource(R.string.browser_new_tab_button)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; pageError = null },
                label = { Text(stringResource(R.string.browser_address)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { openUrl(address) }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { openUrl(address) }, enabled = address.isNotBlank()) {
                Text(stringResource(R.string.browser_go))
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            TextButton(onClick = { if (activeTab.webView.canGoBack()) activeTab.webView.goBack() },
                enabled = activeTab.started && activeTab.canBack) {
                Text(stringResource(R.string.browser_back))
            }
            TextButton(onClick = { if (activeTab.webView.canGoForward()) activeTab.webView.goForward() },
                enabled = activeTab.started && activeTab.canForward) {
                Text(stringResource(R.string.browser_forward))
            }
            if (loading) {
                TextButton(onClick = { activeTab.webView.stopLoading() }) { Text(stringResource(R.string.browser_stop)) }
            } else {
                TextButton(onClick = { activeTab.webView.reload() }, enabled = activeTab.started && currentUrl.isNotBlank()) {
                    Text(stringResource(R.string.browser_reload))
                }
            }
            TextButton(
                onClick = {
                    store.toggleBookmark(currentUrl, activeTab.title)
                    bookmarks = store.bookmarks()
                },
                enabled = activeTab.started && currentUrl.isNotBlank(),
            ) {
                Text(stringResource(if (isBookmarked) R.string.browser_bookmark_remove else R.string.browser_bookmark_add))
            }
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(currentUrl))
                    notice = context.getString(R.string.browser_link_copied)
                },
                enabled = activeTab.started && currentUrl.isNotBlank(),
            ) { Text(stringResource(R.string.browser_copy_link)) }
            TextButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, currentUrl)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                },
                enabled = activeTab.started && currentUrl.isNotBlank(),
            ) { Text(stringResource(R.string.browser_share_link)) }
            TextButton(onClick = { pendingExternal = currentUrl }, enabled = activeTab.started && currentUrl.isNotBlank()) {
                Text(stringResource(R.string.browser_open_external))
            }
            TextButton(onClick = { showSite = !showSite }) { Text(stringResource(R.string.browser_site_settings)) }
            TextButton(onClick = { showSettings = !showSettings }) { Text(stringResource(R.string.browser_settings)) }
        }
        if (showSite) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (!activeTab.started || currentSite == null) {
                        Text(stringResource(R.string.browser_site_none), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(stringResource(R.string.browser_site_title, currentSite), style = MaterialTheme.typography.titleSmall)
                        SiteRuleRow(
                            stringResource(R.string.browser_rule_javascript),
                            currentRules.javaScript,
                            stringResource(if (javaScriptEnabled) R.string.browser_rule_default_on else R.string.browser_rule_default_off),
                        ) {
                            setRule(currentUrl) { it.copy(javaScript = nextDecision(it.javaScript)) }
                            applySiteRules(activeTab, currentUrl)
                            activeTab.webView.reload()
                        }
                        SiteRuleRow(
                            stringResource(R.string.browser_rule_cookies),
                            currentRules.cookies,
                            stringResource(if (cookiesEnabled) R.string.browser_rule_default_on else R.string.browser_rule_default_off),
                        ) {
                            setRule(currentUrl) { it.copy(cookies = nextDecision(it.cookies)) }
                            applySiteRules(activeTab, currentUrl)
                            activeTab.webView.reload()
                        }
                        SiteRuleRow(
                            stringResource(R.string.browser_rule_camera),
                            currentRules.camera,
                            stringResource(R.string.browser_rule_ask),
                        ) { setRule(currentUrl) { it.copy(camera = nextDecision(it.camera)) } }
                        SiteRuleRow(
                            stringResource(R.string.browser_rule_microphone),
                            currentRules.microphone,
                            stringResource(R.string.browser_rule_ask),
                        ) { setRule(currentUrl) { it.copy(microphone = nextDecision(it.microphone)) } }
                        TextButton(onClick = {
                            sites.reset(currentUrl)
                            rulesTick++
                            applySiteRules(activeTab, currentUrl)
                            activeTab.webView.reload()
                        }) { Text(stringResource(R.string.browser_site_reset)) }
                    }
                }
            }
        }
        if (showSettings) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.browser_javascript), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        Switch(checked = javaScriptEnabled, onCheckedChange = { javaScriptEnabled = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.browser_cookies), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        Switch(checked = cookiesEnabled, onCheckedChange = { cookiesEnabled = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.browser_history_save), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        Switch(
                            checked = historyEnabled,
                            onCheckedChange = {
                                store.historyEnabled = it
                                historyEnabled = it
                                history = store.history()
                            },
                        )
                    }
                    TextButton(onClick = {
                        val cookies = CookieManager.getInstance()
                        cookies.removeAllCookies(null)
                        cookies.flush()
                        notice = context.getString(R.string.browser_cookies_cleared)
                    }) { Text(stringResource(R.string.browser_clear_cookies)) }
                    TextButton(onClick = {
                        sites.resetAll()
                        rulesTick++
                        tabs.forEach { applySiteRules(it, it.url) }
                    }) { Text(stringResource(R.string.browser_site_reset_all)) }
                }
            }
        }
        if (loading) {
            LinearProgressIndicator(progress = { activeTab.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        pageError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (TorSocksProxySelector.isEnabled()) {
                TextButton(onClick = onOpenNetwork) { Text(stringResource(R.string.browser_open_network)) }
            }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
        if (activeTab.started && activeTab.title.isNotBlank()) {
            Text(activeTab.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (activeTab.started) {
                key(activeTab.id) {
                    AndroidView(
                        factory = {
                            val view = activeTab.webView
                            (view.parent as? ViewGroup)?.removeView(view)
                            view
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.browser_start_hint), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.browser_bookmarks), style = MaterialTheme.typography.titleSmall)
                    if (bookmarks.isEmpty()) {
                        Text(stringResource(R.string.browser_no_bookmarks), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    bookmarks.take(30).forEach { entry ->
                        TextButton(onClick = { openUrl(entry.url) }, modifier = Modifier.fillMaxWidth()) {
                            Text(entry.title.ifBlank { entry.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.browser_history), style = MaterialTheme.typography.titleSmall)
                    if (history.isEmpty()) {
                        Text(stringResource(R.string.browser_no_history), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    history.take(30).forEach { entry ->
                        TextButton(onClick = { openUrl(entry.url) }, modifier = Modifier.fillMaxWidth()) {
                            Text(entry.title.ifBlank { entry.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (history.isNotEmpty()) {
                        TextButton(onClick = { store.clearHistory(); history = store.history() }) {
                            Text(stringResource(R.string.browser_history_clear))
                        }
                    }
                    Text(
                        stringResource(R.string.browser_security_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val download = pendingDownload
    if (download != null) {
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text(stringResource(R.string.browser_download_title)) },
            text = { Text(stringResource(R.string.browser_download_message, File(download.name).name)) },
            confirmButton = {
                TextButton(onClick = { pendingDownload = null; startDownload(download) }) {
                    Text(stringResource(R.string.browser_download_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { pendingDownload = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    val external = pendingExternal
    if (external != null) {
        AlertDialog(
            onDismissRequest = { pendingExternal = null },
            title = { Text(stringResource(R.string.browser_external_title)) },
            text = { Text(stringResource(R.string.browser_external_message, external)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingExternal = null
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(external))) }
                        .onFailure { pageError = context.getString(R.string.browser_external_failed) }
                }) { Text(stringResource(R.string.browser_external_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pendingExternal = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    val prompt = pendingPrompt
    if (prompt != null) {
        val what = stringResource(
            when {
                prompt.camera && prompt.microphone -> R.string.browser_perm_camera_mic
                prompt.camera -> R.string.browser_perm_camera
                else -> R.string.browser_perm_microphone
            },
        )
        val siteUrl = "https://" + prompt.host + "/"
        AlertDialog(
            onDismissRequest = { prompt.request.deny(); pendingPrompt = null },
            title = { Text(stringResource(R.string.browser_perm_title, prompt.host, what)) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        pendingPrompt = null
                        grantWithAndroidCheck(prompt)
                    }) { Text(stringResource(R.string.browser_perm_allow)) }
                    TextButton(onClick = {
                        setRule(siteUrl) {
                            it.copy(
                                camera = if (prompt.camera) SiteDecision.ALLOW else it.camera,
                                microphone = if (prompt.microphone) SiteDecision.ALLOW else it.microphone,
                            )
                        }
                        pendingPrompt = null
                        grantWithAndroidCheck(prompt)
                    }) { Text(stringResource(R.string.browser_perm_always)) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    setRule(siteUrl) {
                        it.copy(
                            camera = if (prompt.camera) SiteDecision.BLOCK else it.camera,
                            microphone = if (prompt.microphone) SiteDecision.BLOCK else it.microphone,
                        )
                    }
                    prompt.request.deny()
                    pendingPrompt = null
                }) { Text(stringResource(R.string.browser_perm_block)) }
            },
        )
    }
}
