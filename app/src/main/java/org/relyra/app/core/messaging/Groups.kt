package org.relyra.app.core.messaging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
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
    /** Stores the encrypted group-file descriptor, message and per-member ciphertext outbox atomically. */
    suspend fun insertOutgoingGroupAttachment(m: GroupMessage, attachment: GroupAttachmentRecord, copies: List<OutboxRow>)
    /** Atomically remembers the copy ID, group message and encrypted file descriptor. */
    suspend fun recordIncomingGroupAttachment(m: GroupMessage, attachment: GroupAttachmentRecord): Boolean
    suspend fun groupAttachment(messageId: String): GroupAttachmentRecord?
    suspend fun updateGroupAttachment(attachment: GroupAttachmentRecord)
    /** Atomically remembers the copy's ID and stores the message. False if that ID was already processed. */
    suspend fun recordIncomingMessage(m: GroupMessage): Boolean
    suspend fun enqueue(rows: List<OutboxRow>)
    /** Atomically advances the Room MLS epoch/member snapshot and persists its retryable Commit outbox. */
    suspend fun applyMlsCommit(
        group: GroupRecord,
        members: List<GroupMemberRef>,
        outbox: List<OutboxRow>,
        controlMessageId: String? = null,
        receivedAt: Long? = null,
    ) {
        putGroup(group, members)
        enqueue(outbox)
        if (controlMessageId != null && receivedAt != null) recordControlMessage(controlMessageId, receivedAt)
    }
    suspend fun dueOutbox(now: Long, limit: Int): List<OutboxRow>
    suspend fun updateOutbox(row: OutboxRow)
    suspend fun outbox(id: String): OutboxRow?
    /** True when [id] is one of our packets addressed to [memberId]. */
    suspend fun markAcked(id: String, memberId: String): Boolean
    /** Atomically returns unread inbound messages and marks them locally read. */
    suspend fun markMessagesRead(groupId: String): List<GroupMessage>
    /** Accepts a read receipt only for the exact per-member copy stored in this outbox. */
    suspend fun markReadReceipt(copyId: String, groupId: String, memberId: String, readAt: Long): Boolean
    /** Atomically records a one-time group control packet (MLS handshake/commit). */
    suspend fun recordControlMessage(id: String, receivedAt: Long): Boolean
    suspend fun hasControlMessage(id: String): Boolean
    suspend fun deleteGroup(groupId: String)
}

/** What [DeliveryEngine] hands over: everything group related that arrives through the encrypted 1:1 channel. */
interface GroupInbound {
    suspend fun onEnvelope(env: Envelope)
    suspend fun onAck(ack: Envelope.Ack): Boolean
    suspend fun processOutbox(): Int
}

enum class GroupResult {
    OK, NO_IDENTITY, NOT_FOUND, NOT_ALLOWED, NOT_ACTIVE, INVALID, EMPTY, TOO_LONG, NO_RECIPIENTS, UNKNOWN_CONTACT, MLS_UNAVAILABLE, PROPOSAL_QUEUED,
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
 *  - on MLS groups, admin membership changes are sent as authenticated proposals to the owner;
 *    the owner serializes them against the exact group and MLS epochs and is the only commit writer;
 *  - on legacy groups, admins can add/remove plain members and rename, but cannot touch the owner/admin set;
 *  - in a channel only OWNER/ADMIN may post.
 * Concurrent same-epoch metadata edits are resolved deterministically by the SHA-256 fingerprint of the canonical snapshot.
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
    val pendingMlsProposals = MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * Holds the same lock used by every group state transition while a cross-store
     * snapshot is taken. Backup callers must acquire this before the MLS native barrier.
     */
    suspend fun <T> withBackupBarrier(block: suspend () -> T): T = lock.withLock { block() }

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

    /** Encrypts the file descriptor with the MLS group epoch and fans out only the MLS ciphertext. */
    suspend fun sendAttachment(groupId: String, file: GroupAttachmentData): GroupResult = lock.withLock {
        val me = ownId() ?: return@withLock GroupResult.NO_IDENTITY
        val group = store.group(groupId) ?: return@withLock GroupResult.NOT_FOUND
        if (group.state != GroupState.ACTIVE) return@withLock GroupResult.NOT_ACTIVE
        if (!group.mlsEnabled) return@withLock GroupResult.MLS_UNAVAILABLE
        val engine = mls ?: return@withLock GroupResult.MLS_UNAVAILABLE
        val members = store.members(groupId)
        val mine = members.find { it.id == me } ?: return@withLock GroupResult.NOT_ALLOWED
        if (!mine.mlsJoined || (group.kind == GroupKind.CHANNEL && mine.role == GroupRole.MEMBER)) {
            return@withLock GroupResult.NOT_ALLOWED
        }
        val normalizedUrl = org.relyra.app.core.transfer.BlossomUrl.normalize(file.serverUrl, allowPrivate = false)
            ?: return@withLock GroupResult.INVALID
        if (file.name.isBlank() || file.name.length > AttachmentLimits.MAX_NAME ||
            file.mime.length > AttachmentLimits.MAX_MIME ||
            file.plainSha256.size != 32 || file.blobSha256.size != 32 || file.fileKey.size != 32 ||
            !AttachmentLimits.validSizes(file.size, file.blobSize)
        ) return@withLock GroupResult.INVALID

        val recipients = members.filter { it.id != me && it.mlsJoined && peers.status(it.id) == PeerStatus.Known }
        if (recipients.isEmpty()) return@withLock GroupResult.NO_RECIPIENTS
        val messageId = newId()
        val now = clock().coerceAtLeast(1L)
        val payload = GroupPayloadCodec.encodeFile(messageId, now, file.copy(serverUrl = normalizedUrl))
        val ciphertext = engine.encrypt(groupId, payload)
        val copies = recipients.map { recipient ->
            val copyId = newId()
            OutboxRow(
                copyId, groupId, recipient.id, messageId,
                EnvelopeCodec.encode(Envelope.GroupMls(copyId, me, recipient.id, groupId, GroupMlsKind.APPLICATION, ciphertext)),
                now, 0, now, acked = false, failed = false,
            )
        }
        val attachment = GroupAttachmentRecord(
            messageId, groupId, me, org.relyra.app.core.transfer.FileNames.sanitize(file.name), file.mime,
            file.size, file.blobSize, file.plainSha256, file.blobSha256, file.fileKey,
            normalizedUrl, null, GroupAttachmentPhase.UPLOADED, now, true,
        )
        store.insertOutgoingGroupAttachment(
            GroupMessage(messageId, groupId, me, true, attachment.name, now, true),
            attachment,
            copies,
        )
        GroupResult.OK
    }

    // ---- management ----------------------------------------------------------------------------------------

    suspend fun addMembers(groupId: String, ids: List<String>): GroupResult = manage(groupId) { g, members, me ->
        if (!isStaff(roleOf(members, me))) return@manage Verdict.Deny(GroupResult.NOT_ALLOWED)
        val fresh = ids.distinct().filter { id -> members.none { it.id == id } }
        if (fresh.isEmpty()) return@manage Verdict.Deny(GroupResult.INVALID)
        if (members.size + fresh.size > GroupLimits.MAX_MEMBERS) return@manage Verdict.Deny(GroupResult.INVALID)
        if (fresh.any { peers.status(it) != PeerStatus.Known }) return@manage Verdict.Deny(GroupResult.UNKNOWN_CONTACT)
        if (g.mlsEnabled && roleOf(members, me) == GroupRole.ADMIN) {
            Verdict.Propose("add", fresh)
        } else {
            Verdict.Apply(g.name, g.owner, members + fresh.map { GroupMemberRef(it, GroupRole.MEMBER) }, emptySet())
        }
    }

    suspend fun removeMember(groupId: String, memberId: String): GroupResult = manage(groupId) { g, members, me ->
        val mine = roleOf(members, me)
        val target = roleOf(members, memberId) ?: return@manage Verdict.Deny(GroupResult.NOT_FOUND)
        val allowed = memberId != me && target != GroupRole.OWNER &&
            (mine == GroupRole.OWNER || (mine == GroupRole.ADMIN && target == GroupRole.MEMBER))
        if (!allowed) return@manage Verdict.Deny(GroupResult.NOT_ALLOWED)
        if (g.mlsEnabled && mine == GroupRole.ADMIN) {
            Verdict.Propose("remove", listOf(memberId))
        } else {
            Verdict.Apply(g.name, g.owner, members.filter { it.id != memberId }, setOf(memberId))
        }
    }

    suspend fun setAdmin(groupId: String, memberId: String, admin: Boolean): GroupResult = manage(groupId) { g, members, me ->
        if (roleOf(members, me) != GroupRole.OWNER) return@manage Verdict.Deny(GroupResult.NOT_ALLOWED)
        val target = roleOf(members, memberId) ?: return@manage Verdict.Deny(GroupResult.NOT_FOUND)
        if (target == GroupRole.OWNER) return@manage Verdict.Deny(GroupResult.NOT_ALLOWED)
        val role = if (admin) GroupRole.ADMIN else GroupRole.MEMBER
        Verdict.Apply(g.name, g.owner, members.map { if (it.id == memberId) it.copy(role = role) else it }, emptySet())
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
        class Propose(val action: String, val targets: List<String>) : Verdict
    }

    private suspend fun manage(
        groupId: String,
        decide: suspend (GroupRecord, List<GroupMemberRef>, String) -> Verdict,
    ): GroupResult = lock.withLock {
        val me = ownId() ?: return@withLock GroupResult.NO_IDENTITY
        var g = store.group(groupId) ?: return@withLock GroupResult.NOT_FOUND
        if (!recoverPendingMlsCommitLocked(g)) return@withLock GroupResult.INVALID
        g = store.group(groupId) ?: return@withLock GroupResult.NOT_FOUND
        if (g.state != GroupState.ACTIVE) return@withLock GroupResult.NOT_ACTIVE
        val previousMembers = store.members(groupId)
        when (val v = decide(g, previousMembers, me)) {
            is Verdict.Deny -> v.result
            is Verdict.Propose -> {
                if (!g.mlsEnabled || roleOf(previousMembers, me) != GroupRole.ADMIN || g.owner == me) {
                    return@withLock GroupResult.NOT_ALLOWED
                }
                val proposalId = newId()
                val now = clock().coerceAtLeast(1L)
                if (v.action == "remove") {
                    if (v.targets.size != 1) return@withLock GroupResult.INVALID
                    val engine = mls ?: return@withLock GroupResult.MLS_UNAVAILABLE
                    val payload = runCatching { engine.proposeRemoveMember(groupId, v.targets.single()) }
                        .getOrElse { return@withLock GroupResult.INVALID }
                    store.enqueue(listOf(OutboxRow(
                        proposalId, groupId, g.owner, null,
                        EnvelopeCodec.encode(Envelope.GroupMls(
                            proposalId, me, g.owner, groupId, GroupMlsKind.PROPOSAL, payload
                        )),
                        now, 0, now, acked = false, failed = false,
                    )))
                } else {
                    // Additions still require the invited device to deliver a KeyPackage to the owner.
                    val payload = GroupChangeProposalCodec.encode(
                        GroupChangeProposal(proposalId, groupId, g.epoch, g.mlsEpoch, v.action, v.targets)
                    )
                    store.enqueue(listOf(OutboxRow(
                        proposalId, groupId, g.owner, null,
                        EnvelopeCodec.encode(Envelope.GroupMls(
                            proposalId, me, g.owner, groupId, GroupMlsKind.MEMBERSHIP_PROPOSAL, payload
                        )),
                        now, 0, now, acked = false, failed = false,
                    )))
                }
                GroupResult.PROPOSAL_QUEUED
            }
            is Verdict.Apply -> {
                val changesMlsMembership = v.members != previousMembers
                // MLS membership proposals are owner-routed and checked against both base epochs.
                // Only the owner reaches this native membership-commit path.
                if (g.mlsEnabled && changesMlsMembership && roleOf(previousMembers, me) != GroupRole.OWNER) {
                    return@withLock GroupResult.NOT_ALLOWED
                }
                var nextMlsEpoch = g.mlsEpoch
                if (g.mlsEnabled && v.removed.isNotEmpty()) {
                    val engine = mls ?: return@withLock GroupResult.MLS_UNAVAILABLE
                    var epochMembers = store.members(groupId)
                    for (removedId in v.removed) {
                        val target = epochMembers.firstOrNull { it.id == removedId && it.mlsJoined } ?: continue
                        val commit = engine.removeMember(groupId, target.id)
                        val recipients = epochMembers.filter { it.mlsJoined && it.id != me && it.id != target.id && peers.status(it.id) == PeerStatus.Known }
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

    private suspend fun enqueueMlsControl(groupId: String, from: String, to: String, kind: GroupMlsKind, payload: ByteArray) {
        require(payload.isNotEmpty() && payload.size <= 48 * 1024)
        val id = newId()
        val now = clock().coerceAtLeast(1L)
        store.enqueue(listOf(OutboxRow(
            id, groupId, to, null,
            EnvelopeCodec.encode(Envelope.GroupMls(id, from, to, groupId, kind, payload)),
            now, 0, now, acked = false, failed = false,
        )))
    }

    /** Handles authenticated MLS handshake, commit and application packets. */
    private suspend fun onMlsEnvelope(me: String, env: Envelope.GroupMls) {
        if (env.to != me || env.from == me || env.payload.isEmpty() || env.payload.size > 48 * 1024) return
        if (peers.status(env.from) != PeerStatus.Known) return
        val group = store.group(env.groupId) ?: return
        if (!group.mlsEnabled) return
        val members = store.members(env.groupId)
        val sender = members.firstOrNull { it.id == env.from } ?: return
        val localRole = roleOf(members, me)

        when (env.kind) {
            GroupMlsKind.PROPOSAL -> {
                if (group.state != GroupState.ACTIVE || localRole != GroupRole.OWNER ||
                    sender.role != GroupRole.ADMIN || !sender.mlsJoined) return
                if (store.hasControlMessage(env.messageId)) { ack(me, env.from, env.messageId); return }
                val engine = mls ?: return
                if (engine.epoch(env.groupId) != group.mlsEpoch) return
                val allowedTargets = members.filter {
                    it.mlsJoined && it.role == GroupRole.MEMBER
                }.map { it.id }.toSet()
                if (allowedTargets.isEmpty()) return
                val acceptedEpoch = runCatching {
                    engine.processProposal(env.payload, env.messageId, env.from, allowedTargets, allowAdd = false, allowRemove = true)
                }.getOrNull()
                if (acceptedEpoch == null || acceptedEpoch != group.mlsEpoch) {
                    // A malformed, unauthorized, replayed or stale proposal is terminally
                    // rejected at this epoch; acknowledge it so the sender does not retry forever.
                    store.recordControlMessage(env.messageId, clock())
                    ack(me, env.from, env.messageId)
                    return
                }
                if (!store.recordControlMessage(env.messageId, clock())) { ack(me, env.from, env.messageId); return }
                refreshPendingMlsProposalsLocked(env.groupId)
                ack(me, env.from, env.messageId)
            }
            GroupMlsKind.MEMBERSHIP_PROPOSAL -> {
                if (group.state != GroupState.ACTIVE || localRole != GroupRole.OWNER ||
                    sender.role != GroupRole.ADMIN || !sender.mlsJoined) return
                if (store.hasControlMessage(env.messageId)) { ack(me, env.from, env.messageId); return }
                val proposal = GroupChangeProposalCodec.decode(env.payload) ?: return
                if (proposal.proposalId != env.messageId || proposal.groupId != env.groupId) return
                if (proposal.baseGroupEpoch != group.epoch || proposal.baseMlsEpoch != group.mlsEpoch) {
                    store.recordControlMessage(env.messageId, clock())
                    broadcastSnapshot(env.groupId, setOf(env.from))
                    ack(me, env.from, env.messageId)
                    return
                }

                val targetIds = proposal.targets.toSet()
                val updatedMembers: List<GroupMemberRef>
                var nextMlsEpoch = group.mlsEpoch
                when (proposal.action) {
                    "add" -> {
                        if (members.size + targetIds.size > GroupLimits.MAX_MEMBERS ||
                            proposal.targets.any { id ->
                                members.any { it.id == id } || peers.status(id) != PeerStatus.Known
                            }) {
                            store.recordControlMessage(env.messageId, clock())
                            broadcastSnapshot(env.groupId, setOf(env.from))
                            ack(me, env.from, env.messageId)
                            return
                        }
                        updatedMembers = members + proposal.targets.map { GroupMemberRef(it, GroupRole.MEMBER) }
                    }
                    "remove" -> {
                        if (proposal.targets.any { id ->
                                id == env.from || members.none { it.id == id && it.role == GroupRole.MEMBER }
                            }) {
                            store.recordControlMessage(env.messageId, clock())
                            broadcastSnapshot(env.groupId, setOf(env.from))
                            ack(me, env.from, env.messageId)
                            return
                        }
                        var epochMembers = members
                        for (targetId in proposal.targets) {
                            val target = epochMembers.first { it.id == targetId }
                            if (target.mlsJoined) {
                                val engine = mls ?: return
                                val commit = engine.removeMember(env.groupId, targetId)
                                val now = clock().coerceAtLeast(1L)
                                val recipients = epochMembers.filter {
                                    it.mlsJoined && it.id != me && it.id != targetId &&
                                        peers.status(it.id) == PeerStatus.Known
                                }
                                store.enqueue(recipients.map { recipient ->
                                    val id = newId()
                                    OutboxRow(
                                        id, env.groupId, recipient.id, null,
                                        EnvelopeCodec.encode(Envelope.GroupMls(
                                            id, me, recipient.id, env.groupId, GroupMlsKind.COMMIT, commit.commit
                                        )),
                                        now, 0, now, acked = false, failed = false,
                                    )
                                })
                                nextMlsEpoch = commit.epoch
                                epochMembers = epochMembers.map {
                                    if (it.id == targetId) it.copy(mlsJoined = false) else it
                                }
                            }
                        }
                        updatedMembers = epochMembers.filterNot { it.id in targetIds }
                    }
                    else -> return
                }
                store.putGroup(
                    group.copy(epoch = group.epoch + 1, mlsEpoch = nextMlsEpoch),
                    updatedMembers,
                )
                broadcastSnapshot(env.groupId, targetIds)
                store.recordControlMessage(env.messageId, clock())
                ack(me, env.from, env.messageId)
            }
            GroupMlsKind.KEY_PACKAGE -> {
                if (localRole != GroupRole.OWNER || sender.mlsJoined || env.from == group.owner) return
                val engine = mls ?: return
                val credential = engine.keyPackageIdentity(env.payload)
                if (!MlsCredentialCodec.verify(credential, env.from)) return
                if (store.hasControlMessage(env.messageId)) { ack(me, env.from, env.messageId); return }
                // The owner turns the validated KeyPackage into a native RFC 9420
                // Add Proposal. Pending admin proposals are never auto-approved by this path.
                if (engine.pendingProposalCount(env.groupId) > 0) return
                val addProposal = engine.proposeAddMember(env.groupId, env.payload)
                engine.processProposal(
                    addProposal, newId(), me, setOf(env.from), allowAdd = true, allowRemove = false,
                )
                val bundle = engine.commitPendingProposals(env.groupId)
                val prepared = engine.commitPendingProposals(env.groupId)
                if (prepared.welcome == null || env.from.lowercase() !in prepared.addedIds) return
                persistPreparedMlsCommit(group, members, prepared, env.messageId, clock())
                refreshPendingMlsProposalsLocked(env.groupId)
                ack(me, env.from, env.messageId)
            }
            GroupMlsKind.WELCOME -> {
                if (env.from != group.owner || sender.role != GroupRole.OWNER) return
                if (group.state == GroupState.ACTIVE && members.any { it.id == me && it.mlsJoined }) {
                    ack(me, env.from, env.messageId)
                    return
                }
                if (group.state != GroupState.JOINING) return
                val engine = mls ?: return
                val joinedEpoch = engine.joinGroup(env.groupId, env.payload, me)
                if (!store.recordControlMessage(env.messageId, clock())) { ack(me, env.from, env.messageId); return }
                val updatedMembers = members.map { if (it.id == me) it.copy(mlsJoined = true) else it }
                store.putGroup(group.copy(state = GroupState.ACTIVE, mlsEpoch = joinedEpoch), updatedMembers)
                enqueueMlsControl(env.groupId, me, group.owner, GroupMlsKind.JOIN_ACK, joinedEpoch.toString().toByteArray())
                ack(me, env.from, env.messageId)
            }
            GroupMlsKind.JOIN_ACK -> {
                if (localRole != GroupRole.OWNER || env.from == group.owner) return
                if (sender.mlsJoined) { ack(me, env.from, env.messageId); return }
                val acknowledgedEpoch = env.payload.toString(Charsets.UTF_8).toLongOrNull() ?: return
                if (acknowledgedEpoch != group.mlsEpoch) return
                val activeCredentials = mlsCredentialIds(env.groupId) ?: return
                if (env.from.lowercase() !in activeCredentials) return
                if (!store.recordControlMessage(env.messageId, clock())) { ack(me, env.from, env.messageId); return }
                val updatedMembers = members.map { if (it.id == env.from) it.copy(mlsJoined = true) else it }
                store.putGroup(group.copy(mlsEpoch = acknowledgedEpoch), updatedMembers)
                broadcastSnapshot(env.groupId, emptySet())
                ack(me, env.from, env.messageId)
            }
            GroupMlsKind.COMMIT -> {
                if (!sender.mlsJoined || sender.role != GroupRole.OWNER) return
                val engine = mls ?: return
                if (store.hasControlMessage(env.messageId)) { ack(me, env.from, env.messageId); return }
                val result = engine.processMessage(env.payload)
                if (result.groupId != env.groupId || result.kind !in setOf("commit", "own_commit")) return
                store.putGroup(group.copy(mlsEpoch = result.epoch), members)
                store.recordControlMessage(env.messageId, clock())
                ack(me, env.from, env.messageId)
            }
            GroupMlsKind.APPLICATION -> {
                if (group.state != GroupState.ACTIVE || !sender.mlsJoined) return
                if (group.kind == GroupKind.CHANNEL && sender.role == GroupRole.MEMBER) return
                if (store.hasControlMessage(env.messageId)) { ack(me, env.from, env.messageId); return }
                val engine = mls ?: return
                val result = engine.processMessage(env.payload)
                if (result.groupId != env.groupId || result.kind != "application") return
                when (val app = GroupPayloadCodec.decode(result.application ?: return) ?: return) {
                    is GroupTextPayload -> {
                        val fresh = store.recordIncomingMessage(
                            // The outer authenticated message ID is the sender's durable per-recipient
                            // outbox copy ID. Receipts must reference this exact ID, not the shared app ID.
                            GroupMessage(env.messageId, env.groupId, env.from, false, app.body, app.sentAt, false)
                        )
                        if (fresh) runCatching { incoming?.onMessage(env.groupId, true, env.from, app.body) }
                    }
                    is GroupFilePayload -> {
                        val safeUrl = org.relyra.app.core.transfer.BlossomUrl.normalize(
                            app.file.serverUrl, allowPrivate = false,
                        ) ?: return
                        val attachment = GroupAttachmentRecord(
                            env.messageId, env.groupId, env.from, org.relyra.app.core.transfer.FileNames.sanitize(app.file.name),
                            app.file.mime, app.file.size, app.file.blobSize, app.file.plainSha256,
                            app.file.blobSha256, app.file.fileKey, safeUrl, null,
                            GroupAttachmentPhase.REMOTE, app.sentAt, false,
                        )
                        val fresh = store.recordIncomingGroupAttachment(
                            GroupMessage(env.messageId, env.groupId, env.from, false, attachment.name, app.sentAt, false),
                            attachment,
                        )
                        if (fresh) runCatching { incoming?.onMessage(env.groupId, true, env.from, null) }
                    }
                }
                store.recordControlMessage(env.messageId, clock())
                ack(me, env.from, env.messageId)
            }
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

    /** Reads and validates the RELYRA-signed account/device credentials in the local MLS tree. */
    private suspend fun refreshPendingMlsProposalsLocked(groupId: String): Int {
        val count = runCatching { mls?.pendingProposalCount(groupId) ?: 0 }.getOrDefault(0)
        pendingMlsProposals.value = pendingMlsProposals.value.toMutableMap().apply {
            if (count == 0) remove(groupId) else put(groupId, count)
        }
        return count
    }

    /** The group owner commits the complete validated OpenMLS proposal queue as one RFC 9420 Commit. */
    suspend fun commitPendingMlsProposals(groupId: String): GroupResult = lock.withLock {
        val me = ownId() ?: return@withLock GroupResult.NO_IDENTITY
        var group = store.group(groupId) ?: return@withLock GroupResult.NOT_FOUND
        if (!recoverPendingMlsCommitLocked(group)) return@withLock GroupResult.INVALID
        group = store.group(groupId) ?: return@withLock GroupResult.NOT_FOUND
        if (group.state != GroupState.ACTIVE) return@withLock GroupResult.NOT_ACTIVE
        if (!group.mlsEnabled || group.owner != me) return@withLock GroupResult.NOT_ALLOWED
        val engine = mls ?: return@withLock GroupResult.MLS_UNAVAILABLE
        if (engine.epoch(groupId) != group.mlsEpoch) return@withLock GroupResult.INVALID
        val pending = engine.pendingProposalCount(groupId)
        if (pending == 0) {
            refreshPendingMlsProposalsLocked(groupId)
            return@withLock GroupResult.OK
        }
        val before = store.members(groupId)
        val prepared = engine.commitPendingProposals(groupId)
        persistPreparedMlsCommit(group, before, prepared)
        refreshPendingMlsProposalsLocked(groupId)
        GroupResult.OK
    }

    private suspend fun persistPreparedMlsCommit(
        group: GroupRecord,
        before: List<GroupMemberRef>,
        prepared: MlsPreparedCommit,
        controlMessageId: String? = null,
        receivedAt: Long? = null,
    ) {
        val me = ownId() ?: throw IllegalStateException("RELYRA identity unavailable")
        val removed = prepared.removedIds.map { it.lowercase() }.toSet()
        val added = prepared.addedIds.map { it.lowercase() }.toSet()
        if (added.isNotEmpty() && prepared.welcome == null) {
            throw IllegalStateException("OpenMLS Add Commit did not produce a Welcome")
        }
        val activeIds = before.filter { it.mlsJoined }.map { it.id.lowercase() }.toSet() - removed + added
        val updated = before
            .filter { !it.mlsJoined || it.id.lowercase() in activeIds }
            .map { it.copy(mlsJoined = it.id.lowercase() in activeIds) } +
            added.filter { target -> before.none { it.id.equals(target, ignoreCase = true) } }
                .map { GroupMemberRef(it, GroupRole.MEMBER, mlsJoined = true) }
        val now = clock().coerceAtLeast(1L)
        val recipients = before.filter { it.mlsJoined && it.id != me && peers.status(it.id) == PeerStatus.Known }
        val rows = recipients.map { recipient ->
            val id = newId()
            OutboxRow(
                id, group.groupId, recipient.id, null,
                EnvelopeCodec.encode(Envelope.GroupMls(id, me, recipient.id, group.groupId, GroupMlsKind.COMMIT, prepared.commit)),
                now, 0, now, acked = false, failed = false,
            )
        } + added.map { target ->
            val id = newId()
            OutboxRow(
                id, group.groupId, target, null,
                EnvelopeCodec.encode(Envelope.GroupMls(id, me, target, group.groupId, GroupMlsKind.WELCOME, prepared.welcome!!)),
                now, 0, now, acked = false, failed = false,
            )
        }
        store.applyMlsCommit(
            group.copy(epoch = group.epoch + 1, mlsEpoch = prepared.epoch),
            updated,
            rows,
            controlMessageId,
            receivedAt,
        )
        // The native pending Commit is merged only after the Room epoch and its
        // retryable network packets are durable. Startup recovery finishes this step.
        mls?.mergePendingCommit(group.groupId)
        broadcastSnapshot(group.groupId, removed)
    }

    private suspend fun recoverPendingMlsCommitLocked(group: GroupRecord): Boolean {
        val engine = mls ?: return true
        val prepared = engine.pendingCommitInfo(group.groupId) ?: return true
        return when {
            group.mlsEpoch == prepared.epoch -> {
                engine.mergePendingCommit(group.groupId)
                true
            }
            group.mlsEpoch + 1 == prepared.epoch -> {
                val before = store.members(group.groupId)
                persistPreparedMlsCommit(group, before, prepared)
                true
            }
            else -> false
        }
    }

    suspend fun refreshPendingMlsProposals(groupId: String): Int = lock.withLock {
        val group = store.group(groupId) ?: return@withLock 0
        if (!recoverPendingMlsCommitLocked(group)) return@withLock 0
        refreshPendingMlsProposalsLocked(groupId)
    }

    private suspend fun mlsCredentialIds(groupId: String): Set<String>? {
        val engine = mls ?: return null
        val encoded = runCatching { engine.members(groupId) }.getOrNull() ?: return null
        val ids = linkedSetOf<String>()
        for (value in encoded) {
            val credential = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return null
            val parsed = MlsCredentialCodec.decode(credential) ?: return null
            if (!MlsCredentialCodec.verify(credential, parsed.relyraPublicId)) return null
            if (!ids.add(parsed.relyraPublicId.lowercase())) return null
        }
        return ids
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
        // A removed member intentionally does not receive the new MLS Commit.
        // Validate native epoch/member state only for replicas that remain in the epoch.
        if (env.mlsEnabled && local.state == GroupState.ACTIVE && iAmIn) {
            val nativeEpoch = runCatching { mls?.epoch(env.groupId) }.getOrNull() ?: return
            if (nativeEpoch != env.mlsEpoch) return
            val nativeMembers = mlsCredentialIds(env.groupId) ?: return
            val declaredMembers = env.members.filter { it.mlsJoined }
                .map { it.id.lowercase() }.toSet()
            if (nativeMembers != declaredMembers) return
        }

        val localMembers = store.members(env.groupId)
        val senderRole = roleOf(localMembers, env.from)
        if (!isStaff(senderRole) || env.kind != local.kind) return
        if (local.mlsEnabled && senderRole == GroupRole.ADMIN && env.members != localMembers) return
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
                val recipients = members.filter { it.mlsJoined && it.id != me && it.id != env.from && peers.status(it.id) == PeerStatus.Known }
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
        for (candidate in store.dueOutbox(now, 100)) {
            var row = store.outbox(candidate.id) ?: continue
            if (row.acked || row.failed) continue
            if (now - row.createdAt > DeliveryEngine.TTL_MS) {
                store.updateOutbox(row.copy(failed = true, nextAttemptAt = Long.MAX_VALUE))
                continue
            }
            val outcome = transmit(row.memberId, row.payload)
            // A synchronous or very fast remote ACK may update this row while send() is running.
            // Re-read before writing retry metadata so an old snapshot cannot erase that ACK.
            row = store.outbox(row.id) ?: continue
            if (row.acked || row.failed) {
                if (row.acked && (outcome == SendOutcome.Accepted || outcome == SendOutcome.Relayed)) sent++
                continue
            }
            val attempts = row.attempts + 1
            when (outcome) {
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
