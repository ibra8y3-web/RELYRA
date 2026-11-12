package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.network.StoreForwardEnvelopeCodec

class StoreForwardEnvelopeCodecTest {
    @Test
    fun signedEncryptedEnvelopeRoundTripsAndBindsRecipient() {
        val sender = IdentityCrypto.generate()
        val recipient = IdentityCrypto.generate()
        val senderId = IdentityCrypto.publicId(sender.publicKey)
        val recipientId = IdentityCrypto.publicId(recipient.publicKey)
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val now = 1_800_000_000L

        val encoded = StoreForwardEnvelopeCodec.encode(
            senderId, recipientId, payload, now, now + 3600,
        ) { bytes -> IdentityCrypto.sign(sender.seed, bytes) }
        assertNotNull(encoded)

        val decoded = StoreForwardEnvelopeCodec.decode(encoded!!, recipientId, now + 1)
        assertNotNull(decoded)
        assertEquals(senderId, decoded!!.senderPublicId)
        assertEquals(recipientId, decoded.recipientPublicId)
        assertEquals(payload.toList(), decoded.payload.toList())

        assertNull(StoreForwardEnvelopeCodec.decode(encoded, senderId, now + 1))
        assertNull(StoreForwardEnvelopeCodec.decode(encoded, recipientId, now + 3601))
    }

    @Test
    fun tamperedPayloadAndSignatureAreRejected() {
        val sender = IdentityCrypto.generate()
        val recipient = IdentityCrypto.generate()
        val senderId = IdentityCrypto.publicId(sender.publicKey)
        val recipientId = IdentityCrypto.publicId(recipient.publicKey)
        val now = 1_800_000_000L
        val encoded = StoreForwardEnvelopeCodec.encode(
            senderId, recipientId, byteArrayOf(1, 2, 3), now, now + 60,
        ) { bytes -> IdentityCrypto.sign(sender.seed, bytes) }!!

        assertNull(StoreForwardEnvelopeCodec.decode(encoded.replace("\"digest\":", "\"x-digest\":"), recipientId, now))
        val tampered = encoded.replace("\"payload\":", "\"payload\":")
            .replace("AQID", "AQIE")
        assertNull(StoreForwardEnvelopeCodec.decode(tampered, recipientId, now))
    }
}
