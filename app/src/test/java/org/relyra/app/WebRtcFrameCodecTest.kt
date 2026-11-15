package org.relyra.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.network.WebRtcFrameCodec
import java.nio.ByteBuffer

class WebRtcFrameCodecTest {
    private val firstId = "11111111-1111-4111-8111-111111111111"
    private val secondId = "22222222-2222-4222-8222-222222222222"

    @Test fun messageFrameRoundTripsAndPreservesPayloadAndTransferId() {
        val payload = ByteArray(1024) { (it * 31).toByte() }
        val encoded = WebRtcFrameCodec.encodeMessage(payload, firstId)!!
        val decoded = WebRtcFrameCodec.decode(encoded) as WebRtcFrameCodec.Frame.Message
        assertEquals(firstId, decoded.transferId)
        assertArrayEquals(payload, decoded.payload)
        assertArrayEquals(WebRtcFrameCodec.digestPayload(payload), decoded.digest)
    }

    @Test fun acknowledgementFrameRoundTripsAndBindsTransferId() {
        val digest = WebRtcFrameCodec.digestPayload("wire".toByteArray())
        val accepted = WebRtcFrameCodec.decode(
            WebRtcFrameCodec.encodeAck(firstId, digest, true)!!
        ) as WebRtcFrameCodec.Frame.Ack
        val rejected = WebRtcFrameCodec.decode(
            WebRtcFrameCodec.encodeAck(secondId, digest, false)!!
        ) as WebRtcFrameCodec.Frame.Ack
        assertEquals(firstId, accepted.transferId)
        assertEquals(secondId, rejected.transferId)
        assertTrue(accepted.accepted)
        assertFalse(rejected.accepted)
        assertArrayEquals(digest, accepted.digest)
    }

    @Test fun identicalPayloadsHaveDifferentTransferIds() {
        val payload = "the same encrypted wire bytes".toByteArray()
        val a = WebRtcFrameCodec.decode(WebRtcFrameCodec.encodeMessage(payload, firstId)!!) as WebRtcFrameCodec.Frame.Message
        val b = WebRtcFrameCodec.decode(WebRtcFrameCodec.encodeMessage(payload, secondId)!!) as WebRtcFrameCodec.Frame.Message
        assertArrayEquals(a.digest, b.digest)
        assertNotEquals(a.transferId, b.transferId)
    }

    @Test fun corruptPayloadAndMalformedFramesAreRejected() {
        val encoded = WebRtcFrameCodec.encodeMessage("signed-wire".toByteArray(), firstId)!!
        encoded.put(encoded.limit() - 1, (encoded.get(encoded.limit() - 1).toInt() xor 0x01).toByte())
        assertNull(WebRtcFrameCodec.decode(encoded))
        assertNull(WebRtcFrameCodec.decode(ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
    }

    @Test fun frameLengthAndTransferIdentifiersAreBounded() {
        assertNull(WebRtcFrameCodec.encodeMessage(ByteArray(65_500), firstId))
        assertNull(WebRtcFrameCodec.encodeMessage(byteArrayOf(1), "not-a-uuid"))
        assertNull(WebRtcFrameCodec.encodeAck("not-a-uuid", ByteArray(32), true))
        assertEquals(54, WebRtcFrameCodec.ACK_BYTES)
    }
}
