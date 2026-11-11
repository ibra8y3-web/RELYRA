package org.relyra.app.core.network

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Bounded wire framing for the RELYRA WebRTC DataChannel.
 *
 * Message frame: magic(4), type(1), SHA-256(32), opaque E2EE wire payload.
 * Acknowledgement: magic(4), type(1), SHA-256(32), accepted(1).
 */
object WebRtcFrameCodec {
    private const val MAGIC = 0x524c5931 // "RLY1"
    private const val TYPE_MESSAGE: Byte = 1
    private const val TYPE_ACK: Byte = 2
    const val HEADER_BYTES = 4 + 1 + 32
    const val ACK_BYTES = HEADER_BYTES + 1

    sealed interface Frame {
        data class Message(val digest: ByteArray, val payload: ByteArray) : Frame
        data class Ack(val digest: ByteArray, val accepted: Boolean) : Frame
    }

    fun encodeMessage(payload: ByteArray): ByteBuffer? {
        if (payload.isEmpty() || payload.size > WebRtcP2pConnector.MAX_DATA_CHANNEL_MESSAGE - HEADER_BYTES) return null
        val digest = digestPayload(payload)
        return ByteBuffer.allocate(HEADER_BYTES + payload.size)
            .putInt(MAGIC)
            .put(TYPE_MESSAGE)
            .put(digest)
            .put(payload)
            .flip()
    }

    fun encodeAck(digest: ByteArray, accepted: Boolean): ByteBuffer? {
        if (digest.size != 32) return null
        return ByteBuffer.allocate(ACK_BYTES)
            .putInt(MAGIC)
            .put(TYPE_ACK)
            .put(digest)
            .put((if (accepted) 1 else 0).toByte())
            .flip()
    }

    fun decode(source: ByteBuffer): Frame? {
        val input = source.slice()
        if (input.remaining() !in ACK_BYTES..WebRtcP2pConnector.MAX_DATA_CHANNEL_MESSAGE) return null
        if (input.int != MAGIC) return null
        val type = input.get()
        val digest = ByteArray(32).also(input::get)
        return when (type) {
            TYPE_MESSAGE -> {
                if (!input.hasRemaining()) return null
                val payload = ByteArray(input.remaining()).also(input::get)
                if (!MessageDigest.isEqual(digest, sha256(payload))) null else Frame.Message(digest, payload)
            }
            TYPE_ACK -> {
                if (input.remaining() != 1) return null
                when (val value = input.get().toInt()) {
                    0 -> Frame.Ack(digest, false)
                    1 -> Frame.Ack(digest, true)
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
}
