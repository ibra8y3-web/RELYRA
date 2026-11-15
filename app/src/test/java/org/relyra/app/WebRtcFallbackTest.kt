package org.relyra.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.messaging.DeliveryEngine
import org.relyra.app.core.messaging.Envelope
import org.relyra.app.core.messaging.EnvelopeCodec
import org.relyra.app.core.messaging.SendOutcome
import org.relyra.app.core.network.AdaptiveRouter
import org.relyra.app.core.network.Connector
import java.util.UUID

class WebRtcFallbackTest {
    @Test fun lostDirectAcknowledgementFallsBackToNostrWithoutCreatingDuplicateMessage() = runBlocking {
        val receiverStore = FakeStore()
        val receivingEngine = DeliveryEngine(
            store = receiverStore,
            peers = Peers(mapOf("alice" to org.relyra.app.core.messaging.PeerStatus.Known)),
            protector = PlainProtector,
            transports = { emptyList() },
            ownId = { "bob" },
            clock = { 1_800_000_000L },
        )
        val packet = EnvelopeCodec.encode(
            Envelope.Chat(UUID.randomUUID().toString(), "alice", "bob", 1_800_000_000L, "fallback once")
        )
        var directCalls = 0
        var relayCalls = 0
        val direct = object : Connector {
            override val name = "Internet P2P (WebRTC)"
            override fun isReady() = true
            override suspend fun send(toPeer: String, payload: ByteArray): SendOutcome {
                directCalls++
                assertTrue(receivingEngine.acceptWire(payload))
                // Simulate the receiver committing the packet but its ACK getting lost on disconnect.
                return SendOutcome.Unavailable
            }
        }
        val nostr = object : Connector {
            override val name = "Nostr"
            override fun isReady() = true
            override suspend fun send(toPeer: String, payload: ByteArray): SendOutcome {
                relayCalls++
                assertTrue(receivingEngine.acceptWire(payload))
                return SendOutcome.Relayed
            }
        }
        val router = AdaptiveRouter { listOf(direct, nostr) }

        assertEquals(SendOutcome.Relayed, router.send("bob", packet))
        assertEquals(1, directCalls)
        assertEquals(1, relayCalls)
        assertEquals(1, receiverStore.rows.values.count { !it.outgoing })
        assertEquals(1, receiverStore.rows.values.map { it.messageId }.distinct().size)
    }

    @Test fun successfulDirectAcknowledgementDoesNotSendDuplicateOverNostr() = runBlocking {
        val packet = EnvelopeCodec.encode(
            Envelope.Chat(UUID.randomUUID().toString(), "alice", "bob", 1_800_000_000L, "direct")
        )
        var relayCalls = 0
        val direct = object : Connector {
            override val name = "Internet P2P (WebRTC)"
            override fun isReady() = true
            override suspend fun send(toPeer: String, payload: ByteArray) = SendOutcome.Accepted
        }
        val nostr = object : Connector {
            override val name = "Nostr"
            override fun isReady() = true
            override suspend fun send(toPeer: String, payload: ByteArray): SendOutcome {
                relayCalls++
                return SendOutcome.Relayed
            }
        }

        assertEquals(SendOutcome.Accepted, AdaptiveRouter { listOf(direct, nostr) }.send("bob", packet))
        assertEquals(0, relayCalls)
    }
}
