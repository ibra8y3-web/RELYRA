package org.relyra.app

import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.crypto.ContactCardCodec
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.crypto.PayloadCrypto
import org.relyra.app.core.crypto.X3dh

class X3dhContactCardTest {
    @Test fun versionThreeCardCarriesIdentityBoundSignedAndOneTimePrekeys() {
        val seed = ByteArray(32) { (it + 17).toByte() }
        val identity = IdentityCrypto.fromSeed(seed)
        val id = IdentityCrypto.publicId(identity.publicKey)
        val identityDh = PayloadCrypto.deriveSecret(seed)
        val signed = X3dh.createSignedPreKey(id, seed, 42, 1_800_000_000L)
        val oneTime = X3dh.createOneTimePreKey(43)
        val bundle = X3dh.bundle(
            id, identity.publicKey, X25519.publicFromPrivate(identityDh), signed, oneTime,
        )
        val uri = ContactCardCodec.encodeV3(
            "Relyra Test", seed, identity.publicKey, X25519.publicFromPrivate(identityDh),
            ByteArray(32) { 9 }, bundle,
        )
        val decoded = ContactCardCodec.decode(uri) as org.relyra.app.core.crypto.CardDecode.Ok
        val actual = decoded.card.preKeyBundle!!
        assertEquals(42L, actual.signedPreKeyId)
        assertEquals(43L, actual.oneTimePreKeyId)
        assertTrue(X3dh.verifyBundle(actual, 1_800_000_000L))
    }

    @Test fun versionThreeCardRejectsChangesToPrekeyFields() {
        val seed = ByteArray(32) { (it + 31).toByte() }
        val identity = IdentityCrypto.fromSeed(seed)
        val id = IdentityCrypto.publicId(identity.publicKey)
        val identityDh = PayloadCrypto.deriveSecret(seed)
        val signed = X3dh.createSignedPreKey(id, seed, 77, 1_800_000_000L)
        val bundle = X3dh.bundle(id, identity.publicKey, X25519.publicFromPrivate(identityDh), signed)
        val uri = ContactCardCodec.encodeV3(
            "Relyra Test", seed, identity.publicKey, X25519.publicFromPrivate(identityDh),
            ByteArray(32) { 7 }, bundle,
        )
        val changed = uri.replace("&pi=77", "&pi=78")
        assertTrue(ContactCardCodec.decode(changed) is org.relyra.app.core.crypto.CardDecode.BadSignature)
    }
}
