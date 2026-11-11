package org.relyra.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.messaging.SendOutcome
import org.relyra.app.core.network.AdaptiveRouter
import org.relyra.app.core.network.Connector

private class ScriptedConnector(
    override val name: String,
    var ready: Boolean = true,
    var result: SendOutcome = SendOutcome.Accepted,
) : Connector {
    var calls: Int = 0
        private set

    override fun isReady(): Boolean = ready

    override suspend fun send(toPeer: String, payload: ByteArray): SendOutcome {
        calls++
        return result
    }
}

class AdaptiveRouterTest {
    @Test
    fun sendsThroughFirstReadyConnectorAndRemembersPath() = runBlocking {
        val offline = ScriptedConnector("offline", ready = false)
        val primary = ScriptedConnector("primary", result = SendOutcome.Relayed)
        val secondary = ScriptedConnector("secondary")
        val router = AdaptiveRouter { listOf(offline, primary, secondary) }

        val result = router.send("peer-1", byteArrayOf(1, 2, 3))

        assertEquals(SendOutcome.Relayed, result)
        assertEquals(0, offline.calls)
        assertEquals(1, primary.calls)
        assertEquals(0, secondary.calls)
        assertEquals("primary", router.lastPath.value)
    }

    @Test
    fun fallsBackWhenFirstReadyConnectorIsUnavailable() = runBlocking {
        val first = ScriptedConnector("first", result = SendOutcome.Unavailable)
        val second = ScriptedConnector("second", result = SendOutcome.Accepted)
        val router = AdaptiveRouter { listOf(first, second) }

        val result = router.send("peer-2", byteArrayOf(9))

        assertEquals(SendOutcome.Accepted, result)
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
        assertEquals("second", router.lastPath.value)
    }

    @Test
    fun doesNotCallConnectorsThatReportNotReady() = runBlocking {
        val first = ScriptedConnector("first", ready = false)
        val second = ScriptedConnector("second", ready = false)
        val router = AdaptiveRouter { listOf(first, second) }

        assertFalse(router.isReady())
        assertEquals(SendOutcome.Unavailable, router.send("peer", byteArrayOf(1)))
        assertEquals(0, first.calls)
        assertEquals(0, second.calls)
    }

    @Test
    fun permanentRejectionIsReturnedWhenNoConnectorAccepts() = runBlocking {
        val first = ScriptedConnector("first", result = SendOutcome.Rejected("permanent"))
        val second = ScriptedConnector("second", result = SendOutcome.Rejected("second reason"))
        val router = AdaptiveRouter { listOf(first, second) }

        val result = router.send("peer", byteArrayOf(1))

        assertTrue(result is SendOutcome.Rejected)
        assertEquals("second reason", (result as SendOutcome.Rejected).reason)
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
    }

    @Test
    fun temporaryFailureTakesPrecedenceOverEarlierPermanentRejection() = runBlocking {
        val first = ScriptedConnector("first", result = SendOutcome.Rejected("bad peer"))
        val second = ScriptedConnector("second", result = SendOutcome.Unavailable)
        val router = AdaptiveRouter { listOf(first, second) }

        assertEquals(SendOutcome.Unavailable, router.send("peer", byteArrayOf(1)))
    }
}
