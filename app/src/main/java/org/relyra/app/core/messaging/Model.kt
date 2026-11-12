package org.relyra.app.core.messaging

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

enum class MessageState {
    QUEUED, SENT, RELAYED, DELIVERED, READ, FAILED, EXPIRED, CANCELLED;

    private val rank: Int
        get() = when (this) { QUEUED -> 0; SENT -> 1; RELAYED -> 2; DELIVERED -> 3; READ -> 4; else -> -1 }

    companion object {
        /** States only move forward. A late receipt may still rescue a FAILED/EXPIRED message; CANCELLED is final. */
        fun canAdvance(from: MessageState, to: MessageState): Boolean = when (to) {
            SENT, RELAYED -> from.rank in 0 until to.rank
            DELIVERED, READ -> from == FAILED || from == EXPIRED || from.rank in 0 until to.rank
            else -> false
        }
    }
}

data class StoredMessage(
    val messageId: String,
    val chatId: String,          // the peer's public ID
    val outgoing: Boolean,
    val body: String,
    val createdAt: Long,
    val state: MessageState,
    val attempts: Int,
    val nextAttemptAt: Long,
    val readLocally: Boolean,
    /** Exact encrypted transport packet reused for every retry; prevents ratchet advancement on retransmission. */
    val wirePayload: ByteArray? = null,
)

enum class AckKind { DELIVERED, READ }
enum class P2pSignalKind { OFFER, ANSWER }

enum class GroupKind { GROUP, CHANNEL }
enum class GroupRole { MEMBER, ADMIN, OWNER }
enum class GroupState { INVITED, JOINING, ACTIVE, LEFT, REMOVED }
enum class GroupMlsKind { KEY_PACKAGE, COMMIT, WELCOME, APPLICATION, JOIN_ACK, MEMBERSHIP_PROPOSAL }

data class GroupChangeProposal(
    val proposalId: String,
    val groupId: String,
    val baseGroupEpoch: Long,
    val baseMlsEpoch: Long,
    val action: String,
    val targets: List<String>,
)

/** Bounded owner-routed control packet carried inside the proposer's authenticated 1:1 E2EE session. */
object GroupChangeProposalCodec {
    private const val MAGIC = "RELYRA-GROUP-CHANGE-PROPOSAL-V1"
    private const val VERSION = 1
    private const val MAX_BYTES = 12 * 1024

    fun encode(proposal: GroupChangeProposal): ByteArray {
        require(runCatching { UUID.fromString(proposal.proposalId) }.isSuccess)
        require(runCatching { UUID.fromString(proposal.groupId) }.isSuccess)
        require(proposal.baseGroupEpoch >= 1 && proposal.baseMlsEpoch >= 0)
        require(proposal.action == "add" || proposal.action == "remove")
        require(proposal.targets.size in 1..GroupLimits.MAX_MEMBERS)
        require(proposal.targets.distinct().size == proposal.targets.size)
        require(proposal.targets.all { it.isNotBlank() && it.length <= 128 })
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(proposal.proposalId.lowercase())
            out.writeUTF(proposal.groupId.lowercase())
            out.writeLong(proposal.baseGroupEpoch)
            out.writeLong(proposal.baseMlsEpoch)
            out.writeUTF(proposal.action)
            out.writeShort(proposal.targets.size)
            proposal.targets.forEach { out.writeUTF(it.lowercase()) }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(raw: ByteArray): GroupChangeProposal? = runCatching {
        require(raw.isNotEmpty() && raw.size <= MAX_BYTES)
        DataInputStream(ByteArrayInputStream(raw)).use { input ->
            require(input.readUTF() == MAGIC && input.readInt() == VERSION)
            val proposalId = input.readUTF()
            val groupId = input.readUTF()
            val groupEpoch = input.readLong()
            val mlsEpoch = input.readLong()
            val action = input.readUTF()
            val count = input.readUnsignedShort()
            require(runCatching { UUID.fromString(proposalId) }.isSuccess)
            require(runCatching { UUID.fromString(groupId) }.isSuccess)
            require(groupEpoch >= 1 && mlsEpoch >= 0)
            require(action == "add" || action == "remove")
            require(count in 1..GroupLimits.MAX_MEMBERS)
            val targets = List(count) { input.readUTF() }
            require(targets.distinct().size == targets.size && targets.all { it.isNotBlank() && it.length <= 128 })
            require(input.available() == 0)
            GroupChangeProposal(proposalId, groupId, groupEpoch, mlsEpoch, action, targets)
        }
    }.getOrNull()
}


data class GroupMemberRef(
    val id: String,
    val role: GroupRole,
    val mlsJoined: Boolean = false,
)

object GroupLimits {
    const val MAX_MEMBERS = 200
    const val MAX_NAME = 64
    fun sanitizeName(raw: String): String = raw.filter { !it.isISOControl() }.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)
}

sealed interface Envelope {
    val messageId: String
    val from: String
    val to: String

    /** One copy of a group message, addressed to one member (each copy has its own ID and is acknowledged on its own). */
    class GroupChat(override val messageId: String, override val from: String, override val to: String,
                    val groupId: String, val sentAt: Long, val body: String) : Envelope

    /** Full membership snapshot of a group. Receivers accept it only from someone who is an admin/owner in THEIR copy. */
    class GroupSync(override val messageId: String, override val from: String, override val to: String,
                    val groupId: String, val name: String, val kind: GroupKind, val epoch: Long,
                    val owner: String, val members: List<GroupMemberRef>,
                    val mlsEnabled: Boolean = false, val mlsEpoch: Long = 0) : Envelope

    class GroupLeave(override val messageId: String, override val from: String, override val to: String,
                     val groupId: String) : Envelope

    /** MLS protocol packet, always carried inside the sender's authenticated RELYRA session. */
    class GroupMls(
        override val messageId: String,
        override val from: String,
        override val to: String,
        val groupId: String,
        val kind: GroupMlsKind,
        val payload: ByteArray,
    ) : Envelope

    /** A member read a specific per-recipient copy of a group message. */
    class GroupReadReceipt(
        override val messageId: String,
        override val from: String,
        override val to: String,
        val groupId: String,
        val originalMessageId: String,
        val readAt: Long,
    ) : Envelope

    class Chat(override val messageId: String, override val from: String, override val to: String,
               val sentAt: Long, val body: String) : Envelope

    class Ack(override val messageId: String, override val from: String, override val to: String,
              val kind: AckKind) : Envelope

    /** Signed WebRTC SDP signaling; the containing RELYRA envelope is E2EE-protected. */
    class P2pSignal(
        override val messageId: String,
        override val from: String,
        override val to: String,
        val kind: P2pSignalKind,
        val signedSignal: String,
    ) : Envelope

    /**
     * A file that was encrypted on the sender's device and uploaded to a Blossom server. Only this small descriptor
     * (including the one-time file key) travels inside the end-to-end encrypted message; the server only ever
     * holds ciphertext.
     */
    class Attachment(
        override val messageId: String, override val from: String, override val to: String,
        val sentAt: Long, val name: String, val mime: String,
        val size: Long, val blobSize: Long,
        val plainSha256: ByteArray, val blobSha256: ByteArray, val key: ByteArray,
        val serverUrl: String,
    ) : Envelope
}

object AttachmentLimits {
    const val MAX_BYTES = 100L * 1024 * 1024
    const val MAX_NAME = 255
    const val MAX_MIME = 100
    const val MAX_URL = 200

    /** The encrypted blob is the plaintext plus a small header and one 16-byte tag per 1 MiB segment. */
    fun validSizes(size: Long, blobSize: Long): Boolean =
        size in 1L..MAX_BYTES && blobSize > size && blobSize <= size + size / 1000 + 1024
}

/** Compact binary wire format. It is always wrapped in end-to-end encryption before it reaches a transport. */
object EnvelopeCodec {
    const val MAX_BODY = 4_000
    private const val MAX_PACKET = 64 * 1024
    private const val MAX_MLS_PAYLOAD = 48 * 1024
    private const val M1 = 0x52
    private const val M2 = 0x4C
    private const val VERSION_1 = 1
    private const val VERSION_2 = 2
    private const val T_CHAT = 1
    private const val T_ACK = 2
    private const val T_ATTACH = 3
    private const val T_GROUP_CHAT = 4
    private const val T_GROUP_SYNC = 5
    private const val T_GROUP_LEAVE = 6
    private const val T_P2P_SIGNAL = 7
    private const val T_GROUP_READ_RECEIPT = 8
    private const val T_GROUP_MLS = 9
    const val MAX_P2P_SIGNAL_CHARS = 48_000

    fun encode(e: Envelope): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            val wireVersion = if (e is Envelope.GroupSync || e is Envelope.GroupMls) VERSION_2 else VERSION_1
            o.writeByte(M1); o.writeByte(M2); o.writeByte(wireVersion)
            when (e) {
                is Envelope.Chat -> {
                    o.writeByte(T_CHAT)
                    o.writeUTF(e.messageId); o.writeUTF(e.from); o.writeUTF(e.to)
                    o.writeLong(e.sentAt); o.writeUTF(e.body)
                }
                is Envelope.Ack -> {
                    o.writeByte(T_ACK)
                    o.writeUTF(e.messageId); o.writeUTF(e.from); o.writeUTF(e.to)
                    o.writeByte(e.kind.ordinal)
                }
                is Envelope.P2pSignal -> {
                    require(e.signedSignal.isNotBlank() && e.signedSignal.length <= MAX_P2P_SIGNAL_CHARS)
                    o.writeByte(T_P2P_SIGNAL)
                    o.writeUTF(e.messageId); o.writeUTF(e.from); o.writeUTF(e.to)
                    o.writeByte(e.kind.ordinal)
                    o.writeUTF(e.signedSignal)
                }
                is Envelope.Attachment -> {
                    require(e.plainSha256.size == 32 && e.blobSha256.size == 32 && e.key.size == 32)
                    o.writeByte(T_ATTACH)
                    o.writeUTF(e.messageId); o.writeUTF(e.from); o.writeUTF(e.to)
                    o.writeLong(e.sentAt)
                    o.writeUTF(e.name); o.writeUTF(e.mime)
                    o.writeLong(e.size); o.writeLong(e.blobSize)
                    o.write(e.plainSha256); o.write(e.blobSha256); o.write(e.key)
                    o.writeUTF(e.serverUrl)
                }
                is Envelope.GroupChat -> {
                    o.writeByte(T_GROUP_CHAT)
                    o.writeUTF(e.messageId); o.writeUTF(e.from); o.writeUTF(e.to)
                    o.writeUTF(e.groupId); o.writeLong(e.sentAt); o.writeUTF(e.body)
                }
                is Envelope.GroupSync -> {
                    require(e.members.size in 1..GroupLimits.MAX_MEMBERS)
                    o.writeByte(T_GROUP_SYNC)
                    o.writeUTF(e.messageId); o.writeUTF(e.from); o.writeUTF(e.to)
                    o.writeUTF(e.groupId); o.writeUTF(e.name)
                    o.writeByte(e.kind.ordinal); o.writeLong(e.epoch); o.writeUTF(e.owner)
                    o.writeBoolean(e.mlsEnabled); o.writeLong(e.mlsEpoch)
                    o.writeShort(e.members.size)
                    for (m in e.members) {
                        o.writeUTF(m.id); o.writeByte(m.role.ordinal); o.writeBoolean(m.mlsJoined)
                    }
                }
                is Envelope.GroupLeave -> {
                    o.writeByte(T_GROUP_LEAVE)
                    o.writeUTF(e.messageId); o.writeUTF(e.from); o.writeUTF(e.to)
                    o.writeUTF(e.groupId)
                }
                is Envelope.GroupReadReceipt -> {
                    require(e.readAt > 0)
                    o.writeByte(T_GROUP_READ_RECEIPT)
                    o.writeUTF(e.messageId); o.writeUTF(e.from); o.writeUTF(e.to)
                    o.writeUTF(e.groupId); o.writeUTF(e.originalMessageId); o.writeLong(e.readAt)
                }
                is Envelope.GroupMls -> {
                    require(e.payload.isNotEmpty() && e.payload.size <= MAX_MLS_PAYLOAD)
                    o.writeByte(T_GROUP_MLS)
                    o.writeUTF(e.messageId); o.writeUTF(e.from); o.writeUTF(e.to)
                    o.writeUTF(e.groupId); o.writeByte(e.kind.ordinal)
                    o.writeInt(e.payload.size); o.write(e.payload)
                }
            }
        }
        return bos.toByteArray()
    }

    /** Returns null for anything malformed; never throws. */
    fun decode(bytes: ByteArray): Envelope? {
        if (bytes.size > MAX_PACKET) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { i ->
                if (i.readUnsignedByte() != M1 || i.readUnsignedByte() != M2) return null
                val wireVersion = i.readUnsignedByte()
                if (wireVersion !in VERSION_1..VERSION_2) return null
                val type = i.readUnsignedByte()
                val id = i.readUTF()
                val from = i.readUTF()
                val to = i.readUTF()
                if (from.length > 100 || to.length > 100) return null
                UUID.fromString(id)
                val decoded = when (type) {
                    T_CHAT -> {
                        val sentAt = i.readLong()
                        val body = i.readUTF()
                        if (body.isBlank() || body.length > MAX_BODY) null else Envelope.Chat(id, from, to, sentAt, body)
                    }
                    T_ACK -> {
                        val k = AckKind.entries.getOrNull(i.readUnsignedByte()) ?: return null
                        Envelope.Ack(id, from, to, k)
                    }
                    T_P2P_SIGNAL -> {
                        val kind = P2pSignalKind.entries.getOrNull(i.readUnsignedByte()) ?: return null
                        val signal = i.readUTF()
                        if (signal.isBlank() || signal.length > MAX_P2P_SIGNAL_CHARS) null
                        else Envelope.P2pSignal(id, from, to, kind, signal)
                    }
                    T_ATTACH -> {
                        val sentAt = i.readLong()
                        val name = i.readUTF()
                        val mime = i.readUTF()
                        val size = i.readLong()
                        val blobSize = i.readLong()
                        val plain = ByteArray(32).also { i.readFully(it) }
                        val blob = ByteArray(32).also { i.readFully(it) }
                        val key = ByteArray(32).also { i.readFully(it) }
                        val url = i.readUTF()
                        if (name.isBlank() || name.length > AttachmentLimits.MAX_NAME ||
                            mime.length > AttachmentLimits.MAX_MIME || url.length > AttachmentLimits.MAX_URL ||
                            !AttachmentLimits.validSizes(size, blobSize)
                        ) null
                        else Envelope.Attachment(id, from, to, sentAt, name, mime, size, blobSize, plain, blob, key, url)
                    }
                    T_GROUP_CHAT -> {
                        val gid = i.readUTF()
                        UUID.fromString(gid)
                        val sentAt = i.readLong()
                        val body = i.readUTF()
                        if (body.isBlank() || body.length > MAX_BODY) null else Envelope.GroupChat(id, from, to, gid, sentAt, body)
                    }
                    T_GROUP_SYNC -> {
                        val gid = i.readUTF()
                        UUID.fromString(gid)
                        val name = i.readUTF()
                        val kind = GroupKind.entries.getOrNull(i.readUnsignedByte()) ?: return null
                        val epoch = i.readLong()
                        val owner = i.readUTF()
                        val mlsEnabled = if (wireVersion >= VERSION_2) i.readBoolean() else false
                        val mlsEpoch = if (wireVersion >= VERSION_2) i.readLong() else 0L
                        if (mlsEpoch < 0) return null
                        val count = i.readUnsignedShort()
                        if (count !in 1..GroupLimits.MAX_MEMBERS || name.length > GroupLimits.MAX_NAME || owner.length > 100 || epoch < 0) return null
                        val members = ArrayList<GroupMemberRef>(count)
                        repeat(count) {
                            val mid = i.readUTF()
                            val role = GroupRole.entries.getOrNull(i.readUnsignedByte()) ?: return null
                            if (mid.isEmpty() || mid.length > 100) return null
                            val mlsJoined = if (wireVersion >= VERSION_2) i.readBoolean() else false
                            members += GroupMemberRef(mid, role, mlsJoined)
                        }
                        Envelope.GroupSync(id, from, to, gid, name, kind, epoch, owner, members, mlsEnabled, mlsEpoch)
                    }
                    T_GROUP_LEAVE -> {
                        val gid = i.readUTF()
                        UUID.fromString(gid)
                        Envelope.GroupLeave(id, from, to, gid)
                    }
                    T_GROUP_READ_RECEIPT -> {
                        val gid = i.readUTF()
                        val originalId = i.readUTF()
                        val readAt = i.readLong()
                        UUID.fromString(gid)
                        UUID.fromString(originalId)
                        if (readAt <= 0) return null
                        Envelope.GroupReadReceipt(id, from, to, gid, originalId, readAt)
                    }
                    T_GROUP_MLS -> {
                        if (wireVersion < VERSION_2) return null
                        val gid = i.readUTF()
                        UUID.fromString(gid)
                        val kind = GroupMlsKind.entries.getOrNull(i.readUnsignedByte()) ?: return null
                        val length = i.readInt()
                        if (length !in 1..MAX_MLS_PAYLOAD || length > i.available()) return null
                        val payload = ByteArray(length).also { i.readFully(it) }
                        Envelope.GroupMls(id, from, to, gid, kind, payload)
                    }
                    else -> null
                }
                if (decoded == null || i.available() != 0) return null
                decoded
            }
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
    }
}
