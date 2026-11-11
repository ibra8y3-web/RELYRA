package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.network.TorProxySelector
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

class TorProxySelectorTest {
    @Test
    fun directRouteIsUsedOnlyWhileStrictModeIsDisabled() {
        val selector = TorProxySelector()
        assertEquals(Proxy.NO_PROXY, selector.select(URI("wss://relay.example")).single())

        assertTrue(selector.configure(strictMode = true, port = 9050))
        val proxy = selector.select(URI("wss://relay.example")).single()
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.address.hostAddress)
        assertEquals(9050, address.port)
    }

    @Test
    fun strictModeHasNoDirectFallbackAfterProxyFailure() {
        val selector = TorProxySelector()
        selector.configure(strictMode = true, port = 9150)
        selector.connectFailed(
            URI("wss://relay.example"),
            InetSocketAddress("127.0.0.1", 9150),
            java.io.IOException("Tor is stopped"),
        )
        val proxies = selector.select(URI("wss://relay.example"))
        assertEquals(1, proxies.size)
        assertEquals(Proxy.Type.SOCKS, proxies.single().type())
    }

    @Test
    fun disablingStrictModeRestoresDirectRoutingAndInvalidPortsAreRejected() {
        val selector = TorProxySelector()
        assertTrue(selector.configure(strictMode = true, port = 9050))
        assertFalse(selector.configure(strictMode = true, port = 70_000))
        assertTrue(selector.isStrictModeEnabled)

        assertTrue(selector.configure(strictMode = false, port = 9050))
        assertFalse(selector.isStrictModeEnabled)
        assertEquals(Proxy.NO_PROXY, selector.select(URI("https://files.example")).single())
    }

    @Test
    fun strictProxyConfigurationChangesHaveRevisionNumbers() {
        val selector = TorProxySelector()
        val initial = selector.revision.value.revision
        selector.configure(strictMode = true, port = 9050)
        assertEquals(initial + 1, selector.revision.value.revision)
        selector.configure(strictMode = false, port = 9050)
        assertEquals(initial + 2, selector.revision.value.revision)
    }
}
