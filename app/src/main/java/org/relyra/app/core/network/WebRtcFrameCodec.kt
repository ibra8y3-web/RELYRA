package org.relyra.app.core.network

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Bounded wire framing for the RELYRA WebRTC DataChannel.
 *
 * Message: magic(4), type(1), transfer UUID(16), SHA-256(32), opaque E2EE wire payload.
 * Ack: magic(4), type(1), transfer UUID(16), SHA-256(32), accepted(1).
 */
object WebRtcFrameCodec {
    private const val MAGIC = 0x524c5931 // "RLY1"
    private const val TYPE_MESSAGE: Byte = 1
    private const val TYPE_ACK: Byte = 2
    const val HEADER_BYTES = 4 + 1 + 16 + 32
    const val ACK_BYTES = HEADER_BYTES + 1

    sealed interface Frame {
        data class Message(val transferId: String, val digest: ByteArray, val payload: ByteArray) : Frame
        data class Ack(val transferId: String, val digest: ByteArray, val accepted: Boolean) : Frame
    }

    fun encodeMessage(payload: ByteArray, transferId: String): ByteBuffer? {
        if (payload.isEmpty() || payload.size > WebRtcP2pConnector.MAX_DATA_CHANNEL_MESSAGE - HEADER_BYTES) return null
        val uuid = parseCanonicalUuid(transferId) ?: return null
        val digest = digestPayload(payload)
        val buffer = ByteBuffer.allocate(HEADER_BYTES + payload.size)
        buffer.putInt(MAGIC)
        buffer.put(TYPE_MESSAGE)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        buffer.put(digest)
        buffer.put(payload)
        buffer.flip()
        return buffer
    }

    fun encodeAck(transferId: String, digest: ByteArray, accepted: Boolean): ByteBuffer? {
        val uuid = parseCanonicalUuid(transferId) ?: return null
        if (digest.size != 32) return null
        val buffer = ByteBuffer.allocate(ACK_BYTES)
        buffer.putInt(MAGIC)
        buffer.put(TYPE_ACK)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        buffer.put(digest)
        buffer.put((if (accepted) 1 else 0).toByte())
        buffer.flip()
        return buffer
    }

    fun decode(source: ByteBuffer): Frame? {
        val input = source.slice()
        if (input.remaining() !in ACK_BYTES..WebRtcP2pConnector.MAX_DATA_CHANNEL_MESSAGE) return null
        if (input.int != MAGIC) return null
        val type = input.get()
        val transferId = UUID(input.long, input.long).toString()
        val digest = ByteArray(32).also(input::get)
        return when (type) {
            TYPE_MESSAGE -> {
                if (!input.hasRemaining()) return null
                val payload = ByteArray(input.remaining()).also(input::get)
                if (!MessageDigest.isEqual(digest, digestPayload(payload))) null
                else Frame.Message(transferId, digest, payload)
            }
            TYPE_ACK -> {
                if (input.remaining() != 1) return null
                when (input.get().toInt()) {
                    0 -> Frame.Ack(transferId, digest, false)
                    1 -> Frame.Ack(transferId, digest, true)
                    else -> null
                }
            }
            else -> null
        }
    }

    fun digestPayload(payload: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(payload)

    fun digestKey(digest: ByteArray): String =
        digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun parseCanonicalUuid(value: String): UUID? {
        val parsed = runCatching { UUID.fromString(value) }.getOrNull() ?: return null
        return parsed.takeIf { it.toString() == value.lowercase() }
    }
}
