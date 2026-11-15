package org.relyra.app.core.messaging

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

sealed interface GroupApplicationPayload {
    val messageId: String
    val sentAt: Long
}

data class GroupTextPayload(
    override val messageId: String,
    override val sentAt: Long,
    val body: String,
) : GroupApplicationPayload

data class GroupFilePayload(
    override val messageId: String,
    override val sentAt: Long,
    val file: GroupAttachmentData,
) : GroupApplicationPayload

/**
 * Plaintext format inside MLS ApplicationData. The whole frame, including the
 * file key and Blossom URL, is encrypted by MLS before it reaches transports.
 */
object GroupPayloadCodec {
    private const val MAGIC_1 = 0x52
    private const val MAGIC_2 = 0x47
    private const val VERSION = 1
    private const val TEXT = 1
    private const val FILE = 2
    private const val MAX_BODY = EnvelopeCodec.MAX_BODY
    private const val MAX_FRAME = 48 * 1024

    fun encodeText(messageId: String, sentAt: Long, body: String): ByteArray {
        validateCommon(messageId, sentAt)
        require(body.isNotBlank() && body.length <= MAX_BODY)
        return frame { out ->
            out.writeByte(TEXT)
            out.writeUTF(messageId)
            out.writeLong(sentAt)
            out.writeUTF(body)
        }
    }

    fun encodeFile(messageId: String, sentAt: Long, file: GroupAttachmentData): ByteArray {
        validateCommon(messageId, sentAt)
        require(file.name.isNotBlank() && file.name.length <= AttachmentLimits.MAX_NAME)
        require(file.mime.length <= AttachmentLimits.MAX_MIME)
        require(file.serverUrl.isNotBlank() && file.serverUrl.length <= AttachmentLimits.MAX_URL)
        require(file.plainSha256.size == 32 && file.blobSha256.size == 32 && file.fileKey.size == 32)
        require(AttachmentLimits.validSizes(file.size, file.blobSize))
        return frame { out ->
            out.writeByte(FILE)
            out.writeUTF(messageId)
            out.writeLong(sentAt)
            out.writeUTF(file.name)
            out.writeUTF(file.mime)
            out.writeLong(file.size)
            out.writeLong(file.blobSize)
            out.write(file.plainSha256)
            out.write(file.blobSha256)
            out.write(file.fileKey)
            out.writeUTF(file.serverUrl)
        }
    }

    fun decode(bytes: ByteArray): GroupApplicationPayload? {
        if (bytes.isEmpty() || bytes.size > MAX_FRAME) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readUnsignedByte() != MAGIC_1 || input.readUnsignedByte() != MAGIC_2) return null
                if (input.readUnsignedByte() != VERSION) return null
                val type = input.readUnsignedByte()
                val id = input.readUTF()
                UUID.fromString(id)
                val sentAt = input.readLong()
                if (sentAt <= 0) return null
                val result = when (type) {
                    TEXT -> {
                        val body = input.readUTF()
                        if (body.isBlank() || body.length > MAX_BODY) return null
                        GroupTextPayload(id, sentAt, body)
                    }
                    FILE -> {
                        val name = input.readUTF()
                        val mime = input.readUTF()
                        val size = input.readLong()
                        val blobSize = input.readLong()
                        val plainHash = ByteArray(32).also { input.readFully(it) }
                        val blobHash = ByteArray(32).also { input.readFully(it) }
                        val key = ByteArray(32).also { input.readFully(it) }
                        val url = input.readUTF()
                        if (name.isBlank() || name.length > AttachmentLimits.MAX_NAME ||
                            mime.length > AttachmentLimits.MAX_MIME || url.isBlank() || url.length > AttachmentLimits.MAX_URL ||
                            !AttachmentLimits.validSizes(size, blobSize)
                        ) return null
                        GroupFilePayload(id, sentAt, GroupAttachmentData(name, mime, size, blobSize, plainHash, blobHash, key, url))
                    }
                    else -> return null
                }
                if (input.available() != 0) return null
                result
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun decodeText(bytes: ByteArray): GroupTextPayload? = decode(bytes) as? GroupTextPayload

    private fun validateCommon(messageId: String, sentAt: Long) {
        UUID.fromString(messageId)
        require(sentAt > 0)
    }

    private inline fun frame(write: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(MAGIC_1)
            out.writeByte(MAGIC_2)
            out.writeByte(VERSION)
            write(out)
        }
        return bytes.toByteArray().also { require(it.size <= MAX_FRAME) }
    }
}
