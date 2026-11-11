package org.relyra.app.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.relyra.app.core.network.TorSocksProxySelector
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .proxySelector(TorSocksProxySelector)
        .build()
}

sealed interface ProbeResult {
    class Success(val connectMs: Long, val eoseMs: Long) : ProbeResult
    class Failure(val reason: String) : ProbeResult
}

/** Minimal Nostr relay client. Phase 4 uses it for connectivity tests; message transport comes with encryption. */
class RelayClient(
    private val http: OkHttpClient = Http.client,
) {
    /** Opens the socket and asks for one public event; success means the relay speaks Nostr (EOSE received). */
    suspend fun probe(url: String, timeoutMs: Long = 10_000): ProbeResult =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<ProbeResult> { cont ->
                val done = AtomicBoolean(false)
                fun finish(r: ProbeResult) { if (done.compareAndSet(false, true) && cont.isActive) cont.resume(r) }
                val subId = "probe-" + ByteArray(4).also { SecureRandom().nextBytes(it) }.let { Hex.encode(it) }
                val start = System.nanoTime()
                var openMs = 0L
                val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        openMs = (System.nanoTime() - start) / 1_000_000
                        webSocket.send(RelayProtocol.req(subId, "{\"kinds\":[1],\"limit\":1}"))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when (val m = RelayProtocol.parse(text)) {
                            is RelayMessage.Eose -> if (m.subId == subId) {
                                finish(ProbeResult.Success(openMs, (System.nanoTime() - start) / 1_000_000))
                                webSocket.close(1000, null)
                            }
                            is RelayMessage.Closed -> if (m.subId == subId) {
                                finish(ProbeResult.Failure("relay closed the request: " + m.message.take(80)))
                                webSocket.close(1000, null)
                            }
                            else -> Unit
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        finish(ProbeResult.Failure(t.message?.take(100) ?: t.javaClass.simpleName))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        finish(ProbeResult.Failure("connection closed ($code)"))
                    }
                })
                cont.invokeOnCancellation { ws.cancel() }
            }
        } ?: ProbeResult.Failure("timeout")
}
