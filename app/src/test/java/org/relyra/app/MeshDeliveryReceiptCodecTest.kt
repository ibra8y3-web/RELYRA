package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.network.MeshDeliveryReceiptCodec
import org.relyra.app.core.network.MeshEnvelopeCodec

class MeshDeliveryReceiptCodecTest {
    @Test
    fun signedReceiptBindsTheOriginalMessageAndBothIdentities() {
        val sender = IdentityCrypto.generate()
        val receiver = IdentityCrypto.generate()
        val senderId = IdentityCrypto.publicId(sender.publicKey)
        val receiverId = IdentityCrypto.publicId(receiver.publicKey)
        val originalId = "a".repeat(64)

        val payload = MeshDeliveryReceiptCodec.encode(originalId, senderId, receiverId)
        val decoded = MeshDeliveryReceiptCodec.decode(payload)

        assertEquals(originalId, decoded?.messageId)
        assertEquals(senderId, decoded?.originId)
        assertEquals(receiverId, decoded?.destinationId)

        val ack = MeshEnvelopeCodec.create(
            "ack-$originalId",
            receiverId,
            senderId,
            payload,
            { message -> IdentityCrypto.sign(receiver.seed, message) },
        )
        assertTrue(ack != null)
        assertTrue(MeshEnvelopeCodec.verify(ack!!))
        assertEquals(senderId, ack.destinationId)
        assertEquals(receiverId, ack.originId)
    }

    @Test
    fun malformedOrTrailingReceiptDataIsRejected() {
        val sender = IdentityCrypto.generate()
        val receiver = IdentityCrypto.generate()
        val payload = MeshDeliveryReceiptCodec.encode(
            "b".repeat(64),
            IdentityCrypto.publicId(sender.publicKey),
            IdentityCrypto.publicId(receiver.publicKey),
        )

        assertNull(MeshDeliveryReceiptCodec.decode(payload + byteArrayOf(0)))
        assertNull(MeshDeliveryReceiptCodec.decode(payload.copyOf(payload.size - 1)))
        assertNull(MeshDeliveryReceiptCodec.decode(ByteArray(0)))
    }
}
