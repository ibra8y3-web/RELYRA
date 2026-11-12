package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.relyra.app.core.messaging.Envelope
import org.relyra.app.core.messaging.EnvelopeCodec
import org.relyra.app.core.messaging.P2pSignalKind
import java.util.UUID

class P2pSignalEnvelopeTest {
    @Test fun offerAndAnswerSignalsRoundTripInsideWireEnvelope() {
        val id = UUID.randomUUID().toString()
        val offer = Envelope.P2pSignal(id, "sender", "recipient", P2pSignalKind.OFFER, "{\"type\":\"offer\",\"sdp\":\"v=0\"}")
        val decodedOffer = EnvelopeCodec.decode(EnvelopeCodec.encode(offer)) as Envelope.P2pSignal
        assertEquals(id, decodedOffer.messageId)
        assertEquals("sender", decodedOffer.from)
        assertEquals("recipient", decodedOffer.to)
        assertEquals(P2pSignalKind.OFFER, decodedOffer.kind)
        assertEquals(offer.signedSignal, decodedOffer.signedSignal)

        val answer = Envelope.P2pSignal(UUID.randomUUID().toString(), "recipient", "sender", P2pSignalKind.ANSWER, "{\"type\":\"answer\"}")
        val decodedAnswer = EnvelopeCodec.decode(EnvelopeCodec.encode(answer)) as Envelope.P2pSignal
        assertEquals(P2pSignalKind.ANSWER, decodedAnswer.kind)
        assertEquals(answer.signedSignal, decodedAnswer.signedSignal)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encoderRejectsOversizedSignals() {
        val signal = "x".repeat(EnvelopeCodec.MAX_P2P_SIGNAL_CHARS + 1)
        EnvelopeCodec.encode(Envelope.P2pSignal(
            UUID.randomUUID().toString(), "sender", "recipient", P2pSignalKind.OFFER, signal,
        ))
    }

    @Test fun decoderRejectsUnknownSignalKind() {
        val messageId = UUID.randomUUID().toString()
        val from = "a"
        val to = "b"
        val bytes = EnvelopeCodec.encode(Envelope.P2pSignal(
            messageId, from, to, P2pSignalKind.OFFER, "signed",
        ))
        // The four-byte envelope header is magic(2), version(1), and type(1).
        // DataOutputStream.writeUTF stores a two-byte length before each ASCII field.
        val envelopeHeaderBytes = 4
        val kindOffset = envelopeHeaderBytes + 2 + messageId.length + 2 + from.length + 2 + to.length
        bytes[kindOffset] = 0x7f
        assertNull(EnvelopeCodec.decode(bytes))
    }
}
