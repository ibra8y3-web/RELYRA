package org.relyra.app

import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.crypto.PayloadCrypto
import org.relyra.app.core.crypto.X3dh

class X3dhTest {
    private val aliceSeed = ByteArray(32) { (it + 1).toByte() }
    private val bobSeed = ByteArray(32) { (it + 41).toByte() }
    private val now = 1_800_000_000L

    private data class Fixture(
        val bobIdentityDh: ByteArray,
        val signedPreKeyPrivate: ByteArray,
        val bundle: X3dh.PreKeyBundle,
        val oneTime: X3dh.OneTimePreKey?,
    )

    private fun fixture(withOneTime: Boolean): Fixture {
        val bobIdentity = IdentityCrypto.fromSeed(bobSeed)
        val bobDhPrivate = PayloadCrypto.deriveSecret(bobSeed)
        val id = IdentityCrypto.publicId(bobIdentity.publicKey)
        val signed = X3dh.createSignedPreKey(id, bobSeed, 7, now)
        val oneTime = if (withOneTime) X3dh.createOneTimePreKey(19) else null
        val bundle = X3dh.bundle(
            id, bobIdentity.publicKey, X25519.publicFromPrivate(bobDhPrivate), signed, oneTime,
        )
        return Fixture(bobDhPrivate, signed.privateKey, bundle, oneTime)
    }

    @Test fun x3dhWithOneTimePrekeyMatchesOnBothDevices() {
        val f = fixture(true)
        assertTrue(X3dh.verifyBundle(f.bundle, now))
        val aliceDh = PayloadCrypto.deriveSecret(aliceSeed)
        val alice = X3dh.initiate(aliceDh, f.bundle, now)
        val bob = X3dh.respond(
            f.bobIdentityDh, f.signedPreKeyPrivate, alice.ephemeralPublic,
            X25519.publicFromPrivate(aliceDh), f.oneTime!!.privateKey, f.oneTime.id,
        )
        assertArrayEquals(alice.sharedSecret, bob.sharedSecret)
        assertTrue(alice.oneTimePreKeyId == bob.consumedOneTimePreKeyId)
    }

    @Test fun x3dhWithoutOneTimePrekeyMatches() {
        val f = fixture(false)
        val aliceDh = PayloadCrypto.deriveSecret(aliceSeed)
        val alice = X3dh.initiate(aliceDh, f.bundle, now)
        val bob = X3dh.respond(
            f.bobIdentityDh, f.signedPreKeyPrivate, alice.ephemeralPublic,
            X25519.publicFromPrivate(aliceDh),
        )
        assertArrayEquals(alice.sharedSecret, bob.sharedSecret)
    }

    @Test fun signedPrekeyTamperingAndExpiryAreRejected() {
        val f = fixture(false)
        assertTrue(X3dh.verifyBundle(f.bundle, now))
        val changed = f.bundle.copy(
            signedPreKey = f.bundle.signedPreKey.copyOf().also {
                it[0] = (it[0].toInt() xor 1).toByte()
            },
        )
        assertFalse(X3dh.verifyBundle(changed, now))
        assertFalse(X3dh.verifyBundle(f.bundle, f.bundle.signedPreKeyExpiresAt + 1))
    }
    @Test fun initialPacketBootstrapsDoubleRatchetAndDecryptsFirstMessage() {
        val f = fixture(true)
        val aliceIdentity = IdentityCrypto.fromSeed(aliceSeed)
        val aliceId = IdentityCrypto.publicId(aliceIdentity.publicKey)
        val aliceDh = PayloadCrypto.deriveSecret(aliceSeed)
        val plaintext = "first ratcheted message".toByteArray()
        val initial = org.relyra.app.core.crypto.X3dhRatchetBootstrap.initiate(
            aliceId, f.bundle, aliceDh, plaintext, now,
        )
        val accepted = org.relyra.app.core.crypto.X3dhRatchetBootstrap.accept(
            initial.wire,
            aliceId,
            f.bundle.identityId,
            X25519.publicFromPrivate(aliceDh),
            f.bobIdentityDh,
            f.bundle.signedPreKeyId,
            f.signedPreKeyPrivate,
            { id -> if (id == f.oneTime!!.id) f.oneTime.privateKey else null },
        )
        assertTrue(accepted != null)
        assertArrayEquals(plaintext, accepted!!.plaintext)
        val aliceReply = org.relyra.app.core.crypto.DoubleRatchet.encrypt(
            accepted.state, "reply".toByteArray(),
            org.relyra.app.core.crypto.DoubleRatchet.aad(f.bundle.identityId, aliceId),
        )
        assertTrue(aliceReply != null)
        val aliceState = initial.state
        val reply = org.relyra.app.core.crypto.DoubleRatchet.decrypt(
            aliceState, aliceReply!!.header, aliceReply.body,
            org.relyra.app.core.crypto.DoubleRatchet.aad(f.bundle.identityId, aliceId),
        )
        assertArrayEquals("reply".toByteArray(), reply!!.plaintext)
    }

}
