package org.relyra.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.relyra.app.core.messaging.AttachmentLimits
import org.relyra.app.core.messaging.GroupAttachmentData
import org.relyra.app.core.messaging.GroupFilePayload
import org.relyra.app.core.messaging.GroupPayloadCodec
import org.relyra.app.core.messaging.GroupTextPayload
import java.util.UUID

class GroupAttachmentPayloadTest {
    private fun attachment() = GroupAttachmentData(
        name = "document.pdf",
        mime = "application/pdf",
        size = 10_000,
        blobSize = 10_100,
        plainSha256 = ByteArray(32) { 1 },
        blobSha256 = ByteArray(32) { 2 },
        fileKey = ByteArray(32) { 3 },
        serverUrl = "https://files.example.test",
    )

    @Test fun textPayloadRoundTripsAndRejectsTrailingBytes() {
        val id = UUID.randomUUID().toString()
        val encoded = GroupPayloadCodec.encodeText(id, 1_800_000_000_000L, "رسالة داخل MLS")
        val decoded = GroupPayloadCodec.decode(encoded) as GroupTextPayload
        assertEquals(id, decoded.messageId)
        assertEquals("رسالة داخل MLS", decoded.body)
        assertNull(GroupPayloadCodec.decode(encoded + byteArrayOf(0)))
    }

    @Test fun fileDescriptorRoundTripsHashesKeyAndServerLocation() {
        val id = UUID.randomUUID().toString()
        val file = attachment()
        val encoded = GroupPayloadCodec.encodeFile(id, 1_800_000_000_000L, file)
        val decoded = GroupPayloadCodec.decode(encoded) as GroupFilePayload
        assertEquals(id, decoded.messageId)
        assertEquals(file.name, decoded.file.name)
        assertEquals(file.mime, decoded.file.mime)
        assertEquals(file.serverUrl, decoded.file.serverUrl)
        assertEquals(file.size, decoded.file.size)
        assertEquals(file.blobSize, decoded.file.blobSize)
        assertArrayEquals(file.plainSha256, decoded.file.plainSha256)
        assertArrayEquals(file.blobSha256, decoded.file.blobSha256)
        assertArrayEquals(file.fileKey, decoded.file.fileKey)
    }

    @Test fun fileDescriptorRejectsInvalidDigestsAndSizes() {
        val id = UUID.randomUUID().toString()
        val valid = attachment()
        try {
            GroupPayloadCodec.encodeFile(id, 1_800_000_000_000L, valid.copy(plainSha256 = ByteArray(16)))
            throw AssertionError("Short digest was accepted")
        } catch (_: IllegalArgumentException) {
            // Expected: exactly 32 bytes per SHA-256 digest and file key.
        }
        try {
            GroupPayloadCodec.encodeFile(id, 1_800_000_000_000L, valid.copy(blobSize = AttachmentLimits.MAX_BYTES + 1))
            throw AssertionError("Invalid blob length was accepted")
        } catch (_: IllegalArgumentException) {
            // Expected: encrypted size must be consistent with the plaintext limit.
        }
    }
}
