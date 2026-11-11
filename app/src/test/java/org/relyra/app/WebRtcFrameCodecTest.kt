package org.relyra.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.network.WebRtcFrameCodec
import java.nio.ByteBuffer

class WebRtcFrameCodecTest {
    @Test fun messageFrameRoundTripsAndPreservesPayload() {
        val payload = ByteArray(1024) { (it * 31).toByte() }
        val encoded = WebRtcFrameCodec.encodeMessage(payload)!!
        val decoded = WebRtcFrameCodec.decode(encoded) as WebRtcFrameCodec.Frame.Message
        assertArrayEquals(payload, decoded.payload)
        assertArrayEquals(WebRtcFrameCodec.digestPayload(payload), decoded.digest)
    }

    @Test fun acknowledgementFrameRoundTrips() {
        val digest = WebRtcFrameCodec.digestPayload("wire".toByteArray())
        val accepted = WebRtcFrameCodec.decode(WebRtcFrameCodec.encodeAck(digest, true)!!) as WebRtcFrameCodec.Frame.Ack
        val rejected = WebRtcFrameCodec.decode(WebRtcFrameCodec.encodeAck(digest, false)!!) as WebRtcFrameCodec.Frame.Ack
        assertTrue(accepted.accepted)
        assertFalse(rejected.accepted)
        assertArrayEquals(digest, accepted.digest)
    }

    @Test fun corruptPayloadAndMalformedFramesAreRejected() {
        val encoded = WebRtcFrameCodec.encodeMessage("signed-wire".toByteArray())!!
        encoded.put(encoded.limit() - 1, (encoded.get(encoded.limit() - 1).toInt() xor 0x01).toByte())
        assertNull(WebRtcFrameCodec.decode(encoded))
        assertNull(WebRtcFrameCodec.decode(ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
    }

    @Test fun frameLengthIsBoundedBeforeAllocation() {
        assertNull(WebRtcFrameCodec.encodeMessage(ByteArray(65_500)))
        assertEquals(38, WebRtcFrameCodec.ACK_BYTES)
    }
}
