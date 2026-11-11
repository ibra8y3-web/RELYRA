package org.relyra.app.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

const val RELYRA_KIND = 4747
private const val SUB = "relyra-inbox"

/** Persistent connections to the user's enabled relays: inbox subscription, publish with OK confirmation, reconnect. */
class NostrPool(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val sinceProvider: () -> Long,
    private val onEvent: suspend (NostrEvent) -> Unit,
) {
    private val conns = ConcurrentHashMap<String, Conn>()
    @Volatile private var secret: ByteArray? = null
    @Volatile private var pubHex: String? = null
    val connected = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    @Synchronized
    fun configure(newSecret: ByteArray?, urls: Set<String>, forceReconnect: Boolean = false) {
        val newPub = newSecret?.let { Hex.encode(NostrCrypto.publicKeyX(it)) }
        if (forceReconnect || newPub != pubHex) { conns.values.forEach { it.stop() }; conns.clear() }
        secret = newSecret
        pubHex = newPub
        if (newSecret != null) {
            conns.keys.filter { !urls.contains(it) }.forEach { conns.remove(it)?.stop() }
            urls.filter { !conns.containsKey(it) }.forEach { u -> conns[u] = Conn(u).also { it.start() } }
        }
        publishStates()
    }

    private fun publishStates() { connected.value = conns.mapValues { it.value.isConnected } }

    suspend fun publish(e: NostrEvent): Boolean = coroutineScope {
        val json = RelayProtocol.event(e)
        conns.values.filter { it.isConnected }.map { c -> async { c.publish(e.id, json) } }.awaitAll().any { it }
    }

    /** Waits until every relay finished its stored-event backlog, or the timeout passes. */
    suspend fun waitForBacklog(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { while (conns.isEmpty() || conns.values.any { !it.eose }) delay(250) }
    }

    private inner class Conn(val url: String) {
        @Volatile var isConnected = false
        @Volatile var eose = false
        private var ws: WebSocket? = null
        private var job: Job? = null
        private var attempt = 0
        private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

        fun start() {
            job = scope.launch {
                while (isActive) {
                    runOnce()
                    isConnected = false; eose = false; publishStates()
                    attempt++
                    delay(minOf(2_000L shl minOf(attempt, 6), 120_000L))
                }
            }
        }

        fun stop() { job?.cancel(); ws?.cancel(); isConnected = false }

        private suspend fun runOnce() = suspendCancellableCoroutine<Unit> { cont ->
            val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempt = 0
                    isConnected = true; publishStates()
                    val pub = pubHex ?: return
                    webSocket.send(RelayProtocol.req(SUB, "{\"kinds\":[$RELYRA_KIND],\"#p\":[\"$pub\"],\"since\":${sinceProvider()}}"))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val m = RelayProtocol.parse(text)) {
                        is RelayMessage.Event -> if (m.subId == SUB) scope.launch { onEvent(m.event) }
                        is RelayMessage.Ok -> pending.remove(m.eventId)?.complete(m.accepted)
                        is RelayMessage.Eose -> if (m.subId == SUB) eose = true
                        else -> Unit
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (cont.isActive) cont.resume(Unit) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (cont.isActive) cont.resume(Unit) }
            })
            ws = socket
            cont.invokeOnCancellation { socket.cancel() }
        }

        suspend fun publish(id: String, json: String): Boolean {
            val d = CompletableDeferred<Boolean>()
            pending[id] = d
            if (ws?.send(json) != true) { pending.remove(id); return false }
            val ok = withTimeoutOrNull(8_000) { d.await() } ?: false
            pending.remove(id)
            return ok
        }
    }
}
