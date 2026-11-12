package org.relyra.app.core.messaging

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

data class GroupTextPayload(
    val messageId: String,
    val sentAt: Long,
    val body: String,
)

/**
 * Plaintext format inside MLS ApplicationData. The entire frame is encrypted
 * once by MLS, and contains a stable application message ID for replay control.
 */
object GroupPayloadCodec {
    private const val MAGIC_1 = 0x52
    private const val MAGIC_2 = 0x47
    private const val VERSION = 1
    private const val TEXT = 1
    private const val MAX_BODY = EnvelopeCodec.MAX_BODY
    private const val MAX_FRAME = 16 * 1024

    fun encodeText(messageId: String, sentAt: Long, body: String): ByteArray {
        UUID.fromString(messageId)
        require(sentAt > 0 && body.isNotBlank() && body.length <= MAX_BODY)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(MAGIC_1)
            out.writeByte(MAGIC_2)
            out.writeByte(VERSION)
            out.writeByte(TEXT)
            out.writeUTF(messageId)
            out.writeLong(sentAt)
            out.writeUTF(body)
        }
        val encoded = bytes.toByteArray()
        require(encoded.size <= MAX_FRAME)
        return encoded
    }

    fun decodeText(bytes: ByteArray): GroupTextPayload? {
        if (bytes.isEmpty() || bytes.size > MAX_FRAME) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readUnsignedByte() != MAGIC_1 || input.readUnsignedByte() != MAGIC_2) return null
                if (input.readUnsignedByte() != VERSION || input.readUnsignedByte() != TEXT) return null
                val id = input.readUTF()
                UUID.fromString(id)
                val sentAt = input.readLong()
                val body = input.readUTF()
                if (sentAt <= 0 || body.isBlank() || body.length > MAX_BODY || input.available() != 0) return null
                GroupTextPayload(id, sentAt, body)
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
