package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.network.IceServerConfig
import org.relyra.app.core.network.InternetSignalCodec

class InternetSignalCodecTest {
    private val aliceSeed = ByteArray(32) { (it + 1).toByte() }
    private val bobSeed = ByteArray(32) { (it + 33).toByte() }
    private val alice = IdentityCrypto.fromSeed(aliceSeed)
    private val bob = IdentityCrypto.fromSeed(bobSeed)
    private val aliceId = IdentityCrypto.publicId(alice.publicKey)
    private val bobId = IdentityCrypto.publicId(bob.publicKey)

    @Test fun signedOfferIsBoundToRecipientAndIdentity() {
        val now = 1_800_000_000L
        val card = InternetSignalCodec.encode(aliceId, bobId, "offer", "v=0\r\no=- test", now, "11111111-1111-4111-8111-111111111111") {
            IdentityCrypto.sign(alice.seed, it)
        }
        assertNotNull(card)
        val decoded = InternetSignalCodec.decode(card!!, bobId, aliceId, now)
        assertNotNull(decoded)
        assertEquals("offer", decoded!!.type)
        assertEquals(aliceId, decoded.peerId)
        assertEquals("11111111-1111-4111-8111-111111111111", decoded.sessionId)
        assertNull(InternetSignalCodec.decode(card, aliceId, aliceId, now))
    }

    @Test fun signatureTamperingIsRejected() {
        val now = 1_800_000_000L
        val card = InternetSignalCodec.encode(aliceId, bobId, "answer", "v=0\r\no=- test", now, "22222222-2222-4222-8222-222222222222") {
            IdentityCrypto.sign(alice.seed, it)
        }!!
        val changed = card.replace("v=0", "v=1")
        assertNull(InternetSignalCodec.decode(changed, bobId, aliceId, now))
    }

    @Test fun sessionIdIsBoundToTheSignature() {
        val now = 1_800_000_000L
        val card = InternetSignalCodec.encode(
            aliceId, bobId, "offer", "v=0", now,
            "55555555-5555-4555-8555-555555555555",
        ) { IdentityCrypto.sign(alice.seed, it) }!!
        val tampered = card.replace(
            "55555555-5555-4555-8555-555555555555",
            "66666666-6666-4666-8666-666666666666",
        )
        assertNull(InternetSignalCodec.decode(tampered, bobId, aliceId, now))
    }

    @Test fun expiredAndFutureSignalsAreRejected() {
        val now = 1_800_000_000L
        val card = InternetSignalCodec.encode(aliceId, bobId, "offer", "v=0", now - 601, "33333333-3333-4333-8333-333333333333") {
            IdentityCrypto.sign(alice.seed, it)
        }!!
        assertNull(InternetSignalCodec.decode(card, bobId, aliceId, now))
        val future = InternetSignalCodec.encode(aliceId, bobId, "offer", "v=0", now + 61, "44444444-4444-4444-8444-444444444444") {
            IdentityCrypto.sign(alice.seed, it)
        }!!
        assertNull(InternetSignalCodec.decode(future, bobId, aliceId, now))
    }

    @Test fun onlyValidIceServerCredentialsAreAccepted() {
        assertTrue(IceServerConfig("stun:stun.example.org:3478").isValid())
        assertTrue(IceServerConfig("turns:turn.example.org:5349", "user", "secret").isValid())
        assertFalse(IceServerConfig("turn:turn.example.org:3478").isValid())
        assertFalse(IceServerConfig("https://turn.example.org", "user", "secret").isValid())
    }
}
