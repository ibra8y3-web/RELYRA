package org.relyra.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.messaging.*
import java.util.UUID

internal class FakeGroupStore : GroupStore {
    val groups = HashMap<String, GroupRecord>()
    val memberMap = HashMap<String, List<GroupMemberRef>>()
    val messages = LinkedHashMap<String, GroupMessage>()
    val outbox = LinkedHashMap<String, OutboxRow>()
    private val processed = HashSet<String>()

    override suspend fun group(id: String) = groups[id]
    override suspend fun members(groupId: String) = memberMap[groupId] ?: emptyList()
    override suspend fun putGroup(g: GroupRecord, members: List<GroupMemberRef>) { groups[g.groupId] = g; memberMap[g.groupId] = members }
    override suspend fun setState(groupId: String, state: GroupState) { groups[groupId] = groups[groupId]!!.copy(state = state) }
    override suspend fun insertOutgoingMessage(m: GroupMessage, copies: List<OutboxRow>) {
        messages[m.messageId] = m
        copies.forEach { outbox[it.id] = it }
    }
    override suspend fun recordIncomingMessage(m: GroupMessage): Boolean {
        if (!processed.add(m.messageId)) return false
        messages[m.messageId] = m
        return true
    }
    override suspend fun enqueue(rows: List<OutboxRow>) { rows.forEach { outbox[it.id] = it } }
    override suspend fun dueOutbox(now: Long, limit: Int) =
        outbox.values.filter { !it.acked && !it.failed && it.nextAttemptAt <= now }.sortedBy { it.createdAt }.take(limit)
    override suspend fun updateOutbox(row: OutboxRow) { outbox[row.id] = row }
    override suspend fun outbox(id: String): OutboxRow? = outbox[id]
    override suspend fun markAcked(id: String, memberId: String): Boolean {
        val r = outbox[id] ?: return false
        if (r.memberId != memberId) return false
        outbox[id] = r.copy(acked = true)
        return true
    }
    override suspend fun deleteGroup(groupId: String) {
        groups.remove(groupId); memberMap.remove(groupId)
        messages.values.removeAll { it.groupId == groupId }
        outbox.values.removeAll { it.groupId == groupId }
    }
}

/** A tiny in-memory network: delivers an encoded envelope straight to the addressee's engine. */
internal class GroupNet {
    val nodes = HashMap<String, GNode>()
    val down = HashSet<String>()
    val log = mutableListOf<Pair<String, ByteArray>>()
}

internal class GNode(val id: String, val net: GroupNet, val clock: () -> Long) {
    val store = FakeGroupStore()
    val engine = GroupEngine(
        store = store,
        peers = Peers(mapOf("alice" to PeerStatus.Known, "bob" to PeerStatus.Known, "carol" to PeerStatus.Known, "dave" to PeerStatus.Known)),
        ownId = { id },
        transmit = { peer, plaintext ->
            if (peer in net.down) SendOutcome.Unavailable
            else {
                net.log += peer to plaintext
                net.nodes[peer]?.receive(plaintext)
                SendOutcome.Relayed
            }
        },
        clock = clock,
    )

    suspend fun receive(bytes: ByteArray) {
        val env = EnvelopeCodec.decode(bytes) ?: return
        if (env.to != id) return
        if (env is Envelope.Ack) engine.onAck(env) else engine.onEnvelope(env)
    }

    init { net.nodes[id] = this }
}

class GroupEngineTest {
    private var now = 1_000_000L
    private val net = GroupNet()
    private val alice = GNode("alice", net) { now }
    private val bob = GNode("bob", net) { now }
    private val carol = GNode("carol", net) { now }
    private val dave = GNode("dave", net) { now }

    private suspend fun pump() { repeat(3) { listOf(alice, bob, carol, dave).forEach { it.engine.processOutbox() } } }

    private suspend fun groupWith(kind: GroupKind = GroupKind.GROUP, vararg others: GNode): String {
        val gid = alice.engine.create("Team", kind, others.map { it.id }).groupId!!
        pump()
        others.forEach { assertEquals(GroupState.INVITED, it.store.group(gid)!!.state); it.engine.accept(gid) }
        return gid
    }

    @Test fun invitationNeedsAcceptanceAndMessagesBeforeItAreDropped() = runBlocking {
        val gid = alice.engine.create("Team", GroupKind.GROUP, listOf("bob", "carol")).groupId!!
        pump()
        assertEquals(GroupState.INVITED, bob.store.group(gid)!!.state)
        assertEquals(setOf("alice", "bob", "carol"), bob.store.members(gid).map { it.id }.toSet())
        alice.engine.send(gid, "early")
        pump()
        assertTrue(bob.store.messages.isEmpty())           // not accepted yet: nothing is shown
        bob.engine.accept(gid)
        now += 2 * 60_000L
        pump()                                              // the unacknowledged copy is retried and now accepted
        assertEquals(listOf("early"), bob.store.messages.values.map { it.body })
    }

    @Test fun messageReachesEveryAcceptedMemberOnceAndOwnerSeesDeliveryAcks() = runBlocking {
        val gid = groupWith(GroupKind.GROUP, bob, carol)
        alice.engine.send(gid, "hello all")
        pump()
        assertEquals(listOf("hello all"), bob.store.messages.values.map { it.body })
        assertEquals(listOf("hello all"), carol.store.messages.values.map { it.body })
        val copies = alice.store.outbox.values.filter { it.baseMessageId != null }
        assertEquals(2, copies.size)
        assertTrue(copies.all { it.acked })
    }

    @Test fun identicalTextSentTwiceIsTwoMessages() = runBlocking {
        val gid = groupWith(GroupKind.GROUP, bob)
        alice.engine.send(gid, "same")
        alice.engine.send(gid, "same")
        pump()
        assertEquals(2, bob.store.messages.size)
    }

    @Test fun lostAcksCauseRetriesButReceiverStoresOnce() = runBlocking {
        val gid = groupWith(GroupKind.GROUP, bob)
        net.down += "alice"                                  // bob's acks cannot reach alice
        alice.engine.send(gid, "once")
        pump()
        assertEquals(1, bob.store.messages.size)
        assertFalse(alice.store.outbox.values.first { it.baseMessageId != null }.acked)
        net.down.clear()
        now += 2 * 60_000L
        pump()
        assertEquals(1, bob.store.messages.size)
        assertTrue(alice.store.outbox.values.first { it.baseMessageId != null }.acked)
    }

    @Test fun channelOnlyStaffCanPost() = runBlocking {
        val gid = groupWith(GroupKind.CHANNEL, bob)
        assertEquals(GroupResult.NOT_ALLOWED, bob.engine.send(gid, "I want to post"))
        assertEquals(GroupResult.OK, alice.engine.send(gid, "announcement"))
        pump()
        assertEquals(listOf("announcement"), bob.store.messages.values.map { it.body })
        // a forged member message is ignored by the receiver as well
        val forged = Envelope.GroupChat(UUID.randomUUID().toString(), "bob", "alice", gid, now, "forged")
        alice.engine.onEnvelope(forged)
        assertTrue(alice.store.messages.values.none { it.body == "forged" })
    }

    @Test fun outsiderCannotChangeAGroup() = runBlocking {
        val gid = groupWith(GroupKind.GROUP, bob)
        val forged = Envelope.GroupSync(UUID.randomUUID().toString(), "dave", "bob", gid, "hacked", GroupKind.GROUP, 99,
            "dave", listOf(GroupMemberRef("dave", GroupRole.OWNER), GroupMemberRef("bob", GroupRole.MEMBER)))
        bob.engine.onEnvelope(forged)
        assertEquals("Team", bob.store.group(gid)!!.name)
        assertEquals("alice", bob.store.group(gid)!!.owner)
    }

    @Test fun plainMemberCannotChangeAGroup() = runBlocking {
        val gid = groupWith(GroupKind.GROUP, bob, carol)
        val forged = Envelope.GroupSync(UUID.randomUUID().toString(), "bob", "carol", gid, "mine now", GroupKind.GROUP, 50,
            "bob", listOf(GroupMemberRef("bob", GroupRole.OWNER), GroupMemberRef("carol", GroupRole.MEMBER), GroupMemberRef("alice", GroupRole.MEMBER)))
        carol.engine.onEnvelope(forged)
        assertEquals("alice", carol.store.group(gid)!!.owner)
        assertEquals(GroupResult.NOT_ALLOWED, bob.engine.addMembers(gid, listOf("dave")))
    }

    @Test fun adminCanManageMembersButNotTheOwnerOrOtherAdmins() = runBlocking {
        val gid = groupWith(GroupKind.GROUP, bob, carol)
        assertEquals(GroupResult.OK, alice.engine.setAdmin(gid, "bob", true))
        pump()
        assertEquals(GroupRole.ADMIN.name, bob.store.members(gid).first { it.id == "bob" }.role.name)

        assertEquals(GroupResult.NOT_ALLOWED, bob.engine.removeMember(gid, "alice"))
        assertEquals(GroupResult.NOT_ALLOWED, bob.engine.setAdmin(gid, "carol", true))
        assertEquals(GroupResult.OK, bob.engine.addMembers(gid, listOf("dave")))
        pump()
        assertEquals(setOf("alice", "bob", "carol", "dave"), alice.store.members(gid).map { it.id }.toSet())

        // an admin snapshot that also promotes someone is refused by the receiver
        val forged = Envelope.GroupSync(UUID.randomUUID().toString(), "bob", "carol", gid, "Team", GroupKind.GROUP, 77,
            "alice", listOf(GroupMemberRef("alice", GroupRole.OWNER), GroupMemberRef("bob", GroupRole.ADMIN),
                GroupMemberRef("carol", GroupRole.ADMIN)))
        carol.engine.onEnvelope(forged)
        assertEquals(GroupRole.MEMBER, carol.store.members(gid).first { it.id == "carol" }.role)
    }

    @Test fun removedMemberStopsReceiving() = runBlocking {
        val gid = groupWith(GroupKind.GROUP, bob, carol)
        assertEquals(GroupResult.OK, alice.engine.removeMember(gid, "carol"))
        pump()
        assertEquals(GroupState.REMOVED, carol.store.group(gid)!!.state)
        alice.engine.send(gid, "after removal")
        pump()
        assertEquals(listOf("after removal"), bob.store.messages.values.map { it.body })
        assertTrue(carol.store.messages.isEmpty())
    }

    @Test fun leavingUpdatesTheOwnerAndEveryoneElse() = runBlocking {
        val gid = groupWith(GroupKind.GROUP, bob, carol)
        assertEquals(GroupResult.OK, carol.engine.leave(gid))
        pump()
        assertEquals(GroupState.LEFT, carol.store.group(gid)!!.state)
        assertEquals(setOf("alice", "bob"), alice.store.members(gid).map { it.id }.toSet())
        assertEquals(setOf("alice", "bob"), bob.store.members(gid).map { it.id }.toSet())
        assertEquals(GroupResult.NOT_ALLOWED, alice.engine.leave(gid))   // the owner must hand over first
    }

    @Test fun ownershipCanBeTransferred() = runBlocking {
        val gid = groupWith(GroupKind.GROUP, bob)
        assertEquals(GroupResult.OK, alice.engine.transferOwnership(gid, "bob"))
        pump()
        assertEquals("bob", bob.store.group(gid)!!.owner)
        assertEquals(GroupRole.ADMIN, alice.store.members(gid).first { it.id == "alice" }.role)
        assertEquals(GroupResult.OK, alice.engine.leave(gid))
    }

    @Test fun membersMustBeKnownContacts() = runBlocking {
        val r = alice.engine.create("Team", GroupKind.GROUP, listOf("bob", "stranger"))
        assertEquals(GroupResult.UNKNOWN_CONTACT, r.result)
        assertNull(r.groupId)
    }

    @Test fun duplicateAndOldSnapshotsAreIgnoredButAcknowledged() = runBlocking {
        val gid = groupWith(GroupKind.GROUP, bob)
        alice.engine.rename(gid, "Renamed")
        pump()
        assertEquals("Renamed", bob.store.group(gid)!!.name)
        val epoch = bob.store.group(gid)!!.epoch
        val old = alice.net.log.map { EnvelopeCodec.decode(it.second) }.filterIsInstance<Envelope.GroupSync>().first { it.epoch < epoch }
        bob.engine.onEnvelope(old)
        assertEquals("Renamed", bob.store.group(gid)!!.name)
        assertEquals(epoch, bob.store.group(gid)!!.epoch)
    }


    @Test fun concurrentAdminEditsAtSameEpochConvergeOnEveryReplica() = runBlocking {
        val gid = groupWith(GroupKind.GROUP, bob, carol, dave)
        assertEquals(GroupResult.OK, alice.engine.setAdmin(gid, "bob", true))
        assertEquals(GroupResult.OK, alice.engine.setAdmin(gid, "carol", true))
        pump()

        // Both admins edit the same epoch while disconnected, creating genuinely concurrent snapshots.
        net.down += setOf("alice", "bob", "carol", "dave")
        assertEquals(GroupResult.OK, bob.engine.rename(gid, "Bob's edit"))
        assertEquals(GroupResult.OK, carol.engine.rename(gid, "Carol's edit"))
        net.down.clear()
        pump()

        val names = listOf(alice, bob, carol, dave).map { it.store.group(gid)!!.name }.toSet()
        assertEquals(1, names.size)
        assertTrue(names.single() == "Bob's edit" || names.single() == "Carol's edit")
        val epochs = listOf(alice, bob, carol, dave).map { it.store.group(gid)!!.epoch }.toSet()
        assertEquals(setOf(4L), epochs)
    }

    @Test fun codecRoundTripAndMalformedInput() {
        val gid = UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()
        val sync = Envelope.GroupSync(id, "a", "b", gid, "Name", GroupKind.CHANNEL, 7, "a",
            listOf(GroupMemberRef("a", GroupRole.OWNER), GroupMemberRef("b", GroupRole.MEMBER)))
        val back = EnvelopeCodec.decode(EnvelopeCodec.encode(sync)) as Envelope.GroupSync
        assertEquals(GroupKind.CHANNEL, back.kind)
        assertEquals(7L, back.epoch)
        assertEquals(listOf("a", "b"), back.members.map { it.id })
        val chat = EnvelopeCodec.decode(EnvelopeCodec.encode(Envelope.GroupChat(id, "a", "b", gid, 5L, "hi"))) as Envelope.GroupChat
        assertEquals("hi", chat.body)
        assertEquals(gid, chat.groupId)
        assertNotNull(EnvelopeCodec.decode(EnvelopeCodec.encode(Envelope.GroupLeave(id, "a", "b", gid))))
        val bytes = EnvelopeCodec.encode(sync)
        for (cut in 1 until bytes.size) assertNull(EnvelopeCodec.decode(bytes.copyOf(cut)))
        assertNull(EnvelopeCodec.decode(bytes + byteArrayOf(0)))
    }
}
