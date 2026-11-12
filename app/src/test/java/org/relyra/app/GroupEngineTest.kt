package org.relyra.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
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
    val attachments = LinkedHashMap<String, GroupAttachmentRecord>()
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
    override suspend fun insertOutgoingGroupAttachment(m: GroupMessage, attachment: GroupAttachmentRecord, copies: List<OutboxRow>) {
        messages[m.messageId] = m
        attachments[attachment.messageId] = attachment
        copies.forEach { outbox[it.id] = it }
    }
    override suspend fun recordIncomingGroupAttachment(m: GroupMessage, attachment: GroupAttachmentRecord): Boolean {
        if (!processed.add(m.messageId)) return false
        messages[m.messageId] = m
        attachments[attachment.messageId] = attachment
        return true
    }
    override suspend fun groupAttachment(messageId: String) = attachments[messageId]
    override suspend fun updateGroupAttachment(attachment: GroupAttachmentRecord) { attachments[attachment.messageId] = attachment }
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
    override suspend fun markMessagesRead(groupId: String): List<GroupMessage> {
        val unread = messages.values.filter { it.groupId == groupId && !it.outgoing && !it.readLocally }
        unread.forEach { messages[it.messageId] = it.copy(readLocally = true) }
        return unread
    }
    override suspend fun markReadReceipt(copyId: String, groupId: String, memberId: String, readAt: Long): Boolean {
        val row = outbox[copyId] ?: return false
        if (row.groupId != groupId || row.memberId != memberId || row.baseMessageId == null) return false
        outbox[copyId] = row.copy(acked = true, readAt = row.readAt ?: readAt)
        return true
    }
    override suspend fun recordControlMessage(id: String, receivedAt: Long): Boolean = processed.add(id)
    override suspend fun hasControlMessage(id: String): Boolean = id in processed

    override suspend fun deleteGroup(groupId: String) {
        groups.remove(groupId); memberMap.remove(groupId)
        messages.values.removeAll { it.groupId == groupId }
        attachments.values.removeAll { it.groupId == groupId }
        outbox.values.removeAll { it.groupId == groupId }
    }
}

/** A tiny in-memory network: delivers an encoded envelope straight to the addressee's engine. */
internal class GroupNet {
    val nodes = HashMap<String, GNode>()
    val down = HashSet<String>()
    val log = mutableListOf<Pair<String, ByteArray>>()
}

internal class GNode(
    val id: String,
    val net: GroupNet,
    val clock: () -> Long,
    mlsProvider: GroupMlsProvider? = null,
) {
    val store = FakeGroupStore()
    val engine = GroupEngine(
        store = store,
        peers = Peers(mapOf("alice" to PeerStatus.Known, "bob" to PeerStatus.Known, "carol" to PeerStatus.Known, "dave" to PeerStatus.Known)),
        ownId = { id },
        mls = mlsProvider,
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
    private val alice = GNode("alice", net, { now })
    private val bob = GNode("bob", net, { now })
    private val carol = GNode("carol", net, { now })
    private val dave = GNode("dave", net, { now })

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

    @Test fun groupReadReceiptsAreBoundToTheExactMemberCopyAndRespectPreference() = runBlocking {
        val gid = groupWith(GroupKind.GROUP, bob, carol)
        alice.engine.send(gid, "read me")
        pump()

        val bobCopy = alice.store.outbox.values.single { it.baseMessageId != null && it.memberId == "bob" }
        val carolCopy = alice.store.outbox.values.single { it.baseMessageId != null && it.memberId == "carol" }

        val forged = Envelope.GroupReadReceipt(
            UUID.randomUUID().toString(), "carol", "alice", gid, bobCopy.id, now + 1
        )
        alice.engine.onEnvelope(forged)
        assertNull(alice.store.outbox[bobCopy.id]!!.readAt)

        bob.engine.markRead(gid, sendReceipts = true)
        pump()
        assertNotNull(alice.store.outbox[bobCopy.id]!!.readAt)
        assertNull(alice.store.outbox[carolCopy.id]!!.readAt)

        // Local read state is saved, but no receipt leaves the device when the preference is off.
        carol.engine.markRead(gid, sendReceipts = false)
        pump()
        assertTrue(carol.store.messages.values.all { it.readLocally })
        assertNull(alice.store.outbox[carolCopy.id]!!.readAt)
    }

    @Test fun readReceiptCodecRoundTripsAndRejectsTruncatedFrames() {
        val receipt = Envelope.GroupReadReceipt(
            UUID.randomUUID().toString(), "alice", "bob", UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), 1234L
        )
        val encoded = EnvelopeCodec.encode(receipt)
        val decoded = EnvelopeCodec.decode(encoded) as Envelope.GroupReadReceipt
        assertEquals(receipt.groupId, decoded.groupId)
        assertEquals(receipt.originalMessageId, decoded.originalMessageId)
        assertEquals(1234L, decoded.readAt)
        assertNull(EnvelopeCodec.decode(encoded.copyOf(encoded.size - 1)))
    }

    @Test fun groupMlsEnvelopeAndV2MembershipSnapshotRoundTrip() {
        val gid = UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()
        val memberId = UUID.randomUUID().toString()
        val sync = Envelope.GroupSync(
            id, "owner", "member", gid, "MLS team", GroupKind.GROUP, 3, "owner",
            listOf(GroupMemberRef("owner", GroupRole.OWNER, true), GroupMemberRef("member", GroupRole.MEMBER, false)),
            mlsEnabled = true, mlsEpoch = 2,
        )
        val back = EnvelopeCodec.decode(EnvelopeCodec.encode(sync)) as Envelope.GroupSync
        assertTrue(back.mlsEnabled)
        assertEquals(2L, back.mlsEpoch)
        assertTrue(back.members.first().mlsJoined)
        assertFalse(back.members.last().mlsJoined)

        val packet = Envelope.GroupMls(id, "owner", "member", gid, GroupMlsKind.WELCOME, byteArrayOf(1, 2, 3))
        val decoded = EnvelopeCodec.decode(EnvelopeCodec.encode(packet)) as Envelope.GroupMls
        assertEquals(GroupMlsKind.WELCOME, decoded.kind)
        assertTrue(decoded.payload.contentEquals(byteArrayOf(1, 2, 3)))
        assertNull(EnvelopeCodec.decode(EnvelopeCodec.encode(packet).copyOf(EnvelopeCodec.encode(packet).size - 1)))
    }

    @Test fun mlsGroupTextPayloadCodecRejectsMalformedFrames() {
        val id = UUID.randomUUID().toString()
        val encoded = GroupPayloadCodec.encodeText(id, 1234L, "مرحبا MLS")
        val decoded = GroupPayloadCodec.decodeText(encoded)!!
        assertEquals(id, decoded.messageId)
        assertEquals(1234L, decoded.sentAt)
        assertEquals("مرحبا MLS", decoded.body)
        assertNull(GroupPayloadCodec.decodeText(encoded.copyOf(encoded.size - 1)))
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
    @Test fun mlsMembershipChangesHaveOneOwnerWriterAndAdminsCannotAdvanceEpoch() = runBlocking {
        val gid = UUID.randomUUID().toString()
        val store = FakeGroupStore()
        val members = listOf(
            GroupMemberRef("alice", GroupRole.OWNER, mlsJoined = true),
            GroupMemberRef("bob", GroupRole.ADMIN, mlsJoined = true),
            GroupMemberRef("carol", GroupRole.MEMBER, mlsJoined = true),
        )
        store.putGroup(
            GroupRecord(gid, "MLS team", GroupKind.GROUP, "alice", 7, GroupState.ACTIVE,
                now, "alice", mlsEnabled = true, mlsEpoch = 4),
            members,
        )
        val admin = GroupEngine(
            store = store,
            peers = Peers(mapOf("alice" to PeerStatus.Known, "bob" to PeerStatus.Known,
                "carol" to PeerStatus.Known, "dave" to PeerStatus.Known)),
            ownId = { "bob" },
            transmit = { _, _ -> SendOutcome.Unavailable },
        )

        assertEquals(GroupResult.PROPOSAL_QUEUED, admin.addMembers(gid, listOf("dave")))
        assertEquals(GroupResult.PROPOSAL_QUEUED, admin.removeMember(gid, "carol"))
        assertEquals(GroupResult.NOT_ALLOWED, admin.setAdmin(gid, "carol", true))
        assertEquals(setOf("alice", "bob", "carol"), store.members(gid).map { it.id }.toSet())
        assertEquals(7L, store.group(gid)!!.epoch)
        assertEquals(4L, store.group(gid)!!.mlsEpoch)

        // A metadata-only change is safe for an admin; it advances only the snapshot epoch,
        // never the cryptographic MLS epoch.
        assertEquals(GroupResult.OK, admin.rename(gid, "MLS team renamed"))
        assertEquals(8L, store.group(gid)!!.epoch)
        assertEquals(4L, store.group(gid)!!.mlsEpoch)
    }

    @Test fun concurrentMlsAdminMembershipProposalsAreSerializedByOwner() = runBlocking {
        val gid = UUID.randomUUID().toString()
        val members = listOf(
            GroupMemberRef("alice", GroupRole.OWNER, mlsJoined = true),
            GroupMemberRef("bob", GroupRole.ADMIN, mlsJoined = true),
            GroupMemberRef("carol", GroupRole.ADMIN, mlsJoined = true),
        )
        val record = GroupRecord(gid, "MLS team", GroupKind.GROUP, "alice", 7, GroupState.ACTIVE,
            now, "alice", mlsEnabled = true, mlsEpoch = 4)
        listOf(alice, bob, carol).forEach { it.store.putGroup(record, members) }

        // Both admins create proposals against the same group and MLS epochs while the owner is offline.
        net.down += "alice"
        assertEquals(GroupResult.PROPOSAL_QUEUED, bob.engine.addMembers(gid, listOf("dave")))
        assertEquals(GroupResult.PROPOSAL_QUEUED, carol.engine.addMembers(gid, listOf("dave")))
        net.down.clear()
        pump()

        val updated = alice.store.group(gid)!!
        assertEquals(8L, updated.epoch)
        assertEquals(4L, updated.mlsEpoch)
        assertEquals(1, alice.store.members(gid).count { it.id == "dave" })
        assertEquals(GroupRole.MEMBER, alice.store.members(gid).first { it.id == "dave" }.role)
        assertEquals(GroupState.INVITED, dave.store.group(gid)?.state)

        val acceptedProposal = bob.store.outbox.values.single { it.memberId == "alice" }
        val acceptedEnvelope = EnvelopeCodec.decode(acceptedProposal.payload) as Envelope.GroupMls
        alice.engine.onEnvelope(acceptedEnvelope) // duplicate delivery must not apply the proposal twice
        val staleProposal = carol.store.outbox.values.single { it.memberId == "alice" }
        assertTrue(alice.store.hasControlMessage(staleProposal.id)) // stale concurrent request was rejected and consumed
        assertEquals(8L, alice.store.group(gid)!!.epoch)
        assertEquals(1, alice.store.members(gid).count { it.id == "dave" })
        assertTrue(bob.store.outbox.values.all { it.acked })
        assertTrue(carol.store.outbox.values.all { it.acked })
    }

    @Test fun mlsAdminRemovalCreatesNativeProposalWithoutAdvancingTheEpoch() = runBlocking {
        val gid = UUID.randomUUID().toString()
        val members = listOf(
            GroupMemberRef("alice", GroupRole.OWNER, mlsJoined = true),
            GroupMemberRef("bob", GroupRole.ADMIN, mlsJoined = true),
            GroupMemberRef("carol", GroupRole.MEMBER, mlsJoined = true),
        )
        val record = GroupRecord(gid, "MLS team", GroupKind.GROUP, "alice", 7, GroupState.ACTIVE,
            now, "alice", mlsEnabled = true, mlsEpoch = 4)
        val fakeMls = object : GroupMlsProvider {
            override suspend fun proposeRemoveMember(groupId: String, relyraPublicId: String) = relyraPublicId.toByteArray()
            override suspend fun createGroup(groupId: String, relyraPublicId: String): Long = error("unused")
            override suspend fun generateKeyPackage(relyraPublicId: String): ByteArray = error("unused")
            override suspend fun keyPackageIdentity(keyPackage: ByteArray): ByteArray = error("unused")
            override suspend fun addMembers(groupId: String, keyPackages: List<ByteArray>): MlsCommitBundle = error("unused")
            override suspend fun joinGroup(groupId: String, welcome: ByteArray, relyraPublicId: String): Long = error("unused")
            override suspend fun encrypt(groupId: String, plaintext: ByteArray): ByteArray = error("unused")
            override suspend fun processMessage(message: ByteArray): MlsProcessedMessage = error("unused")
            override suspend fun removeMember(groupId: String, relyraPublicId: String): MlsCommitBundle = error("unused")
            override suspend fun proposeAddMember(groupId: String, keyPackage: ByteArray): ByteArray = error("unused")
            override suspend fun processProposal(message: ByteArray, messageId: String, expectedSenderPublicId: String, allowedTargetPublicIds: Set<String>, allowAdd: Boolean, allowRemove: Boolean): Long = error("unused")
            override suspend fun commitPendingProposals(groupId: String, controlMessageId: String?): MlsPreparedCommit = error("unused")
            override suspend fun pendingCommitInfo(groupId: String): MlsPreparedCommit? = null
            override suspend fun mergePendingCommit(groupId: String): Long = error("unused")
            override suspend fun pendingProposalCount(groupId: String): Int = 0
            override suspend fun epoch(groupId: String): Long = 4
            override suspend fun members(groupId: String): List<String> = error("unused")
            override suspend fun close() = Unit
        }
        val bobMls = GNode("bob", net, { now }, fakeMls)
        listOf(alice, bobMls, carol).forEach { it.store.putGroup(record, members) }

        assertEquals(GroupResult.PROPOSAL_QUEUED, bobMls.engine.removeMember(gid, "carol"))
        val row = bobMls.store.outbox.values.single()
        val proposal = EnvelopeCodec.decode(row.payload) as Envelope.GroupMls
        assertEquals(GroupMlsKind.PROPOSAL, proposal.kind)
        assertEquals("carol", proposal.payload.toString(Charsets.UTF_8))
        assertEquals(4L, bobMls.store.group(gid)!!.mlsEpoch)
        assertEquals(4L, alice.store.group(gid)!!.mlsEpoch)
        assertTrue(alice.store.members(gid).any { it.id == "carol" })
    }

    @Test fun membershipProposalCodecRejectsMalformedOrTrailingBytes() {
        val proposal = GroupChangeProposal(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), 3, 2, "add", listOf("alice", "bob")
        )
        val encoded = GroupChangeProposalCodec.encode(proposal)
        assertEquals(proposal, GroupChangeProposalCodec.decode(encoded))
        assertNull(GroupChangeProposalCodec.decode(encoded + byteArrayOf(0)))
        assertNull(GroupChangeProposalCodec.decode(byteArrayOf(1, 2, 3)))
    }

    @Test fun removedMlsMemberAcceptsOwnerSnapshotWithoutTheNewEpochCommit() = runBlocking {
        val gid = UUID.randomUUID().toString()
        val store = FakeGroupStore()
        store.putGroup(
            GroupRecord(gid, "MLS team", GroupKind.GROUP, "alice", 3, GroupState.ACTIVE,
                now, "alice", mlsEnabled = true, mlsEpoch = 9),
            listOf(
                GroupMemberRef("alice", GroupRole.OWNER, mlsJoined = true),
                GroupMemberRef("bob", GroupRole.ADMIN, mlsJoined = true),
                GroupMemberRef("carol", GroupRole.MEMBER, mlsJoined = true),
            ),
        )
        val removed = GroupEngine(
            store = store,
            peers = Peers(mapOf("alice" to PeerStatus.Known, "bob" to PeerStatus.Known, "carol" to PeerStatus.Known)),
            ownId = { "carol" },
            transmit = { _, _ -> SendOutcome.Unavailable },
        )
        removed.onEnvelope(Envelope.GroupSync(
            UUID.randomUUID().toString(), "alice", "carol", gid, "MLS team", GroupKind.GROUP,
            4, "alice", listOf(
                GroupMemberRef("alice", GroupRole.OWNER, mlsJoined = true),
                GroupMemberRef("bob", GroupRole.ADMIN, mlsJoined = true),
            ), mlsEnabled = true, mlsEpoch = 10,
        ))
        assertEquals(GroupState.REMOVED, store.group(gid)!!.state)
        assertFalse(store.members(gid).any { it.id == "carol" })
    }

    @Test fun mlsCommitFromAdminIsIgnoredAtReceiver() = runBlocking {
        val gid = UUID.randomUUID().toString()
        val store = FakeGroupStore()
        store.putGroup(
            GroupRecord(gid, "MLS team", GroupKind.GROUP, "alice", 3, GroupState.ACTIVE,
                now, "alice", mlsEnabled = true, mlsEpoch = 9),
            listOf(
                GroupMemberRef("alice", GroupRole.OWNER, mlsJoined = true),
                GroupMemberRef("bob", GroupRole.ADMIN, mlsJoined = true),
                GroupMemberRef("carol", GroupRole.MEMBER, mlsJoined = true),
            ),
        )
        val receiver = GroupEngine(
            store = store,
            peers = Peers(mapOf("alice" to PeerStatus.Known, "bob" to PeerStatus.Known, "carol" to PeerStatus.Known)),
            ownId = { "carol" },
            transmit = { _, _ -> SendOutcome.Unavailable },
        )
        val adminCommitId = UUID.randomUUID().toString()
        receiver.onEnvelope(
            Envelope.GroupMls(adminCommitId, "bob", "carol", gid,
                GroupMlsKind.COMMIT, byteArrayOf(1, 2, 3))
        )
        assertEquals(9L, store.group(gid)!!.mlsEpoch)
        assertFalse(store.hasControlMessage(adminCommitId))
    }

    @Test fun backupBarrierBlocksRoomGroupMutationsUntilSnapshotCompletes() = runBlocking {
    kotlinx.coroutines.coroutineScope {
        val gid = groupWith(GroupKind.GROUP, bob)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val snapshot = async {
            alice.engine.withBackupBarrier {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val mutation = kotlinx.coroutines.async { alice.engine.rename(gid, "after snapshot") }
        yield()
        assertFalse("group mutation must wait for the cross-store snapshot", mutation.isCompleted)

        release.complete(Unit)
        snapshot.await()
        assertEquals(GroupResult.OK, mutation.await())
        assertEquals("after snapshot", alice.store.group(gid)?.name)
    }
    }

}
