package org.relyra.app.core.crypto

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * First-message bridge from X3DH into Double Ratchet. Subsequent messages use RatchetWire v2.
 * The caller supplies the sender's identity DH key from the already verified contact record.
 */
object X3dhRatchetBootstrap {
    private const val VERSION = 3
    private const val MAX_ID_BYTES = 255
    private const val MIN_BODY_BYTES = 28

    data class Initial(val wire: ByteArray, val state: RatchetState)
    data class Accepted(val senderId: String, val plaintext: ByteArray, val state: RatchetState, val consumedOneTimePreKeyId: Long?)

    fun initiate(
        senderId: String,
        peer: X3dh.PreKeyBundle,
        identityDhPrivate: ByteArray,
        plaintext: ByteArray,
        nowSeconds: Long,
    ): Initial {
        val agreement = X3dh.initiate(identityDhPrivate, peer, nowSeconds)
        val state = DoubleRatchet.initAlice(agreement.sharedSecret, peer.signedPreKey)
        val encrypted = DoubleRatchet.encrypt(
            state, plaintext, DoubleRatchet.aad(senderId, peer.identityId),
        ) ?: error("new Alice ratchet must be able to send")
        val sender = senderId.toByteArray(Charsets.UTF_8)
        val recipient = peer.identityId.toByteArray(Charsets.UTF_8)
        require(sender.isNotEmpty() && sender.size <= MAX_ID_BYTES)
        require(recipient.isNotEmpty() && recipient.size <= MAX_ID_BYTES)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeByte(VERSION)
            d.writeByte(sender.size); d.write(sender)
            d.writeByte(recipient.size); d.write(recipient)
            d.writeLong(agreement.signedPreKeyId)
            d.writeBoolean(agreement.oneTimePreKeyId != null)
            agreement.oneTimePreKeyId?.let(d::writeLong)
            d.write(agreement.ephemeralPublic)
            d.write(encrypted.header)
            d.writeInt(encrypted.body.size)
            d.write(encrypted.body)
        }
        return Initial(out.toByteArray(), encrypted.newState)
    }

    /**
     * The caller must atomically consume the referenced one-time private key and persist
     * [Accepted.state] in one database transaction before acknowledging the message.
     */
    fun accept(
        wire: ByteArray,
        expectedSenderId: String,
        ownId: String,
        senderIdentityDhPublic: ByteArray,
        ownIdentityDhPrivate: ByteArray,
        signedPreKeyId: Long,
        signedPreKeyPrivate: ByteArray,
        resolveOneTimePreKey: (Long) -> ByteArray?,
    ): Accepted? {
        val packet = parse(wire) ?: return null
        if (packet.senderId != expectedSenderId || packet.recipientId != ownId ||
            packet.signedPreKeyId != signedPreKeyId || senderIdentityDhPublic.size != 32) return null
        val oneTimePrivate = packet.oneTimePreKeyId?.let { resolveOneTimePreKey(it) ?: return null }
        return try {
            val shared = X3dh.respond(
                ownIdentityDhPrivate, signedPreKeyPrivate, packet.ephemeralPublic,
                senderIdentityDhPublic, oneTimePrivate, packet.oneTimePreKeyId,
            ).sharedSecret
            val state = DoubleRatchet.initBob(shared, signedPreKeyPrivate)
            val decrypted = DoubleRatchet.decrypt(
                state, packet.ratchetHeader, packet.body,
                DoubleRatchet.aad(expectedSenderId, ownId),
            ) ?: return null
            Accepted(expectedSenderId, decrypted.plaintext, decrypted.newState, packet.oneTimePreKeyId)
        } catch (_: Exception) {
            null
        }
    }

    private data class Parsed(
        val senderId: String,
        val recipientId: String,
        val signedPreKeyId: Long,
        val oneTimePreKeyId: Long?,
        val ephemeralPublic: ByteArray,
        val ratchetHeader: ByteArray,
        val body: ByteArray,
    )

    fun senderId(wire: ByteArray): String? = parse(wire)?.senderId
    fun signedPreKeyId(wire: ByteArray): Long? = parse(wire)?.signedPreKeyId
    fun oneTimePreKeyId(wire: ByteArray): Long? = parse(wire)?.oneTimePreKeyId

    private fun parse(wire: ByteArray): Parsed? = try {
        val b = ByteBuffer.wrap(wire)
        if (b.remaining() < 1 + 1 + 1 + 1 + 1 + 8 + 1 + 32 + DoubleRatchet.HEADER_LEN + 4 + MIN_BODY_BYTES) return null
        if ((b.get().toInt() and 0xFF) != VERSION) return null
        fun readId(): String? {
            if (!b.hasRemaining()) return null
            val length = b.get().toInt() and 0xFF
            if (length == 0 || b.remaining() < length) return null
            val bytes = ByteArray(length).also(b::get)
            return String(bytes, Charsets.UTF_8).takeIf { it.isNotBlank() }
        }
        val sender = readId() ?: return null
        val recipient = readId() ?: return null
        if (b.remaining() < 8 + 1 + 32 + DoubleRatchet.HEADER_LEN + 4 + MIN_BODY_BYTES) return null
        val signedId = b.long
        val oneTimeFlag = b.get().toInt() and 0xFF
        if (oneTimeFlag !in 0..1) return null
        val hasOneTime = oneTimeFlag == 1
        val oneTimeId = if (hasOneTime) {
            if (b.remaining() < 8) return null
            b.long
        } else null
        if (signedId < 0 || (oneTimeId != null && oneTimeId < 0)) return null
        if (b.remaining() < 32 + DoubleRatchet.HEADER_LEN + 4 + MIN_BODY_BYTES) return null
        val ephemeral = ByteArray(32).also(b::get)
        val header = ByteArray(DoubleRatchet.HEADER_LEN).also(b::get)
        val bodyLength = b.int
        if (bodyLength < MIN_BODY_BYTES || bodyLength != b.remaining()) return null
        val body = ByteArray(bodyLength).also(b::get)
        Parsed(sender, recipient, signedId, oneTimeId, ephemeral, header, body)
    } catch (_: Exception) {
        null
    }
}
