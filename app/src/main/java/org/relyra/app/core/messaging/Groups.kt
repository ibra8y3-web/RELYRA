package org.relyra.app.core.messaging

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

data class GroupRecord(
    val groupId: String,
    val name: String,
    val kind: GroupKind,
    val owner: String,
    /** Grows with every membership/name change. A snapshot is only accepted when its epoch is higher. */
    val epoch: Long,
    val state: GroupState,
    val createdAt: Long,
    val invitedBy: String,
    val mlsEnabled: Boolean = false,
    val mlsEpoch: Long = 0,
)

data class GroupMessage(
    val messageId: String,
    val groupId: String,
    val senderId: String,
    val outgoing: Boolean,
    val body: String,
    val createdAt: Long,
    val readLocally: Boolean,
)

/** One packet that must reach one member (a message copy, a membership snapshot or a leave notice). */
data class OutboxRow(
    val id: String,
    val groupId: String,
    val memberId: String,
    /** The local group message this copy belongs to; null for control packets. */
    val baseMessageId: String?,
    val payload: ByteArray,
    val createdAt: Long,
    val attempts: Int,
    val nextAttemptAt: Long,
    val acked: Boolean,
    val failed: Boolean,
    val readAt: Long? = null,
)

interface GroupStore {
    suspend fun group(id: String): GroupRecord?
    suspend fun members(groupId: String): List<GroupMemberRef>
    /** Replaces the group and its whole member list in one transaction. */
    suspend fun putGroup(g: GroupRecord, members: List<GroupMemberRef>)
    suspend fun setState(groupId: String, state: GroupState)
    suspend fun insertOutgoingMessage(m: GroupMessage, copies: List<OutboxRow>)
    /** Atomically remembers the copy's ID and stores the message. False if that ID was already processed. */
    suspend fun recordIncomingMessage(m: GroupMessage): Boolean
    suspend fun enqueue(rows: List<OutboxRow>)
    suspend fun dueOutbox(now: Long, limit: Int): List<OutboxRow>
    suspend fun updateOutbox(row: OutboxRow)
    /** True when [id] is one of our packets addressed to [memberId]. */
    suspend fun markAcked(id: String, memberId: String): Boolean
    /** Atomically returns unread inbound messages and marks them locally read. */
    suspend fun markMessagesRead(groupId: String): List<GroupMessage>
    /** Accepts a read receipt only for the exact per-member copy stored in this outbox. */
    suspend fun markReadReceipt(copyId: String, groupId: String, memberId: String, readAt: Long): Boolean
    /** Atomically records a one-time group control packet (MLS handshake/commit). */
    suspend fun recordControlMessage(id: String, receivedAt: Long): Boolean
    suspend fun deleteGroup(groupId: String)
}

/** What [DeliveryEngine] hands over: everything group related that arrives through the encrypted 1:1 channel. */
interface GroupInbound {
    suspend fun onEnvelope(env: Envelope)
    suspend fun onAck(ack: Envelope.Ack): Boolean
    suspend fun processOutbox(): Int
}

enum class GroupResult {
    OK, NO_IDENTITY, NOT_FOUND, NOT_ALLOWED, NOT_ACTIVE, INVALID, EMPTY, TOO_LONG, NO_RECIPIENTS, UNKNOWN_CONTACT, MLS_UNAVAILABLE,
}

class GroupOutcome(val result: GroupResult, val groupId: String? = null)

/**
 * Groups and broadcast channels.
 *
 * Encryption: NOT MLS. Every group packet is sent to each member separately through the existing one-to-one
 * end-to-end channel (Double Ratchet), exactly like the first Signal groups. A removed member simply stops being
 * an addressee, so cryptographic removal needs no key rotation. Cost: sending grows with the member count.
 * Every member must be one of your contacts (the keys come from contact cards).
 *
 * Authority is decided from each receiver's OWN copy of the group, never from what a packet claims:
 *  - an unknown group is only ever an invitation that the user must accept;
 *  - a snapshot is applied only if its sender is OWNER/ADMIN locally and its epoch is higher;
 *  - admins can add/remove plain members and rename, but cannot touch the owner/admin set;
 *  - in a channel only OWNER/ADMIN may post.
 * Concurrent admin edits at the same epoch are resolved deterministically by the SHA-256 fingerprint of the canonical snapshot.
 */
class GroupEngine(
    private val store: GroupStore,
    private val peers: PeerDirectory,
    private val ownId: suspend () -> String?,
    private val transmit: suspend (peer: String, plaintext: ByteArray) -> SendOutcome,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val incoming: IncomingListener? = null,
    private val mls: GroupMlsProvider? = null,
) : GroupInbound {
    private val lock = Mutex()

    // ---- creating and posting ------------------------------------------------------------------------------

    suspend fun create(name: String, kind: GroupKind, memberIds: List<String>): GroupOutcome = lock.withLock {
        val me = ownId() ?: return@withLock GroupOutcome(GroupResult.NO_IDENTITY)
        val clean = GroupLimits.sanitizeName(name)
        if (clean.isBlank()) return@withLock GroupOutcome(GroupResult.INVALID)
        val ids = memberIds.filter { it != me }.distinct()
        if (ids.size + 1 > GroupLimits.MAX_MEMBERS) return@withLock GroupOutcome(GroupResult.INVALID)
        if (ids.any { peers.status(it) != PeerStatus.Known }) return@withLock GroupOutcome(GroupResult.UNKNOWN_CONTACT)
        val gid = newId()
        val mlsEpoch = mls?.createGroup(gid, me)
        val mlsEnabled = mls != null
        val members = listOf(GroupMemberRef(me, GroupRole.OWNER, mlsJoined = mlsEnabled)) +
            ids.map { GroupMemberRef(it, GroupRole.MEMBER, mlsJoined = false) }
        store.putGroup(
            GroupRecord(gid, clean, kind, me, 1, GroupState.ACTIVE, clock(), me, mlsEnabled, mlsEpoch ?: 0),
            members,
        )
        broadcastSnapshot(gid, emptySet())
        GroupOutcome(GroupResult.OK, gid)
    }

    suspend fun send(groupId: String, body: String): GroupResult = lock.withLock {
        val me = ownId() ?: return@withLock GroupResult.NO_IDENTITY
        val g = store.group(groupId) ?: return@withLock GroupResult.NOT_FOUND
        if (g.state != GroupState.ACTIVE) return@withLock GroupResult.NOT_ACTIVE
        if (body.isBlank()) return@withLock GroupResult.EMPTY
        if (body.length > EnvelopeCodec.MAX_BODY) return@withLock GroupResult.TOO_LONG
        val members = store.members(groupId)
        val mine = members.find { it.id == me } ?: return@withLock GroupResult.NOT_ALLOWED
        if (g.kind == GroupKind.CHANNEL && mine.role == GroupRole.MEMBER) return@withLock GroupResult.NOT_ALLOWED
        val now = clock().coerceAtLeast(1L)
        val baseId = newId()
        val copies = if (g.mlsEnabled) {
            val engine = mls ?: return@withLock GroupResult.MLS_UNAVAILABLE
            val recipients = members.filter { it.id != me && it.mlsJoined && peers.status(it.id) == PeerStatus.Known }
            if (recipients.isEmpty()) return@withLock GroupResult.NO_RECIPIENTS
            val appFrame = GroupPayloadCodec.encodeText(baseId, now, body)
            val ciphertext = engine.encrypt(groupId, appFrame)
            recipients.map { member ->
                val copyId = newId()
                OutboxRow(
                    copyId, groupId, member.id, baseId,
                    EnvelopeCodec.encode(Envelope.GroupMls(copyId, me, member.id, groupId, GroupMlsKind.APPLICATION, ciphertext)),
                    now, 0, now, acked = false, failed = false,
                )
            }
        } else {
            val recipients = members.filter { it.id != me && peers.status(it.id) == PeerStatus.Known }
            if (recipients.isEmpty() && members.size > 1) return@withLock GroupResult.NO_RECIPIENTS
            recipients.map { member ->
                val copyId = newId()
                OutboxRow(copyId, groupId, member.id, baseId,
                    EnvelopeCodec.encode(Envelope.GroupChat(copyId, me, member.id, groupId, now, body)),
                    now, 0, now, acked = false, failed = false)
            }
        }
        store.insertOutgoingMessage(GroupMessage(baseId, groupId, me, true, body, now, true), copies)
        GroupResult.OK
    }

    // ---- management ----------------------------------------------------------------------------------------

    suspend fun addMembers(groupId: String, ids: List<String>): GroupResult = manage(groupId) { g, members, me ->
        if (!isStaff(roleOf(members, me))) return@manage Verdict.Deny(GroupResult.NOT_ALLOWED)
        val fresh = ids.distinct().filter { id -> members.none { it.id == id } }
        if (fresh.isEmpty()) return@manage Verdict.Deny(GroupResult.INVALID)
        if (members.size + fresh.size > GroupLimits.MAX_MEMBERS) return@manage Verdict.Deny(GroupResult.INVALID)
        if (fresh.any { peers.status(it) != PeerStatus.Known }) return@manage Verdict.Deny(GroupResult.UNKNOWN_CONTACT)
        Verdict.Apply(g.name, g.owner, members + fresh.map { GroupMemberRef(it, GroupRole.MEMBER) }, emptySet())
    }

    suspend fun removeMember(groupId: String, memberId: String): GroupResult = manage(groupId) { g, members, me ->
        val mine = roleOf(members, me)
        val target = roleOf(members, memberId) ?: return@manage Verdict.Deny(GroupResult.NOT_FOUND)
        val allowed = memberId != me && target != GroupRole.OWNER &&
            (mine == GroupRole.OWNER || (mine == GroupRole.ADMIN && target == GroupRole.MEMBER))
        if (!allowed) return@manage Verdict.Deny(GroupResult.NOT_ALLOWED)
        Verdict.Apply(g.name, g.owner, members.filter { it.id != memberId }, setOf(memberId))
    }

    suspend fun setAdmin(groupId: String, memberId: String, admin: Boolean): GroupResult = manage(groupId) { g, members, me ->
        if (roleOf(members, me) != GroupRole.OWNER) return@manage Verdict.Deny(GroupResult.NOT_ALLOWED)
        val target = roleOf(members, memberId) ?: return@manage Verdict.Deny(GroupResult.NOT_FOUND)
        if (target == GroupRole.OWNER) return@manage Verdict.Deny(GroupResult.NOT_ALLOWED)
        val role = if (admin) GroupRole.ADMIN else GroupRole.MEMBER
        Verdict.Apply(g.name, g.owner, members.map { if (it.id == memberId) GroupMemberRef(it.id, role) else it }, emptySet())
    }

    suspend fun rename(groupId: String, name: String): GroupResult = manage(groupId) { g, members, me ->
        if (!isStaff(roleOf(members, me))) return@manage Verdict.Deny(GroupResult.NOT_ALLOWED)
        val clean = GroupLimits.sanitizeName(name)
        if (clean.isBlank() || clean == g.name) return@manage Verdict.Deny(GroupResult.INVALID)
        Verdict.Apply(clean, g.owner, members, emptySet())
    }

    /** The old owner becomes an admin; the new owner must already be a member. */
    suspend fun transferOwnership(groupId: String, newOwner: String): GroupResult = manage(groupId) { g, members, me ->
        if (roleOf(members, me) != GroupRole.OWNER || newOwner == me) return@manage Verdict.Deny(GroupResult.NOT_ALLOWED)
        if (roleOf(members, newOwner) == null) return@manage Verdict.Deny(GroupResult.NOT_FOUND)
        val updated = members.map {
            when (it.id) {
                me -> it.copy(role = GroupRole.ADMIN)
                newOwner -> it.copy(role = GroupRole.OWNER)
                else -> it
            }
        }
        Verdict.Apply(g.name, newOwner, updated, emptySet())
    }

    private sealed interface Verdict {
        class Deny(val result: GroupResult) : Verdict
        class Apply(val name: String, val owner: String, val members: List<GroupMemberRef>, val removed: Set<String>) : Verdict
    }

    private suspend fun manage(
        groupId: String,
        decide: suspend (GroupRecord, List<GroupMemberRef>, String) -> Verdict,
    ): GroupResult = lock.withLock {
        val me = ownId() ?: return@withLock GroupResult.NO_IDENTITY
        val g = store.group(groupId) ?: return@withLock GroupResult.NOT_FOUND
        if (g.state != GroupState.ACTIVE) return@withLock GroupResult.NOT_ACTIVE
        when (val v = decide(g, store.members(groupId), me)) {
            is Verdict.Deny -> v.result
            is Verdict.Apply -> {
                var nextMlsEpoch = g.mlsEpoch
                if (g.mlsEnabled && v.removed.isNotEmpty()) {
                    val engine = mls ?: return@withLock GroupResult.MLS_UNAVAILABLE
                    var epochMembers = store.members(groupId)
                    for (removedId in v.removed) {
                        val target = epochMembers.firstOrNull { it.id == removedId && it.mlsJoined } ?: continue
                        val commit = engine.removeMember(groupId, target.id)
                        val recipients = epochMembers.filter { it.mlsJoined && it.id != me && peers.status(it.id) == PeerStatus.Known }
                        val now = clock().coerceAtLeast(1L)
                        store.enqueue(recipients.map { recipient ->
                            val id = newId()
                            OutboxRow(
                                id, groupId, recipient.id, null,
                                EnvelopeCodec.encode(Envelope.GroupMls(id, me, recipient.id, groupId, GroupMlsKind.COMMIT, commit.commit)),
                                now, 0, now, acked = false, failed = false,
                            )
                        })
                        nextMlsEpoch = commit.epoch
                        epochMembers = epochMembers.map { if (it.id == target.id) it.copy(mlsJoined = false) else it }
                    }
                }
                store.putGroup(g.copy(name = v.name, owner = v.owner, epoch = g.epoch + 1, mlsEpoch = nextMlsEpoch), v.members)
                broadcastSnapshot(groupId, v.removed)
                GroupResult.OK
            }
        }
    }

    /**
     * Accepting an MLS invitation is a real handshake: send a signed KeyPackage,
     * wait for Welcome, install the epoch state, then acknowledge to the owner.
     */
    suspend fun accept(groupId: String): GroupResult = lock.withLock {
        val me = ownId() ?: return@withLock GroupResult.NO_IDENTITY
        val g = store.group(groupId) ?: return@withLock GroupResult.NOT_FOUND
        if (g.state != GroupState.INVITED) return@withLock GroupResult.NOT_ACTIVE
        if (g.mlsEnabled) {
            val engine = mls ?: return@withLock GroupResult.MLS_UNAVAILABLE
            val keyPackage = engine.generateKeyPackage(me)
            val id = newId()
            val now = clock().coerceAtLeast(1L)
            store.setState(groupId, GroupState.JOINING)
            store.enqueue(listOf(OutboxRow(
                id, groupId, g.owner, null,
                EnvelopeCodec.encode(Envelope.GroupMls(id, me, g.owner, groupId, GroupMlsKind.KEY_PACKAGE, keyPackage)),
                now, 0, now, acked = false, failed = false,
            )))
            return@withLock GroupResult.OK
        }
        store.setState(groupId, GroupState.ACTIVE)
        GroupResult.OK
    }

    /** Leaves an active group or declines an invitation. The owner is told; the owner must hand over first. */
    suspend fun leave(groupId: String): GroupResult = lock.withLock {
        val me = ownId() ?: return@withLock GroupResult.NO_IDENTITY
        val g = store.group(groupId) ?: return@withLock GroupResult.NOT_FOUND
        if (g.state != GroupState.ACTIVE && g.state != GroupState.INVITED && g.state != GroupState.JOINING) return@withLock GroupResult.NOT_ACTIVE
        if (g.owner == me) return@withLock GroupResult.NOT_ALLOWED
        val id = newId()
        val now = clock()
        store.setState(groupId, GroupState.LEFT)
        store.enqueue(listOf(OutboxRow(id, groupId, g.owner, null,
            EnvelopeCodec.encode(Envelope.GroupLeave(id, me, g.owner, groupId)), now, 0, now, acked = false, failed = false)))
        GroupResult.OK
    }

    /** Deletes the local copy (messages included) of a group this device is no longer part of. */
    suspend fun deleteLocal(groupId: String): GroupResult = lock.withLock {
        val g = store.group(groupId) ?: return@withLock GroupResult.NOT_FOUND
        if (g.state == GroupState.ACTIVE || g.state == GroupState.INVITED || g.state == GroupState.JOINING) return@withLock GroupResult.NOT_ALLOWED
        store.deleteGroup(groupId)
        GroupResult.OK
    }

    private fun roleOf(members: List<GroupMemberRef>, id: String): GroupRole? = members.find { it.id == id }?.role
    private fun isStaff(r: GroupRole?) = r == GroupRole.OWNER || r == GroupRole.ADMIN

    private suspend fun broadcastSnapshot(groupId: String, extraTargets: Set<String>) {
        val me = ownId() ?: return
        val g = store.group(groupId) ?: return
        val members = store.members(groupId)
        val now = clock()
        val rows = ((members.map { it.id } + extraTargets).toSet() - me).map { target ->
            val id = newId()
            OutboxRow(id, groupId, target, null,
                EnvelopeCodec.encode(Envelope.GroupSync(id, me, target, groupId, g.name, g.kind, g.epoch, g.owner, members, g.mlsEnabled, g.mlsEpoch)),
                now, 0, now, acked = false, failed = false)
        }
        store.enqueue(rows)
    }

    // ---- receiving -----------------------------------------------------------------------------------------

    override suspend fun onAck(ack: Envelope.Ack): Boolean = store.markAcked(ack.messageId, ack.from)

    override suspend fun onEnvelope(env: Envelope) {
        val me = ownId() ?: return
        lock.withLock {
            when (env) {
                is Envelope.GroupChat -> onChat(me, env)
                is Envelope.GroupSync -> onSync(me, env)
                is Envelope.GroupLeave -> onLeave(me, env)
                is Envelope.GroupReadReceipt -> onReadReceipt(me, env)
                is Envelope.GroupMls -> onMlsEnvelope(me, env)
                else -> Unit
            }
        }
    }

    private suspend fun ack(me: String, to: String, id: String) {
        transmit(to, EnvelopeCodec.encode(Envelope.Ack(id, me, to, AckKind.DELIVERED)))
    }

    /**
     * Marks unread group messages locally and queues one authenticated receipt per sender/copy.
     * Local read state is saved even when the user disabled outgoing read receipts.
     */
    suspend fun markRead(groupId: String, sendReceipts: Boolean) = lock.withLock {
        val me = ownId() ?: return@withLock
        val group = store.group(groupId) ?: return@withLock
        if (group.state != GroupState.ACTIVE) return@withLock
        val unread = store.markMessagesRead(groupId)
        if (!sendReceipts || unread.isEmpty()) return@withLock
        val members = store.members(groupId).map { it.id }.toSet()
        val now = clock().coerceAtLeast(1L)
        val rows = unread.mapNotNull { message ->
            val sender = message.senderId
            if (message.outgoing || sender == me || sender !in members || peers.status(sender) != PeerStatus.Known) {
                null
            } else {
                val id = newId()
                OutboxRow(
                    id, groupId, sender, null,
                    EnvelopeCodec.encode(Envelope.GroupReadReceipt(id, me, sender, groupId, message.messageId, now)),
                    now, 0, now, acked = false, failed = false,
                )
            }
        }
        if (rows.isNotEmpty()) store.enqueue(rows)
    }

    private suspend fun onReadReceipt(me: String, env: Envelope.GroupReadReceipt) {
        if (env.to != me || env.from == me) return
        val group = store.group(env.groupId) ?: return
        if (group.state != GroupState.ACTIVE) return
        // The database binds the original copy ID to its intended recipient and group.
        if (store.markReadReceipt(env.originalMessageId, env.groupId, env.from, env.readAt)) {
            ack(me, env.from, env.messageId)
        }
    }

    private suspend fun onChat(me: String, env: Envelope.GroupChat) {
        val g = store.group(env.groupId) ?: return
        if (g.state != GroupState.ACTIVE) return
        val members = store.members(g.groupId)
        val sender = members.find { it.id == env.from } ?: return
        if (members.none { it.id == me }) return
        if (g.kind == GroupKind.CHANNEL && sender.role == GroupRole.MEMBER) return
        // Save first, acknowledge second; a repeat of a known copy is only re-acknowledged.
        val fresh = store.recordIncomingMessage(GroupMessage(env.messageId, g.groupId, env.from, false, env.body, clock(), false))
        if (fresh) runCatching { incoming?.onMessage(g.groupId, true, env.from, env.body) }
        ack(me, env.from, env.messageId)
    }

    private fun validSnapshot(env: Envelope.GroupSync): Boolean {
        if (env.epoch < 1 || env.owner.isBlank() || env.owner.length > 100 || env.mlsEpoch < 0) return false
        if (GroupLimits.sanitizeName(env.name).isBlank() || env.members.size !in 1..GroupLimits.MAX_MEMBERS) return false
        if (env.members.any { it.id.isBlank() || it.id.length > 100 }) return false
        if (env.members.map { it.id }.toSet().size != env.members.size) return false
        val owners = env.members.filter { it.role == GroupRole.OWNER }
        return owners.size == 1 && owners[0].id == env.owner &&
            env.members.any { it.id == env.from && (it.role == GroupRole.OWNER || it.role == GroupRole.ADMIN) }
    }

    private fun snapshotFingerprint(name: String, kind: GroupKind, owner: String, members: List<GroupMemberRef>, mlsEnabled: Boolean, mlsEpoch: Long): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF(name)
            out.writeInt(kind.ordinal)
            out.writeUTF(owner)
            out.writeBoolean(mlsEnabled)
            out.writeLong(mlsEpoch)
            val ordered = members.sortedBy { it.id }
            out.writeInt(ordered.size)
            ordered.forEach { member ->
                out.writeUTF(member.id)
                out.writeInt(member.role.ordinal)
                out.writeBoolean(member.mlsJoined)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun onSync(me: String, env: Envelope.GroupSync) {
        if (!validSnapshot(env)) return
        val name = GroupLimits.sanitizeName(env.name)
        val local = store.group(env.groupId)
        val iAmIn = env.members.any { it.id == me }

        if (local == null) {
            // Only ever an invitation. The sender must at least claim to be staff, and the user must accept it.
            if (!iAmIn) { ack(me, env.from, env.messageId); return }
            val senderRole = env.members.find { it.id == env.from }?.role
            if (!isStaff(senderRole)) return
            store.putGroup(GroupRecord(env.groupId, name, env.kind, env.owner, env.epoch, GroupState.INVITED, clock(), env.from, env.mlsEnabled, env.mlsEpoch), env.members)
            runCatching { incoming?.onInvited(env.groupId, name, env.from) }
            ack(me, env.from, env.messageId)
            return
        }
        if (env.epoch < local.epoch || env.mlsEpoch < local.mlsEpoch) { ack(me, env.from, env.messageId); return }
        if (env.mlsEnabled != local.mlsEnabled) return

        val localMembers = store.members(env.groupId)
        val senderRole = roleOf(localMembers, env.from)
        if (!isStaff(senderRole) || env.kind != local.kind) return
        if (senderRole == GroupRole.ADMIN) {
            // Admins may only change plain members and the name: owner and admin set must stay identical.
            val before = localMembers.filter { it.role != GroupRole.MEMBER }.associate { it.id to it.role }
            val after = env.members.filter { it.role != GroupRole.MEMBER }.associate { it.id to it.role }
            if (before != after || env.owner != local.owner) return
        }
        if (env.epoch == local.epoch) {
            val incoming = snapshotFingerprint(name, env.kind, env.owner, env.members, env.mlsEnabled, env.mlsEpoch)
            val current = snapshotFingerprint(local.name, local.kind, local.owner, localMembers, local.mlsEnabled, local.mlsEpoch)
            // All replicas choose the same winner for concurrent edits based on identical canonical bytes.
            if (incoming <= current) { ack(me, env.from, env.messageId); return }
        }
        val state = when {
            !iAmIn -> if (local.state == GroupState.LEFT) GroupState.LEFT else GroupState.REMOVED
            local.state == GroupState.LEFT || local.state == GroupState.REMOVED -> GroupState.INVITED
            else -> local.state
        }
        store.putGroup(local.copy(name = name, owner = env.owner, epoch = env.epoch, state = state, mlsEpoch = env.mlsEpoch), env.members)
        ack(me, env.from, env.messageId)
    }

    private suspend fun onLeave(me: String, env: Envelope.GroupLeave) {
        val g = store.group(env.groupId) ?: run { ack(me, env.from, env.messageId); return }
        val members = store.members(g.groupId)
        val leaver = members.find { it.id == env.from }
        // Only the owner publishes the new membership, so that several admins never publish the same change twice.
        if (leaver != null && leaver.role != GroupRole.OWNER && roleOf(members, me) == GroupRole.OWNER && g.state == GroupState.ACTIVE) {
            var nextMlsEpoch = g.mlsEpoch
            if (g.mlsEnabled && leaver.mlsJoined) {
                val engine = mls ?: return
                val commit = engine.removeMember(g.groupId, env.from)
                val now = clock().coerceAtLeast(1L)
                val recipients = members.filter { it.mlsJoined && it.id != me && peers.status(it.id) == PeerStatus.Known }
                store.enqueue(recipients.map { recipient ->
                    val id = newId()
                    OutboxRow(
                        id, g.groupId, recipient.id, null,
                        EnvelopeCodec.encode(Envelope.GroupMls(id, me, recipient.id, g.groupId, GroupMlsKind.COMMIT, commit.commit)),
                        now, 0, now, acked = false, failed = false,
                    )
                })
                nextMlsEpoch = commit.epoch
            }
            store.putGroup(g.copy(epoch = g.epoch + 1, mlsEpoch = nextMlsEpoch), members.filter { it.id != env.from })
            broadcastSnapshot(g.groupId, setOf(env.from))
        }
        ack(me, env.from, env.messageId)
    }

    // ---- delivery ------------------------------------------------------------------------------------------

    override suspend fun processOutbox(): Int = lock.withLock {
        val now = clock()
        var sent = 0
        for (row in store.dueOutbox(now, 100)) {
            if (now - row.createdAt > DeliveryEngine.TTL_MS) {
                store.updateOutbox(row.copy(failed = true, nextAttemptAt = Long.MAX_VALUE))
                continue
            }
            val attempts = row.attempts + 1
            when (transmit(row.memberId, row.payload)) {
                SendOutcome.Accepted, SendOutcome.Relayed -> {
                    store.updateOutbox(row.copy(attempts = attempts, nextAttemptAt = now + DeliveryEngine.ackWait(attempts)))
                    sent++
                }
                SendOutcome.Unavailable ->
                    store.updateOutbox(row.copy(attempts = attempts, nextAttemptAt = now + DeliveryEngine.backoff(attempts)))
                is SendOutcome.Rejected ->
                    store.updateOutbox(row.copy(attempts = attempts, failed = true, nextAttemptAt = Long.MAX_VALUE))
            }
        }
        sent
    }
}
