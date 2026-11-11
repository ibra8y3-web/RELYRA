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
