package org.relyra.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.messaging.DeliveryEngine
import org.relyra.app.core.messaging.Envelope
import org.relyra.app.core.messaging.EnvelopeCodec
import org.relyra.app.core.messaging.MessageState
import org.relyra.app.core.messaging.PeerStatus
import java.util.UUID

class DeliveryEngineWireAcceptanceTest {
    @Test
    fun authenticKnownPacketIsAcceptedAndDuplicateIsStillSafeToReack() = runBlocking {
        val store = FakeStore()
        val link = Link()
        val engine = DeliveryEngine(
            store = store,
            peers = Peers(mapOf("bob" to PeerStatus.Known)),
            protector = PlainProtector,
            transports = { listOf(link) },
            ownId = { "alice" },
            clock = { 1_000_000L },
        )
        val messageId = UUID.randomUUID().toString()
        val wire = EnvelopeCodec.encode(
            Envelope.Chat(messageId, "bob", "alice", 1_000_000L, "hello")
        )

        assertTrue(engine.acceptWire(wire))
        assertEquals(1, store.rows.values.count { !it.outgoing })
        assertEquals(MessageState.DELIVERED, store.rows[messageId]?.state)

        // Same authenticated MessageID: do not create another visible row, but accept it for another ACK.
        assertTrue(engine.acceptWire(wire))
        assertEquals(1, store.rows.values.count { !it.outgoing })
        assertEquals(2, link.sendCount)
    }

    @Test
    fun rejectsUnknownSenderWrongRecipientAndMalformedFrames() = runBlocking {
        val store = FakeStore()
        val link = Link()
        val engine = DeliveryEngine(
            store = store,
            peers = Peers(mapOf("bob" to PeerStatus.Known)),
            protector = PlainProtector,
            transports = { listOf(link) },
            ownId = { "alice" },
            clock = { 1_000_000L },
        )
        val unknown = EnvelopeCodec.encode(
            Envelope.Chat(UUID.randomUUID().toString(), "mallory", "alice", 1_000_000L, "spam")
        )
        val wrongRecipient = EnvelopeCodec.encode(
            Envelope.Chat(UUID.randomUUID().toString(), "bob", "carol", 1_000_000L, "not for alice")
        )

        assertFalse(engine.acceptWire(unknown))
        assertFalse(engine.acceptWire(wrongRecipient))
        assertFalse(engine.acceptWire(byteArrayOf(1, 2, 3)))
        assertTrue(store.rows.isEmpty())
        assertEquals(0, link.sendCount)
    }
}
