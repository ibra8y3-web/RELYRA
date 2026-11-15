package org.relyra.app.core.network

/** Bounded exponential retry policy for rebuilding a failed WebRTC peer session. */
object WebRtcReconnectPolicy {
    const val MAX_ATTEMPTS = 6
    const val NETWORK_WAIT_ATTEMPTS = 15
    private val delays = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)

    fun delayMillis(attempt: Int): Long = delays[attempt.coerceIn(0, delays.lastIndex)]
}
