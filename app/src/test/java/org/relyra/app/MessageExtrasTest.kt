package org.relyra.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.relyra.app.core.messaging.*
import java.util.UUID

private class MemReactions : ReactionStore {
    val map = HashMap<Pair<String, String>, StoredReaction>()
    override suspend fun current(messageId: String, reactorId: String) = map[messageId to reactorId]
    override suspend fun applyIfNewer(r: StoredReaction): Boolean {
        val e = map[r.messageId to r.reactorId]
        if (e != null && e.updatedAt >= r.updatedAt) return false
        map[r.messageId to r.reactorId] = r
        return true
    }
}

private class MemControls : ControlStore {
    val rows = LinkedHashMap<String, StoredControl>()
    override suspend fun insert(c: StoredControl) { rows[c.controlId] = c }
    override suspend fun get(id: String) = rows[id]
    override suspend fun update(c: StoredControl) { rows[c.controlId] = c }
    override suspend fun due(now: Long, limit: Int) = rows.values.filter { !it.done && it.nextAttemptAt <= now }.take(limit)
    override suspend fun supersede(targetId: String, kind: ControlKind, exceptId: String) {
        rows.values.filter { it.targetId == targetId && it.kind == kind && it.controlId != exceptId && !it.done }
            .forEach { rows[it.controlId] = it.copy(done = true, nextAttemptAt = Long.MAX_VALUE) }
    }
    override suspend fun purgeDone(olderThan: Long) { rows.values.removeAll { it.done && it.createdAt < olderThan } }
}

private object NoLock : StateLock {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

class MessageExtrasTest {
    private val me = "me-id"
    private val peer = "peer-id"
    private val other = "other-id"

    private class Rig(val me: String) {
        val store = FakeStore()
        val reactions = MemReactions()
        val controls = MemControls()
        val sent = mutableListOf<Pair<String, Envelope>>()
        var now = 1_000_000_000L
        var outcome: SendOutcome = SendOutcome.Accepted
        val extras = MessageExtras(
            messages = store, reactions = reactions, controls = controls,
            ownId = { me },
            transmit = { p, b -> EnvelopeCodec.decode(b)?.let { sent += p to it }; outcome },
            stateLock = NoLock, clock = { now },
        )
        fun msg(id: String = UUID.randomUUID().toString(), chat: String, outgoing: Boolean, body: String = "hello",
                state: MessageState = if (outgoing) MessageState.DELIVERED else MessageState.DELIVERED): String {
            store.rows[id] = StoredMessage(id, chat, outgoing, body, now, state, 0, Long.MAX_VALUE, !outgoing)
            return id
        }
    }

    @Test fun codecRoundTripsReactionAndEdit() {
        val id = UUID.randomUUID().toString(); val t = UUID.randomUUID().toString()
        val r = EnvelopeCodec.decode(EnvelopeCodec.encode(Envelope.Reaction(id, "a", "b", t, "\uD83D\uDC4D", 5L))) as Envelope.Reaction
        assertEquals(t, r.targetId); assertEquals("\uD83D\uDC4D", r.emoji); assertEquals(5L, r.rev)
        val e = EnvelopeCodec.decode(EnvelopeCodec.encode(Envelope.Edit(id, "a", "b", t, "new", 9L, 2))) as Envelope.Edit
        assertEquals("new", e.body); assertEquals(2, e.rev)
        // an empty edit body or a zero revision is never valid on the wire
        assertNull(EnvelopeCodec.decode(EnvelopeCodec.encode(Envelope.Reaction(id, "a", "b", t, "", 1L)).copyOf().also { it[it.size - 1] = 0; for (i in it.size - 8 until it.size) it[i] = 0 }))
    }

    @Test fun emojiValidation() {
        assertTrue(MessageExtras.validEmoji(""))
        assertTrue(MessageExtras.validEmoji("\uD83D\uDC4D"))
        assertFalse(MessageExtras.validEmoji("abc"))
        assertFalse(MessageExtras.validEmoji("\uD83D\uDC4D "))
        assertFalse(MessageExtras.validEmoji("\n"))
        assertFalse(MessageExtras.validEmoji("\uD83D\uDC4D".repeat(9)))
    }

    @Test fun reactionIsStoredQueuedAndAcknowledgedOnce() = runBlocking {
        val rig = Rig(me)
        val m = rig.msg(chat = peer, outgoing = false)
        assertEquals(ExtraResult.OK, rig.extras.react(peer, m, "\uD83D\uDC4D"))
        assertEquals("\uD83D\uDC4D", rig.reactions.map[m to me]!!.emoji)
        val sentReaction = rig.sent.single { it.second is Envelope.Reaction }
        assertEquals(peer, sentReaction.first)
        assertEquals(ExtraResult.UNCHANGED, rig.extras.react(peer, m, "\uD83D\uDC4D"))
        // ack stops retries
        val ctl = rig.controls.rows.values.single { !it.done }
        assertTrue(rig.extras.onAck(Envelope.Ack(ctl.controlId, peer, me, AckKind.DELIVERED)))
        assertTrue(rig.controls.rows[ctl.controlId]!!.done)
        rig.now += 10_000_000
        val before = rig.sent.size
        rig.extras.processControls()
        assertEquals(before, rig.sent.size)
    }

    @Test fun ackFromWrongPeerDoesNotCompleteControl() = runBlocking {
        val rig = Rig(me)
        val m = rig.msg(chat = peer, outgoing = false)
        rig.extras.react(peer, m, "\u2764\uFE0F")
        val ctl = rig.controls.rows.values.single()
        assertFalse(rig.extras.onAck(Envelope.Ack(ctl.controlId, other, me, AckKind.DELIVERED)))
        assertFalse(rig.controls.rows[ctl.controlId]!!.done)
    }

    @Test fun incomingReactionLastWriterWinsAndRequiresConversationMembership() = runBlocking {
        val rig = Rig(me)
        val m = rig.msg(chat = peer, outgoing = true)
        val id = { UUID.randomUUID().toString() }
        rig.extras.onEnvelope(Envelope.Reaction(id(), peer, me, m, "\uD83D\uDE02", rig.now - 10))
        rig.extras.onEnvelope(Envelope.Reaction(id(), peer, me, m, "\uD83D\uDE22", rig.now - 20)) // older: ignored
        assertEquals("\uD83D\uDE02", rig.reactions.map[m to peer]!!.emoji)
        rig.extras.onEnvelope(Envelope.Reaction(id(), peer, me, m, "", rig.now - 5)) // newer removal
        assertEquals("", rig.reactions.map[m to peer]!!.emoji)
        // someone who is not in this conversation cannot react
        rig.extras.onEnvelope(Envelope.Reaction(id(), other, me, m, "\uD83D\uDE02", rig.now))
        assertNull(rig.reactions.map[m to other])
        // a far-future timestamp cannot pin a reaction
        rig.extras.onEnvelope(Envelope.Reaction(id(), peer, me, m, "\uD83D\uDE22", rig.now + 3_600_000))
        assertEquals("", rig.reactions.map[m to peer]!!.emoji)
    }

    @Test fun ownEditUpdatesTextBumpsRevisionAndQueuesControl() = runBlocking {
        val rig = Rig(me)
        val m = rig.msg(chat = peer, outgoing = true, body = "old")
        assertEquals(ExtraResult.OK, rig.extras.edit(m, "new"))
        val row = rig.store.rows[m]!!
        assertEquals("new", row.body); assertEquals(1, row.editRev); assertNotNull(row.editedAt)
        val e = rig.sent.map { it.second }.filterIsInstance<Envelope.Edit>().single()
        assertEquals(1, e.rev); assertEquals("new", e.body)
        assertEquals(ExtraResult.UNCHANGED, rig.extras.edit(m, "new"))
    }

    @Test fun editRulesWindowIncomingAndEmpty() = runBlocking {
        val rig = Rig(me)
        val incoming = rig.msg(chat = peer, outgoing = false)
        assertEquals(ExtraResult.NOT_ALLOWED, rig.extras.edit(incoming, "x"))
        val old = rig.msg(chat = peer, outgoing = true)
        rig.now += MessageExtras.EDIT_WINDOW_MS + 1
        assertEquals(ExtraResult.TOO_LATE, rig.extras.edit(old, "later"))
        assertEquals(ExtraResult.INVALID, rig.extras.edit(old, "  "))
        assertEquals(ExtraResult.NOT_FOUND, rig.extras.edit(UUID.randomUUID().toString(), "x"))
    }

    @Test fun queuedMessageWithoutPacketIsEditedInPlaceWithoutControl() = runBlocking {
        val rig = Rig(me)
        val m = rig.msg(chat = peer, outgoing = true, body = "draft", state = MessageState.QUEUED)
        assertEquals(ExtraResult.OK, rig.extras.edit(m, "final"))
        assertEquals("final", rig.store.rows[m]!!.body)
        assertNull(rig.store.rows[m]!!.editedAt)
        assertTrue(rig.sent.isEmpty())
    }

    @Test fun incomingEditOnlyFromAuthorAndOnlyNewerRevision() = runBlocking {
        val rig = Rig(me)
        val m = rig.msg(chat = peer, outgoing = false, body = "v0")
        val id = { UUID.randomUUID().toString() }
        rig.extras.onEnvelope(Envelope.Edit(id(), peer, me, m, "v2", rig.now, 2))
        assertEquals("v2", rig.store.rows[m]!!.body)
        rig.extras.onEnvelope(Envelope.Edit(id(), peer, me, m, "v1", rig.now, 1))   // replayed older revision
        assertEquals("v2", rig.store.rows[m]!!.body)
        rig.extras.onEnvelope(Envelope.Edit(id(), other, me, m, "evil", rig.now, 9)) // not the author
        assertEquals("v2", rig.store.rows[m]!!.body)
        // an edit of OUR OWN outgoing message cannot be pushed by the peer
        val mine = rig.msg(chat = peer, outgoing = true, body = "mine")
        rig.extras.onEnvelope(Envelope.Edit(id(), peer, me, mine, "hijack", rig.now, 1))
        assertEquals("mine", rig.store.rows[mine]!!.body)
        // duplicate of an applied revision is acknowledged again so the sender stops retrying
        val acks = rig.sent.count { it.second is Envelope.Ack }
        rig.extras.onEnvelope(Envelope.Edit(id(), peer, me, m, "v2", rig.now, 2))
        assertEquals(acks + 1, rig.sent.count { it.second is Envelope.Ack })
    }

    @Test fun editArrivingBeforeOriginalIsNotAcknowledged() = runBlocking {
        val rig = Rig(me)
        rig.extras.onEnvelope(Envelope.Edit(UUID.randomUUID().toString(), peer, me, UUID.randomUUID().toString(), "x", rig.now, 1))
        assertTrue(rig.sent.isEmpty())
    }

    @Test fun controlsAreRetriedWithBackoffAndGiveUp() = runBlocking {
        val rig = Rig(me)
        val m = rig.msg(chat = peer, outgoing = false)
        rig.outcome = SendOutcome.Unavailable
        rig.extras.react(peer, m, "\uD83D\uDE4F")
        val id = rig.controls.rows.keys.single()
        repeat(MessageExtras.MAX_ATTEMPTS + 2) { rig.now += 1_000_000L; rig.extras.processControls() }
        assertTrue(rig.controls.rows[id]!!.done)
    }

    @Test fun newerReactionSupersedesPendingOlderControl() = runBlocking {
        val rig = Rig(me)
        val m = rig.msg(chat = peer, outgoing = false)
        rig.outcome = SendOutcome.Unavailable
        rig.extras.react(peer, m, "\uD83D\uDC4D")
        rig.extras.react(peer, m, "\u2764\uFE0F")
        assertEquals(1, rig.controls.rows.values.count { !it.done })
    }
}
