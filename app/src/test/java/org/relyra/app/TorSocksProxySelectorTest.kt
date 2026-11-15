package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.network.TorSocksProxySelector
import org.relyra.app.core.network.TorSocksSettings
import java.net.Proxy
import java.net.URI

class TorSocksProxySelectorTest {
    @Test
    fun disabledModeUsesDirectConnection() {
        TorSocksProxySelector.configure(TorSocksSettings(enabled = false))
        val routes = TorSocksProxySelector.select(URI("https://example.org"))
        assertEquals(1, routes.size)
        assertEquals(Proxy.NO_PROXY, routes.single())
    }

    @Test
    fun strictModeReturnsOnlySocksAndCannotFallbackToDirect() {
        TorSocksProxySelector.configure(TorSocksSettings(enabled = true, strict = true, port = 9050))
        val routes = TorSocksProxySelector.select(URI("wss://relay.example"))
        assertEquals(1, routes.size)
        assertEquals(Proxy.Type.SOCKS, routes.single().type())
        assertEquals(9050, (routes.single().address() as java.net.InetSocketAddress).port)
    }

    @Test
    fun nonStrictModeExplicitlyAllowsDirectFallback() {
        TorSocksProxySelector.configure(TorSocksSettings(enabled = true, strict = false, port = 9150))
        val routes = TorSocksProxySelector.select(URI("https://example.org"))
        assertEquals(2, routes.size)
        assertEquals(Proxy.Type.SOCKS, routes[0].type())
        assertEquals(Proxy.NO_PROXY, routes[1])
    }

    @Test
    fun nonHttpSchemesNeverUseTorHttpProxy() {
        TorSocksProxySelector.configure(TorSocksSettings(enabled = true, strict = true))
        assertEquals(Proxy.NO_PROXY, TorSocksProxySelector.select(URI("file:///tmp/a")).single())
    }

    @Test
    fun portRangeIsBounded() {
        assertTrue(TorSocksSettings.isValidPort(1))
        assertTrue(TorSocksSettings.isValidPort(65535))
        assertTrue(!TorSocksSettings.isValidPort(0))
        assertTrue(!TorSocksSettings.isValidPort(65536))
    }
}
