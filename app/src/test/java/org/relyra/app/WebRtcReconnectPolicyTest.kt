package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.network.WebRtcReconnectPolicy

class WebRtcReconnectPolicyTest {
    @Test fun retryDelayGrowsAndIsCapped() {
        val values = (0 until WebRtcReconnectPolicy.MAX_ATTEMPTS)
            .map(WebRtcReconnectPolicy::delayMillis)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), values)
        assertTrue(WebRtcReconnectPolicy.delayMillis(99) == 30_000L)
        assertTrue(WebRtcReconnectPolicy.NETWORK_WAIT_ATTEMPTS in 1..30)
    }
}
