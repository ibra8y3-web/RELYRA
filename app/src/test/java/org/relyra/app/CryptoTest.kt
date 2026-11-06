package org.relyra.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.crypto.Base32
import org.relyra.app.core.crypto.CardDecode
import org.relyra.app.core.crypto.ContactCardCodec
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.crypto.PayloadCrypto

class Base32Test {
    @Test fun rfc4648Vector() {
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray()))
        assertArrayEquals("foobar".toByteArray(), Base32.decode("MZXW6YTBOI"))
    }

    @Test fun rejectsInvalidCharacters() {
        assertNull(Base32.decode("AB1!"))
    }
}

class IdentityCryptoTest {
    @Test fun signAndVerify() {
        val k = IdentityCrypto.generate()
        val msg = "hello".toByteArray()
        val sig = IdentityCrypto.sign(k.seed, msg)
        assertTrue(IdentityCrypto.verify(k.publicKey, sig, msg))
        assertFalse(IdentityCrypto.verify(k.publicKey, sig, "hellp".toByteArray()))
    }

    @Test fun seedIsDeterministic() {
        val k = IdentityCrypto.generate()
        assertArrayEquals(k.publicKey, IdentityCrypto.fromSeed(k.seed).publicKey)
    }

    @Test fun publicIdRoundTrip() {
        val k = IdentityCrypto.generate()
        val id = IdentityCrypto.publicId(k.publicKey)
        assertTrue(id.startsWith("rly1"))
        assertArrayEquals(k.publicKey, IdentityCrypto.parsePublicId(id))
    }

    @Test fun publicIdChecksumCatchesTypos() {
        val id = IdentityCrypto.publicId(IdentityCrypto.generate().publicKey)
        val last = if (id.last() == 'a') 'b' else 'a'
        assertNull(IdentityCrypto.parsePublicId(id.dropLast(1) + last))
        assertNull(IdentityCrypto.parsePublicId("rly1abc"))
    }

    @Test fun recoveryKeyRoundTrip() {
        val k = IdentityCrypto.generate()
        val text = IdentityCrypto.recoveryKey(k.seed)
        assertArrayEquals(k.seed, IdentityCrypto.parseRecoveryKey(text))
        assertArrayEquals(k.seed, IdentityCrypto.parseRecoveryKey(text.lowercase().replace("-", " ")))
    }

    @Test fun recoveryKeyChecksumCatchesTypos() {
        val text = IdentityCrypto.recoveryKey(IdentityCrypto.generate().seed)
        val first = text[0]
        val altered = (if (first == 'A') 'B' else 'A') + text.drop(1)
        assertNull(IdentityCrypto.parseRecoveryKey(altered))
    }

    @Test fun fingerprintFormat() {
        val fp = IdentityCrypto.fingerprint(IdentityCrypto.generate().publicKey)
        assertEquals(6, fp.split(" ").size)
    }
}

private val X = ByteArray(32) { 1 }
private val N = ByteArray(32) { 2 }

class ContactCardTest {
    @Test fun roundTrip() {
        val k = IdentityCrypto.generate()
        val uri = ContactCardCodec.encode("Ahmed Ali", k.seed, k.publicKey, X, N)
        val r = ContactCardCodec.decode(uri)
        assertTrue(r is CardDecode.Ok)
        r as CardDecode.Ok
        assertEquals("Ahmed Ali", r.card.name)
        assertArrayEquals(X, r.card.encKey)
        assertArrayEquals(N, r.card.nostrKey)
        assertArrayEquals(k.publicKey, r.card.publicKey)
    }

    @Test fun arabicNameRoundTrip() {
        val k = IdentityCrypto.generate()
        val r = ContactCardCodec.decode(ContactCardCodec.encode("أحمد علي", k.seed, k.publicKey, X, N))
        assertTrue(r is CardDecode.Ok)
        assertEquals("أحمد علي", (r as CardDecode.Ok).card.name)
    }

    @Test fun tamperedNameFailsSignature() {
        val k = IdentityCrypto.generate()
        val uri = ContactCardCodec.encode("Alice", k.seed, k.publicKey, X, N).replace("n=Alice", "n=Malice")
        assertEquals(CardDecode.BadSignature, ContactCardCodec.decode(uri))
    }

    @Test fun swappedKeyFailsSignature() {
        val a = IdentityCrypto.generate()
        val b = IdentityCrypto.generate()
        val uriA = ContactCardCodec.encode("Alice", a.seed, a.publicKey, X, N)
        val uriB = ContactCardCodec.encode("Alice", b.seed, b.publicKey, X, N)
        val keyB = uriB.substringAfter("&k=").substringBefore("&x=")
        val keyA = uriA.substringAfter("&k=").substringBefore("&x=")
        assertEquals(CardDecode.BadSignature, ContactCardCodec.decode(uriA.replace(keyA, keyB)))
    }

    @Test fun malformedInputs() {
        assertEquals(CardDecode.Malformed, ContactCardCodec.decode("https://example.com"))
        assertEquals(CardDecode.Malformed, ContactCardCodec.decode("relyra://contact?v=1&n=x"))
        assertEquals(CardDecode.Malformed, ContactCardCodec.decode("relyra://contact?v=3&n=x&k=a&s=b"))
        assertEquals(CardDecode.Malformed, ContactCardCodec.decode("relyra://contact?v=2&n=x&k=a&s=b"))
        assertNotNull(ContactCardCodec.decode("relyra://contact?"))
    }
}

class PayloadCryptoTest {
    private val aliceSecret = PayloadCrypto.deriveSecret(ByteArray(32) { 1 })
    private val bobSecret = PayloadCrypto.deriveSecret(ByteArray(32) { 2 })
    private val alicePub = PayloadCrypto.publicKey(aliceSecret)
    private val bobPub = PayloadCrypto.publicKey(bobSecret)

    private fun open(wire: ByteArray, ownId: String, own: ByteArray, senderPub: ByteArray): ByteArray? =
        PayloadCrypto.parseHeader(wire)?.let { PayloadCrypto.open(wire, it, ownId, own, senderPub) }

    @Test fun roundTrip() {
        val msg = "مرحبا 👋 hello".toByteArray()
        val wire = PayloadCrypto.seal("alice", aliceSecret, "bob", bobPub, msg)
        assertEquals("alice", PayloadCrypto.parseHeader(wire)!!.senderId)
        assertArrayEquals(msg, open(wire, "bob", bobSecret, alicePub))
    }

    @Test fun twoSealsOfSameMessageDiffer() {
        val a = PayloadCrypto.seal("alice", aliceSecret, "bob", bobPub, "x".toByteArray())
        val b = PayloadCrypto.seal("alice", aliceSecret, "bob", bobPub, "x".toByteArray())
        assertFalse(a.contentEquals(b))
    }

    @Test fun wrongRecipientCannotOpen() {
        val wire = PayloadCrypto.seal("alice", aliceSecret, "bob", bobPub, "secret".toByteArray())
        val carol = PayloadCrypto.deriveSecret(ByteArray(32) { 3 })
        assertNull(open(wire, "bob", carol, alicePub))
        assertNull(open(wire, "carol", bobSecret, alicePub))      // recipient ID is bound into the AAD
    }

    @Test fun impersonationFails() {
        val mallory = PayloadCrypto.deriveSecret(ByteArray(32) { 9 })
        val forged = PayloadCrypto.seal("alice", mallory, "bob", bobPub, "pay me".toByteArray())
        assertNull(open(forged, "bob", bobSecret, alicePub))      // claims to be Alice, but is not her key
    }

    @Test fun tamperingIsDetected() {
        val wire = PayloadCrypto.seal("alice", aliceSecret, "bob", bobPub, "hello".toByteArray())
        for (i in listOf(0, 3, wire.size / 2, wire.size - 1)) {
            val bad = wire.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", open(bad, "bob", bobSecret, alicePub))
        }
        assertNull(PayloadCrypto.parseHeader(ByteArray(10)))
    }
}
