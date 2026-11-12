package org.relyra.app.core.network

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URISyntaxException

object RelayUrl {
    /** Only secure websockets. Returns a canonical wss:// URL or null. */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.length > 200 || s.any { it.isWhitespace() }) return null
        if (!s.contains("://")) s = "wss://$s"
        val uri = try { URI(s) } catch (e: URISyntaxException) { return null }
        if (uri.scheme?.lowercase() != "wss") return null
        val host = uri.host?.lowercase() ?: return null
        if (!host.contains('.') || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "wss://$host$port${uri.rawPath.orEmpty().trimEnd('/')}"
    }
}

data class RelayConfig(val url: String, val enabled: Boolean)

object DiscoveryGatewayUrl {
    /** Only HTTPS endpoints are accepted; cleartext discovery could spoof presence and TURN credentials. */
    fun normalize(input: String): String? {
        val value = input.trim()
        if (value.isEmpty() || value.length > 300 || value.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = try { URI(value) } catch (_: URISyntaxException) { return null }
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.query != null || uri.fragment != null ||
            (uri.port != -1 && uri.port !in 1..65535)
        ) return null
        val host = uri.host.lowercase()
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/').ifEmpty { "" }
        return "https://$host$port$path"
    }
}

data class DiscoveryGatewayConfig(val url: String, val enabled: Boolean)

sealed interface RelayStatus {
    data object Untested : RelayStatus
    data object Testing : RelayStatus
    class Ok(val connectMs: Long, val eoseMs: Long, val at: Long) : RelayStatus
    class Failed(val reason: String, val at: Long) : RelayStatus
}

enum class AddRelayResult { Added, Invalid, Duplicate }

/** The user's relay list. Nothing is added by default and nothing connects until the user asks. */
class NetworkManager(context: Context, private val client: RelayClient = RelayClient()) {
    private val prefs = context.applicationContext.getSharedPreferences("relyra_network", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _torProxySettings = MutableStateFlow(loadTorProxySettings())
    val torProxySettings: StateFlow<TorSocksSettings> = _torProxySettings
    private val _torProxyStatus = MutableStateFlow<TorProxyStatus>(
        if (_torProxySettings.value.enabled) TorProxyStatus.ReadyToTest else TorProxyStatus.Disabled
    )
    val torProxyStatus: StateFlow<TorProxyStatus> = _torProxyStatus

    init {
        Http.configureTorProxy(_torProxySettings.value)
    }

    private fun loadTorProxySettings(): TorSocksSettings = TorSocksSettings(
        enabled = prefs.getBoolean("tor_socks_enabled", false),
        strict = prefs.getBoolean("tor_socks_strict", true),
        port = prefs.getInt("tor_socks_port", TorSocksSettings.DEFAULT_PORT)
            .takeIf { TorSocksSettings.isValidPort(it) } ?: TorSocksSettings.DEFAULT_PORT,
    )

    fun updateTorProxySettings(enabled: Boolean, strict: Boolean, port: Int): Boolean {
        if (!TorSocksSettings.isValidPort(port)) return false
        val settings = TorSocksSettings(enabled = enabled, strict = strict, port = port)
        prefs.edit {
            putBoolean("tor_socks_enabled", settings.enabled)
            putBoolean("tor_socks_strict", settings.strict)
            putInt("tor_socks_port", settings.port)
        }
        Http.configureTorProxy(settings)
        _torProxySettings.value = settings
        _torProxyStatus.value = if (enabled) TorProxyStatus.ReadyToTest else TorProxyStatus.Disabled
        return true
    }

    fun testTorProxy() {
        if (!_torProxySettings.value.enabled) {
            _torProxyStatus.value = TorProxyStatus.Disabled
            return
        }
        _torProxyStatus.value = TorProxyStatus.Testing
        scope.launch {
            _torProxyStatus.value = TorProxyTester().test()
        }
    }

    private val _discoveryGateways = MutableStateFlow(loadDiscoveryGateways())
    val discoveryGateways: StateFlow<List<DiscoveryGatewayConfig>> = _discoveryGateways
    private val _publicDiscoveryEnabled = MutableStateFlow(prefs.getBoolean("public_discovery_enabled", false))
    val publicDiscoveryEnabled: StateFlow<Boolean> = _publicDiscoveryEnabled

    fun setPublicDiscoveryEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("public_discovery_enabled", enabled) }
        _publicDiscoveryEnabled.value = enabled
    }

    private fun loadDiscoveryGateways(): List<DiscoveryGatewayConfig> =
        prefs.getString("discovery_gateways", "").orEmpty().lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                DiscoveryGatewayUrl.normalize(parts[0])?.let { DiscoveryGatewayConfig(it, parts.getOrNull(1) != "0") }
            }
            .distinctBy { it.url }

    private fun saveDiscoveryGateways(values: List<DiscoveryGatewayConfig>) {
        val normalized = values.distinctBy { it.url }
        _discoveryGateways.value = normalized
        prefs.edit {
            putString("discovery_gateways", normalized.joinToString("\n") {
                "${it.url}\t${if (it.enabled) 1 else 0}"
            })
        }
    }

    fun addDiscoveryGateway(input: String): AddRelayResult {
        val url = DiscoveryGatewayUrl.normalize(input) ?: return AddRelayResult.Invalid
        if (_discoveryGateways.value.any { it.url == url }) return AddRelayResult.Duplicate
        saveDiscoveryGateways(_discoveryGateways.value + DiscoveryGatewayConfig(url, true))
        return AddRelayResult.Added
    }

    fun removeDiscoveryGateway(url: String) {
        saveDiscoveryGateways(_discoveryGateways.value.filterNot { it.url == url })
    }

    fun setDiscoveryGatewayEnabled(url: String, enabled: Boolean) {
        saveDiscoveryGateways(_discoveryGateways.value.map {
            if (it.url == url) it.copy(enabled = enabled) else it
        })
    }

    private val _relays = MutableStateFlow(load())
    val relays: StateFlow<List<RelayConfig>> = _relays
    private val _status = MutableStateFlow<Map<String, RelayStatus>>(emptyMap())
    val status: StateFlow<Map<String, RelayStatus>> = _status

    /** Explicit opt-in: when enabled, the public RELYRA ID is advertised on the current local network. */
    private val _nearbyEnabled = MutableStateFlow(prefs.getBoolean("nearby_enabled", false))
    val nearbyEnabled: StateFlow<Boolean> = _nearbyEnabled

    fun setNearbyEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("nearby_enabled", enabled) }
        _nearbyEnabled.value = enabled
    }

    private val _bluetoothEnabled = MutableStateFlow(prefs.getBoolean("bluetooth_enabled", false))
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled

    fun setBluetoothEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("bluetooth_enabled", enabled) }
        _bluetoothEnabled.value = enabled
    }

    private val _wifiDirectEnabled = MutableStateFlow(prefs.getBoolean("wifi_direct_enabled", false))
    val wifiDirectEnabled: StateFlow<Boolean> = _wifiDirectEnabled

    fun setWifiDirectEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("wifi_direct_enabled", enabled) }
        _wifiDirectEnabled.value = enabled
    }

    /** Newest relay event (seconds) already processed; used to resume the inbox subscription. */
    var inboxSince: Long
        get() = prefs.getLong("inbox_since", 0L)
        set(v) { prefs.edit { putLong("inbox_since", v) } }

    private fun load(): List<RelayConfig> =
        prefs.getString("relays", "").orEmpty().lines().filter { it.isNotBlank() }.mapNotNull {
            val parts = it.split('\t')
            RelayUrl.normalize(parts[0])?.let { u -> RelayConfig(u, parts.getOrNull(1) != "0") }
        }

    private fun save(list: List<RelayConfig>) {
        _relays.value = list
        prefs.edit { putString("relays", list.joinToString("\n") { "${it.url}\t${if (it.enabled) 1 else 0}" }) }
    }

    fun add(input: String): AddRelayResult {
        val url = RelayUrl.normalize(input) ?: return AddRelayResult.Invalid
        if (_relays.value.any { it.url == url }) return AddRelayResult.Duplicate
        save(_relays.value + RelayConfig(url, true))
        return AddRelayResult.Added
    }

    fun remove(url: String) {
        save(_relays.value.filterNot { it.url == url })
        _status.value = _status.value - url
    }

    fun setEnabled(url: String, enabled: Boolean) =
        save(_relays.value.map { if (it.url == url) it.copy(enabled = enabled) else it })

    fun test(url: String) {
        _status.value = _status.value + (url to RelayStatus.Testing)
        scope.launch {
            val now = System.currentTimeMillis()
            val result = when (val r = client.probe(url)) {
                is ProbeResult.Success -> RelayStatus.Ok(r.connectMs, r.eoseMs, now)
                is ProbeResult.Failure -> RelayStatus.Failed(r.reason, now)
            }
            _status.value = _status.value + (url to result)
        }
    }
}
