package org.relyra.app.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

data class TorSocksSettings(
    val enabled: Boolean = false,
    val strict: Boolean = true,
    val port: Int = DEFAULT_PORT,
) {
    companion object {
        const val DEFAULT_PORT = 9050
        fun isValidPort(port: Int): Boolean = port in 1..65535
    }
}

sealed interface TorProxyStatus {
    data object Disabled : TorProxyStatus
    data object ReadyToTest : TorProxyStatus
    data object Testing : TorProxyStatus
    data class Verified(val exitIp: String) : TorProxyStatus
    data object NotTor : TorProxyStatus
    data class Failed(val reason: String) : TorProxyStatus
}

/**
 * A live SOCKS5 selector for an external Tor service (e.g. Orbot on 127.0.0.1:9050).
 *
 * Strict mode returns only the SOCKS proxy, never DIRECT. Non-strict mode deliberately allows a direct
 * fallback and must not be described as anonymity protection. Remote hostnames remain unresolved here so
 * SOCKS5 performs destination DNS resolution through the proxy.
 */
object TorSocksProxySelector : ProxySelector() {
    private val _settings = MutableStateFlow(TorSocksSettings())
    val settings: StateFlow<TorSocksSettings> = _settings

    @Volatile private var lastFailure: String? = null

    fun configure(value: TorSocksSettings) {
        require(TorSocksSettings.isValidPort(value.port)) { "Invalid SOCKS port" }
        _settings.value = value
        lastFailure = null
    }

    fun lastFailure(): String? = lastFailure

    fun isEnabled(): Boolean = _settings.value.enabled
    fun isStrictMode(): Boolean = _settings.value.enabled && _settings.value.strict

    override fun select(uri: URI): List<Proxy> {
        requireNotNull(uri) { "URI must not be null" }
        val current = _settings.value
        if (uri.scheme?.lowercase() !in setOf("http", "https", "ws", "wss")) {
            return listOf(Proxy.NO_PROXY)
        }
        if (!current.enabled) return listOf(Proxy.NO_PROXY)

        val proxyAddress: SocketAddress = InetSocketAddress("127.0.0.1", current.port)
        val socks = Proxy(Proxy.Type.SOCKS, proxyAddress)
        return if (current.strict) listOf(socks) else listOf(socks, Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        lastFailure = (ioe?.message ?: ioe?.javaClass?.simpleName ?: "SOCKS connection failed").take(120)
    }
}

/** Performs a real request through the configured shared OkHttp SOCKS selector and checks Tor Project's answer. */
class TorProxyTester(
    private val client: OkHttpClient = Http.client,
) {
    suspend fun test(): TorProxyStatus {
        if (!TorSocksProxySelector.isEnabled()) return TorProxyStatus.Disabled

        val checkClient = client.newBuilder()
            .callTimeout(18, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        return try {
            val request = Request.Builder()
                .url(CHECK_URL)
                .header("Accept", "application/json")
                .get()
                .build()
            checkClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return TorProxyStatus.Failed("Tor check returned HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                val parsed = try { JSONObject(body) } catch (_: JSONException) {
                    return TorProxyStatus.Failed("Tor check returned invalid JSON")
                }
                if (!parsed.optBoolean("IsTor", false)) return TorProxyStatus.NotTor
                val ip = parsed.optString("IP", "").take(64)
                TorProxyStatus.Verified(ip.ifBlank { "verified" })
            }
        } catch (_: IOException) {
            TorProxyStatus.Failed(TorSocksProxySelector.lastFailure() ?: "Cannot reach the local Tor SOCKS proxy")
        } catch (_: IllegalArgumentException) {
            TorProxyStatus.Failed("Invalid Tor SOCKS configuration")
        }
    }

    private companion object {
        const val CHECK_URL = "https://check.torproject.org/api/ip"
    }
}
