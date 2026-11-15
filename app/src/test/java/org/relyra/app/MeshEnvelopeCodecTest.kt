package org.relyra.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.network.MeshEnvelopeCodec

class MeshEnvelopeCodecTest {
    @Test
    fun originAndChainedHopSignaturesAuthenticateTheTransitPath() {
        val origin = IdentityCrypto.generate()
        val destination = IdentityCrypto.generate()
        val relayOne = IdentityCrypto.generate()
        val relayTwo = IdentityCrypto.generate()
        val originId = IdentityCrypto.publicId(origin.publicKey)
        val destinationId = IdentityCrypto.publicId(destination.publicKey)
        val relayOneId = IdentityCrypto.publicId(relayOne.publicKey)
        val relayTwoId = IdentityCrypto.publicId(relayTwo.publicKey)
        val now = 1_000_000L

        val envelope = MeshEnvelopeCodec.create(
            "message-1",
            originId,
            destinationId,
            byteArrayOf(1, 2, 3, 4),
            { message -> IdentityCrypto.sign(origin.seed, message) },
            now,
        )!!
        assertTrue(MeshEnvelopeCodec.verify(envelope, now))

        val firstHop = MeshEnvelopeCodec.appendHop(envelope, relayOneId) { message ->
            IdentityCrypto.sign(relayOne.seed, message)
        }!!
        val secondHop = MeshEnvelopeCodec.appendHop(firstHop, relayTwoId) { message ->
            IdentityCrypto.sign(relayTwo.seed, message)
        }!!
        assertTrue(MeshEnvelopeCodec.verify(secondHop, now))
        assertNull(MeshEnvelopeCodec.appendHop(secondHop, relayOneId) { message ->
            IdentityCrypto.sign(relayOne.seed, message)
        })
        assertNull(MeshEnvelopeCodec.appendHop(secondHop, originId) { message ->
            IdentityCrypto.sign(origin.seed, message)
        })
    }

    @Test
    fun payloadTamperingAndExpiredTransitAreRejected() {
        val origin = IdentityCrypto.generate()
        val destination = IdentityCrypto.generate()
        val originId = IdentityCrypto.publicId(origin.publicKey)
        val destinationId = IdentityCrypto.publicId(destination.publicKey)
        val now = 2_000_000L
        val envelope = MeshEnvelopeCodec.create(
            "message-2",
            originId,
            destinationId,
            byteArrayOf(8, 7, 6),
            { message -> IdentityCrypto.sign(origin.seed, message) },
            now,
        )!!

        val changed = envelope.copy(payload = byteArrayOf(8, 7, 5))
        assertFalse(MeshEnvelopeCodec.verify(changed, now))
        assertFalse(MeshEnvelopeCodec.verify(envelope, envelope.expiresAt + 1))
    }

    @Test
    fun payloadAndHopBoundsAreEnforcedBeforeForwarding() {
        val origin = IdentityCrypto.generate()
        val destination = IdentityCrypto.generate()
        val relay = IdentityCrypto.generate()
        val originId = IdentityCrypto.publicId(origin.publicKey)
        val destinationId = IdentityCrypto.publicId(destination.publicKey)
        val relayId = IdentityCrypto.publicId(relay.publicKey)

        assertNull(MeshEnvelopeCodec.create(
            "too-large",
            originId,
            destinationId,
            ByteArray(MeshEnvelopeCodec.MAX_PAYLOAD_BYTES + 1),
            { message -> IdentityCrypto.sign(origin.seed, message) },
        ))

        var envelope = MeshEnvelopeCodec.create(
            "hop-limit",
            originId,
            destinationId,
            byteArrayOf(1),
            { message -> IdentityCrypto.sign(origin.seed, message) },
        )!!
        repeat(MeshEnvelopeCodec.MAX_HOPS) { index ->
            val signer = IdentityCrypto.generate()
            val id = IdentityCrypto.publicId(signer.publicKey)
            envelope = MeshEnvelopeCodec.appendHop(envelope, id) { message ->
                IdentityCrypto.sign(signer.seed, message)
            }!!
            assertTrue("hop ${index + 1} should remain valid", MeshEnvelopeCodec.verify(envelope))
        }
        assertNull(MeshEnvelopeCodec.appendHop(envelope, relayId) { message ->
            IdentityCrypto.sign(relay.seed, message)
        })
    }
}
