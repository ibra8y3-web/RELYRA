package org.relyra.app.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

data class TorProxyRevision(val enabled: Boolean, val port: Int, val revision: Long)

/**
 * A fail-closed SOCKS5 selector for a local Tor daemon such as Orbot.
 * When strict mode is enabled, exactly one SOCKS proxy is returned. There is deliberately
 * no DIRECT fallback if the daemon is stopped or unreachable.
 */
class TorProxySelector(
    defaultPort: Int = DEFAULT_SOCKS_PORT,
) : ProxySelector() {
    @Volatile
    private var activeProxy: Proxy? = null

    @Volatile
    var isStrictModeEnabled: Boolean = false
        private set

    @Volatile
    var socksPort: Int = defaultPort
        private set

    private val _revision = MutableStateFlow(TorProxyRevision(false, defaultPort, 0L))
    val revision: StateFlow<TorProxyRevision> = _revision

    init {
        require(defaultPort in MIN_PORT..MAX_PORT) { "invalid SOCKS port" }
    }

    @Synchronized
    fun configure(strictMode: Boolean, port: Int): Boolean {
        if (port !in MIN_PORT..MAX_PORT) return false
        if (strictMode == isStrictModeEnabled && port == socksPort) return true

        val candidate = if (strictMode) {
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(loopback, port))
        } else {
            null
        }
        socksPort = port
        activeProxy = candidate
        isStrictModeEnabled = strictMode
        _revision.value = TorProxyRevision(strictMode, port, _revision.value.revision + 1)
        return true
    }

    override fun select(uri: URI?): List<Proxy> {
        requireNotNull(uri) { "URI must not be null" }
        return listOf(activeProxy ?: Proxy.NO_PROXY)
    }

    /**
     * Intentionally does not return a direct proxy after a failure. Strict mode never
     * silently downgrades to a direct Internet connection.
     */
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    companion object {
        const val DEFAULT_SOCKS_PORT = 9050
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535
    }
}
