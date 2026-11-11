package org.relyra.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.messaging.*
import java.util.UUID

internal class FakeStore : MessageStore {
    val rows = LinkedHashMap<String, StoredMessage>()
    val attachments = HashMap<String, StoredAttachment>()
    private val processed = HashSet<String>()
    override suspend fun insertOutgoing(m: StoredMessage) { rows[m.messageId] = m }
    override suspend fun dueOutgoing(now: Long, limit: Int) = rows.values.filter {
        it.outgoing && it.state in setOf(MessageState.QUEUED, MessageState.SENT, MessageState.RELAYED) && it.nextAttemptAt <= now
    }.sortedBy { it.createdAt }.take(limit)
    override suspend fun get(id: String) = rows[id]
    override suspend fun update(m: StoredMessage) { rows[m.messageId] = m }
    override suspend fun recordIncoming(m: StoredMessage): Boolean {
        if (!processed.add(m.messageId)) return false
        rows[m.messageId] = m
        return true
    }
    override suspend fun unreadIncoming(chatId: String) = rows.values.filter { !it.outgoing && it.chatId == chatId && !it.readLocally }
    override suspend fun markReadLocal(ids: List<String>) { ids.forEach { rows[it] = rows[it]!!.copy(readLocally = true) } }
    override suspend fun insertOutgoingAttachment(m: StoredMessage, a: StoredAttachment) {
        rows[m.messageId] = m
        attachments[m.messageId] = a
    }
    override suspend fun attachment(messageId: String) = attachments[messageId]
    override suspend fun updateAttachment(a: StoredAttachment) { attachments[a.messageId] = a }
    override suspend fun recordIncomingAttachment(m: StoredMessage, a: StoredAttachment): Boolean {
        if (!processed.add(m.messageId)) return false
        rows[m.messageId] = m
        attachments[m.messageId] = a
        return true
    }
}

/** Test-only: no encryption, so engine logic can be tested in isolation. Real crypto is covered by PayloadCryptoTest. */
internal object PlainProtector : PayloadProtector {
    override suspend fun protect(peerId: String, plaintext: ByteArray): ByteArray? = plaintext
    override suspend fun open(wire: ByteArray): Opened? = EnvelopeCodec.decode(wire)?.let { Opened(it.from, wire) }
}

internal class Peers(val map: Map<String, PeerStatus>) : PeerDirectory {
    override suspend fun status(publicId: String) = map[publicId] ?: PeerStatus.Unknown
}

/** A scriptable link between two engines. */
internal class Link(var deliver: (suspend (ByteArray) -> Unit)? = null) : Transport {
    override val name = "test-link"
    var up = true
    var sendCount = 0
    var dropNext = 0
    val sent = mutableListOf<ByteArray>()
    override suspend fun send(toPeer: String, payload: ByteArray): SendOutcome {
        if (!up) return SendOutcome.Unavailable
        sendCount++
        sent += payload
        if (dropNext > 0) { dropNext--; return SendOutcome.Accepted } // accepted by network but lost
        deliver?.invoke(payload)
        return SendOutcome.Accepted
    }
}

internal class Node(val id: String, val peerId: String, val clock: () -> Long, uploader: BlobUploader? = null) {
    val store = FakeStore()
    val link = Link()
    val engine = DeliveryEngine(
        store, Peers(mapOf(peerId to PeerStatus.Known)), PlainProtector, { listOf(link) }, { id }, clock,
        uploader = uploader,
    )
}

class DeliveryEngineTest {
    private var now = 1_000_000L
    private val clock = { now }
    private fun pair(): Pair<Node, Node> {
        val a = Node("alice", "bob", clock)
        val b = Node("bob", "alice", clock)
        a.link.deliver = { b.engine.onPacket(it) }
        b.link.deliver = { a.engine.onPacket(it) }
        return a to b
    }

    @Test fun identicalTextSentThreeTimesIsThreeMessages() = runBlocking {
        val (a, b) = pair()
        repeat(3) { a.engine.enqueue("bob", "السلام عليكم") }
        a.engine.processOutbox()
        assertEquals(3, a.store.rows.keys.size)
        assertEquals(3, b.store.rows.values.count { !it.outgoing })
        assertEquals(3, b.store.rows.values.map { it.messageId }.toSet().size)
    }

    @Test fun retriesReuseTheSameIdAndReceiverStoresOnce() = runBlocking {
        val (a, b) = pair()
        val id = (a.engine.enqueue("bob", "hello") as EnqueueResult.Queued).messageId
        a.link.up = false
        repeat(5) { a.engine.processOutbox(); now += 20 * 60_000L }   // five failed attempts
        assertEquals(0, b.store.rows.size)
        a.link.up = true
        a.engine.processOutbox()
        assertEquals(listOf(id), b.store.rows.keys.toList())
        assertEquals(MessageState.DELIVERED, a.store.rows[id]!!.state)
    }

    @Test fun duplicatePacketsAreStoredOnceButAlwaysReAcknowledged() = runBlocking {
        val (a, b) = pair()
        a.engine.enqueue("bob", "once")
        a.engine.processOutbox()
        val packet = a.link.sent.first()
        repeat(5) { b.engine.onPacket(packet) }
        assertEquals(1, b.store.rows.values.count { !it.outgoing })
        assertEquals(6, b.link.sendCount)           // 1 original ack + 5 re-acks
    }

    @Test fun lostAckCausesResendThenDelivered() = runBlocking {
        val (a, b) = pair()
        b.link.dropNext = 1
        val id = (a.engine.enqueue("bob", "hi") as EnqueueResult.Queued).messageId
        a.engine.processOutbox()
        assertEquals(MessageState.SENT, a.store.rows[id]!!.state)
        a.engine.processOutbox()                      // not due yet: must not resend
        assertEquals(1, a.link.sendCount)
        now += 2 * 60_000L
        a.engine.processOutbox()
        assertEquals(2, a.link.sendCount)
        assertEquals(1, b.store.rows.values.count { !it.outgoing })
        assertEquals(MessageState.DELIVERED, a.store.rows[id]!!.state)
    }

    @Test fun stateNeverMovesBackwards() = runBlocking {
        val (a, b) = pair()
        val id = (a.engine.enqueue("bob", "x") as EnqueueResult.Queued).messageId
        a.engine.processOutbox()
        b.engine.markChatRead("alice", true)
        assertEquals(MessageState.READ, a.store.rows[id]!!.state)
        val lateDelivered = EnvelopeCodec.encode(Envelope.Ack(id, "bob", "alice", AckKind.DELIVERED))
        a.engine.onPacket(lateDelivered)
        assertEquals(MessageState.READ, a.store.rows[id]!!.state)
    }

    @Test fun unknownAndBlockedSendersAreDropped() = runBlocking {
        val a = Node("alice", "bob", clock)
        val stranger = EnvelopeCodec.encode(Envelope.Chat(UUID.randomUUID().toString(), "mallory", "alice", 1, "spam"))
        a.engine.onPacket(stranger)
        assertTrue(a.store.rows.isEmpty())
        val blocked = DeliveryEngine(a.store, Peers(mapOf("bob" to PeerStatus.Blocked)), PlainProtector, { listOf(a.link) }, { "alice" }, clock)
        blocked.onPacket(EnvelopeCodec.encode(Envelope.Chat(UUID.randomUUID().toString(), "bob", "alice", 1, "hi")))
        assertTrue(a.store.rows.isEmpty())
        assertEquals(0, a.link.sendCount)           // no ack to blocked senders
    }

    @Test fun enqueueValidation() = runBlocking {
        val a = Node("alice", "bob", clock)
        assertEquals(EnqueueResult.Empty, a.engine.enqueue("bob", "   "))
        assertEquals(EnqueueResult.UnknownPeer, a.engine.enqueue("carol", "hi"))
        assertEquals(EnqueueResult.TooLong, a.engine.enqueue("bob", "x".repeat(EnvelopeCodec.MAX_BODY + 1)))
    }

    @Test fun contactWithoutKeysCannotBeMessaged() = runBlocking {
        val e = DeliveryEngine(FakeStore(), Peers(mapOf("bob" to PeerStatus.NoKeys)), PlainProtector, { emptyList() }, { "alice" }, clock)
        assertEquals(EnqueueResult.NoEncryptionKey, e.enqueue("bob", "hi"))
    }

    @Test fun innerSenderMustMatchAuthenticatedSender() = runBlocking {
        val a = Node("alice", "bob", clock)
        val spoof = object : PayloadProtector {
            override suspend fun protect(peerId: String, plaintext: ByteArray) = plaintext
            override suspend fun open(wire: ByteArray) = Opened("bob", wire)   // authenticated as bob...
        }
        val engine = DeliveryEngine(a.store, Peers(mapOf("bob" to PeerStatus.Known, "carol" to PeerStatus.Known)), spoof, { listOf(a.link) }, { "alice" }, clock)
        engine.onWire(EnvelopeCodec.encode(Envelope.Chat(UUID.randomUUID().toString(), "carol", "alice", 1, "hi"))) // ...but claims carol
        assertTrue(a.store.rows.isEmpty())
    }

    @Test fun cancelOnlyWorksWhileQueued() = runBlocking {
        val (a, _) = pair()
        a.link.up = false
        val id = (a.engine.enqueue("bob", "oops") as EnqueueResult.Queued).messageId
        a.engine.cancel(id)
        assertEquals(MessageState.CANCELLED, a.store.rows[id]!!.state)
        a.link.up = true
        a.engine.processOutbox()
        assertEquals(0, a.link.sendCount)
    }

    @Test fun queuedMessageExpiresAfterTtl() = runBlocking {
        val (a, _) = pair()
        a.link.up = false
        val id = (a.engine.enqueue("bob", "old") as EnqueueResult.Queued).messageId
        now += DeliveryEngine.TTL_MS + 1
        a.engine.processOutbox()
        assertEquals(MessageState.EXPIRED, a.store.rows[id]!!.state)
    }


    @Test fun unavailableTransportRetryReusesPersistedCiphertextWithoutAdvancingProtector() = runBlocking {
        val store = FakeStore()
        val link = Link().also { it.up = false }
        var protectCalls = 0
        val protector = object : PayloadProtector {
            override suspend fun protect(peerId: String, plaintext: ByteArray): ByteArray {
                protectCalls++
                return byteArrayOf(protectCalls.toByte())
            }
            override suspend fun open(wire: ByteArray): Opened? = null
        }
        val engine = DeliveryEngine(
            store, Peers(mapOf("bob" to PeerStatus.Known)), protector, { listOf(link) }, { "alice" }, clock,
        )
        val id = (engine.enqueue("bob", "persist me") as EnqueueResult.Queued).messageId
        engine.processOutbox()
        assertEquals(1, protectCalls)
        assertEquals(byteArrayOf(1), store.rows[id]!!.wirePayload)
        now += DeliveryEngine.backoff(1) + 1
        link.up = true
        engine.processOutbox()
        assertEquals(1, protectCalls)
        assertEquals(byteArrayOf(1), link.sent.single())
    }

    @Test fun backoffGrowsAndIsCapped() {
        assertTrue(DeliveryEngine.backoff(2) > DeliveryEngine.backoff(1))
        assertEquals(900_000L, DeliveryEngine.backoff(40))
        assertEquals(3_600_000L, DeliveryEngine.ackWait(40))
    }
}

class EnvelopeCodecTest {
    @Test fun chatRoundTripWithArabicAndEmoji() {
        val id = UUID.randomUUID().toString()
        val e = EnvelopeCodec.decode(EnvelopeCodec.encode(Envelope.Chat(id, "a", "b", 42, "مرحبا 👋")))
        e as Envelope.Chat
        assertEquals("مرحبا 👋", e.body)
        assertEquals(id, e.messageId)
        assertEquals(42L, e.sentAt)
    }

    @Test fun ackRoundTrip() {
        val id = UUID.randomUUID().toString()
        val e = EnvelopeCodec.decode(EnvelopeCodec.encode(Envelope.Ack(id, "a", "b", AckKind.READ))) as Envelope.Ack
        assertEquals(AckKind.READ, e.kind)
    }

    @Test fun garbageIsRejectedWithoutThrowing() {
        assertNull(EnvelopeCodec.decode(ByteArray(0)))
        assertNull(EnvelopeCodec.decode(byteArrayOf(1, 2, 3)))
        assertNull(EnvelopeCodec.decode(ByteArray(70_000)))
        val bad = EnvelopeCodec.encode(Envelope.Chat("not-a-uuid", "a", "b", 1, "x"))
        assertNull(EnvelopeCodec.decode(bad))
        val truncated = EnvelopeCodec.encode(Envelope.Chat(UUID.randomUUID().toString(), "a", "b", 1, "hello")).copyOf(20)
        assertNull(EnvelopeCodec.decode(truncated))
    }

    @Test fun stateMachine() {
        assertTrue(MessageState.canAdvance(MessageState.QUEUED, MessageState.SENT))
        assertTrue(MessageState.canAdvance(MessageState.SENT, MessageState.DELIVERED))
        assertTrue(MessageState.canAdvance(MessageState.FAILED, MessageState.DELIVERED))
        assertNotEquals(true, MessageState.canAdvance(MessageState.READ, MessageState.DELIVERED))
        assertNotEquals(true, MessageState.canAdvance(MessageState.CANCELLED, MessageState.DELIVERED))
        assertNotEquals(true, MessageState.canAdvance(MessageState.RELAYED, MessageState.SENT))
    }
}
