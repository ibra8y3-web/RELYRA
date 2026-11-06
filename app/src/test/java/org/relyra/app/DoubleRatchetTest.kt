package org.relyra.app

import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.crypto.DoubleRatchet
import org.relyra.app.core.crypto.RatchetState
import org.relyra.app.core.crypto.RatchetWire

class DoubleRatchetTest {
    private val aliceSecret = X25519.generatePrivateKey()
    private val bobSecret = X25519.generatePrivateKey()
    private val aad = DoubleRatchet.aad("alice", "bob")
    private val aadBack = DoubleRatchet.aad("bob", "alice")

    private class Pair2(var alice: RatchetState, var bob: RatchetState)

    private fun session(): Pair2 {
        val shared = DoubleRatchet.sharedSecret(aliceSecret, X25519.publicFromPrivate(bobSecret), "alice", "bob")
        val sharedBob = DoubleRatchet.sharedSecret(bobSecret, X25519.publicFromPrivate(aliceSecret), "alice", "bob")
        assertArrayEquals(shared, sharedBob)
        return Pair2(
            DoubleRatchet.initAlice(shared, X25519.publicFromPrivate(bobSecret)),
            DoubleRatchet.initBob(sharedBob, bobSecret),
        )
    }

    private fun aliceSend(p: Pair2, text: String): DoubleRatchet.Encrypted {
        val e = DoubleRatchet.encrypt(p.alice, text.toByteArray(), aad)!!
        p.alice = e.newState
        return e
    }

    private fun bobSend(p: Pair2, text: String): DoubleRatchet.Encrypted {
        val e = DoubleRatchet.encrypt(p.bob, text.toByteArray(), aadBack)!!
        p.bob = e.newState
        return e
    }

    private fun bobReceive(p: Pair2, e: DoubleRatchet.Encrypted): String? {
        val d = DoubleRatchet.decrypt(p.bob, e.header, e.body, aad) ?: return null
        p.bob = d.newState
        return String(d.plaintext)
    }

    private fun aliceReceive(p: Pair2, e: DoubleRatchet.Encrypted): String? {
        val d = DoubleRatchet.decrypt(p.alice, e.header, e.body, aadBack) ?: return null
        p.alice = d.newState
        return String(d.plaintext)
    }

    @Test fun bobCannotSendRatchetedMessagesBeforeHearingFromAlice() {
        val p = session()
        assertFalse(p.bob.canSend)
        assertNull(DoubleRatchet.encrypt(p.bob, "hi".toByteArray(), aadBack))
        assertTrue(p.alice.canSend)
    }

    @Test fun conversationInBothDirectionsWithArabicText() {
        val p = session()
        assertEquals("السلام عليكم", bobReceive(p, aliceSend(p, "السلام عليكم")))
        assertEquals("وعليكم السلام", aliceReceive(p, bobSend(p, "وعليكم السلام")))
        repeat(20) { i ->
            assertEquals("a$i", bobReceive(p, aliceSend(p, "a$i")))
            assertEquals("b$i", aliceReceive(p, bobSend(p, "b$i")))
        }
    }

    @Test fun identicalPlaintextNeverProducesIdenticalCiphertext() {
        val p = session()
        val a = aliceSend(p, "same")
        val b = aliceSend(p, "same")
        assertFalse(a.body.contentEquals(b.body))
    }

    @Test fun outOfOrderMessagesAreDecrypted() {
        val p = session()
        val m1 = aliceSend(p, "1"); val m2 = aliceSend(p, "2"); val m3 = aliceSend(p, "3")
        assertEquals("3", bobReceive(p, m3))
        assertEquals("1", bobReceive(p, m1))
        assertEquals("2", bobReceive(p, m2))
    }

    @Test fun outOfOrderAcrossARatchetStep() {
        val p = session()
        val a1 = aliceSend(p, "a1"); val a2 = aliceSend(p, "a2")
        assertEquals("a1", bobReceive(p, a1))
        val b1 = bobSend(p, "b1")
        assertEquals("b1", aliceReceive(p, b1))
        val a3 = aliceSend(p, "a3")                      // new ratchet key
        assertEquals("a3", bobReceive(p, a3))
        assertEquals("a2", bobReceive(p, a2))            // late message of the previous chain
    }

    @Test fun replayedMessageIsRejected() {
        val p = session()
        val m = aliceSend(p, "once")
        assertEquals("once", bobReceive(p, m))
        assertNull(bobReceive(p, m))
    }

    @Test fun forgedOrTamperedMessageIsRejectedAndDoesNotDamageTheSession() {
        val p = session()
        val m1 = aliceSend(p, "first")
        assertEquals("first", bobReceive(p, m1))
        val m2 = aliceSend(p, "second")
        val flippedBody = m2.body.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(bobReceive(p, DoubleRatchet.Encrypted(m2.header, flippedBody, m2.newState)))
        val flippedHeader = m2.header.copyOf().also { it[39] = (it[39].toInt() xor 1).toByte() }
        assertNull(bobReceive(p, DoubleRatchet.Encrypted(flippedHeader, m2.body, m2.newState)))
        assertEquals("second", bobReceive(p, m2))        // the genuine one still works
    }

    @Test fun messageIsBoundToSenderAndRecipient() {
        val p = session()
        val m = aliceSend(p, "private")
        val wrong = DoubleRatchet.decrypt(p.bob, m.header, m.body, DoubleRatchet.aad("alice", "carol"))
        assertNull(wrong)
    }

    @Test fun wrongLongTermKeyCannotDecrypt() {
        val p = session()
        val m = aliceSend(p, "secret")
        val mallory = X25519.generatePrivateKey()
        val sharedM = DoubleRatchet.sharedSecret(mallory, X25519.publicFromPrivate(aliceSecret), "alice", "bob")
        val state = DoubleRatchet.initBob(sharedM, mallory)
        assertNull(DoubleRatchet.decrypt(state, m.header, m.body, aad))
    }

    @Test fun gapLargerThanTheLimitIsRefused() {
        val p = session()
        var last: DoubleRatchet.Encrypted? = null
        repeat(DoubleRatchet.MAX_SKIP + 3) { last = aliceSend(p, "x") }
        assertNull(bobReceive(p, last!!))
    }

    @Test fun losingMessagesInTheMiddleStillWorks() {
        val p = session()
        aliceSend(p, "lost1"); aliceSend(p, "lost2")
        assertEquals("kept", bobReceive(p, aliceSend(p, "kept")))
    }

    @Test fun stateSurvivesSerialization() {
        val p = session()
        assertEquals("1", bobReceive(p, aliceSend(p, "1")))
        val late = aliceSend(p, "late")
        assertEquals("3", bobReceive(p, aliceSend(p, "3")))    // leaves "late" as a skipped key
        val bobAgain = DoubleRatchet.deserialize(DoubleRatchet.serialize(p.bob))!!
        val aliceAgain = DoubleRatchet.deserialize(DoubleRatchet.serialize(p.alice))!!
        p.bob = bobAgain; p.alice = aliceAgain
        assertEquals("late", bobReceive(p, late))
        assertEquals("reply", aliceReceive(p, bobSend(p, "reply")))
        assertEquals("next", bobReceive(p, aliceSend(p, "next")))
    }

    @Test fun corruptStateIsRejectedWithoutThrowing() {
        assertNull(DoubleRatchet.deserialize(ByteArray(0)))
        assertNull(DoubleRatchet.deserialize(byteArrayOf(9, 1, 2, 3)))
        val good = DoubleRatchet.serialize(session().alice)
        assertNull(DoubleRatchet.deserialize(good.copyOf(good.size - 5)))
    }

    @Test fun ratchetKeysChangeAfterEachRoundTrip() {
        val p = session()
        val first = aliceSend(p, "1")
        bobReceive(p, first)
        aliceReceive(p, bobSend(p, "2"))
        val second = aliceSend(p, "3")
        assertFalse(first.header.copyOf(32).contentEquals(second.header.copyOf(32)))
    }

    @Test fun wireFormatRoundTripAndGarbage() {
        val p = session()
        val m = aliceSend(p, "wire")
        val wire = RatchetWire.pack("rly1abc", m.header, m.body)
        val parsed = RatchetWire.parse(wire)
        assertNotNull(parsed)
        assertEquals("rly1abc", parsed!!.senderId)
        assertArrayEquals(m.header, parsed.header)
        assertArrayEquals(m.body, parsed.body)
        assertNull(RatchetWire.parse(ByteArray(0)))
        assertNull(RatchetWire.parse(byteArrayOf(2, 0)))
        assertNull(RatchetWire.parse(wire.copyOf(30)))
        assertNull(RatchetWire.parse(byteArrayOf(1) + wire.copyOfRange(1, wire.size)))
    }
}
