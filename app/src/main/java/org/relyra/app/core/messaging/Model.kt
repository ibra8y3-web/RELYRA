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

enum class GroupKind { GROUP, CHANNEL }
enum class GroupRole { MEMBER, ADMIN, OWNER }
enum class GroupState { INVITED, ACTIVE, LEFT, REMOVED }

class GroupMemberRef(val id: String, val role: GroupRole)

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
                    val owner: String, val members: List<GroupMemberRef>) : Envelope

    class GroupLeave(override val messageId: String, override val from: String, override val to: String,
                     val groupId: String) : Envelope

    class Chat(override val messageId: String, override val from: String, override val to: String,
               val sentAt: Long, val body: String) : Envelope

    class Ack(override val messageId: String, override val from: String, override val to: String,
              val kind: AckKind) : Envelope

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

/** Compact binary wire format (version 1). It is always wrapped in end-to-end encryption before it reaches a transport. */
object EnvelopeCodec {
    const val MAX_BODY = 4_000
    private const val MAX_PACKET = 64 * 1024
    private const val M1 = 0x52
    private const val M2 = 0x4C
    private const val VERSION = 1
    private const val T_CHAT = 1
    private const val T_ACK = 2
    private const val T_ATTACH = 3
    private const val T_GROUP_CHAT = 4
    private const val T_GROUP_SYNC = 5
    private const val T_GROUP_LEAVE = 6

    fun encode(e: Envelope): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeByte(M1); o.writeByte(M2); o.writeByte(VERSION)
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
                    o.writeShort(e.members.size)
                    for (m in e.members) { o.writeUTF(m.id); o.writeByte(m.role.ordinal) }
                }
                is Envelope.GroupLeave -> {
                    o.writeByte(T_GROUP_LEAVE)
                    o.writeUTF(e.messageId); o.writeUTF(e.from); o.writeUTF(e.to)
                    o.writeUTF(e.groupId)
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
                if (i.readUnsignedByte() != M1 || i.readUnsignedByte() != M2 || i.readUnsignedByte() != VERSION) return null
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
                        val count = i.readUnsignedShort()
                        if (count !in 1..GroupLimits.MAX_MEMBERS || name.length > GroupLimits.MAX_NAME || owner.length > 100 || epoch < 0) return null
                        val members = ArrayList<GroupMemberRef>(count)
                        repeat(count) {
                            val mid = i.readUTF()
                            val role = GroupRole.entries.getOrNull(i.readUnsignedByte()) ?: return null
                            if (mid.isEmpty() || mid.length > 100) return null
                            members += GroupMemberRef(mid, role)
                        }
                        Envelope.GroupSync(id, from, to, gid, name, kind, epoch, owner, members)
                    }
                    T_GROUP_LEAVE -> {
                        val gid = i.readUTF()
                        UUID.fromString(gid)
                        Envelope.GroupLeave(id, from, to, gid)
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
