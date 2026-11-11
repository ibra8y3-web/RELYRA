package org.relyra.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.network.NostrEvents
import org.relyra.app.core.network.NostrPool
import org.relyra.app.core.network.RELYRA_KIND

class NostrPoolTest {
    private val secret = ByteArray(32).also { it[31] = 3 }

    @Test fun relaySubscriptionEventDeliveryAndPublishAcknowledgement() = runBlocking {
        val server = MockWebServer()
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val subscribed = CompletableDeferred<Unit>()
        val received = CompletableDeferred<org.relyra.app.core.network.NostrEvent>()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONArray(text)
                when (message.getString(0)) {
                    "REQ" -> {
                        subscribed.complete(Unit)
                        val incoming = NostrEvents.create(
                            secret, RELYRA_KIND, listOf(listOf("p", "recipient")),
                            "incoming", 1_700_000_000,
                        )
                        webSocket.send(JSONArray().put("EVENT").put("relyra-inbox").put(JSONObject(NostrEvents.toJson(incoming))).toString())
                        webSocket.send(JSONArray().put("EOSE").put("relyra-inbox").toString())
                    }
                    "EVENT" -> {
                        val id = message.getJSONObject(1).getString("id")
                        webSocket.send(JSONArray().put("OK").put(id).put(true).put("").toString())
                    }
                }
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        val pool = NostrPool(OkHttpClient(), scope, { 1_700_000_000L }) { received.complete(it) }
        try {
            val ws = server.url("/").toString().replaceFirst("http", "ws")
            pool.configure(secret, setOf(ws))
            withTimeout(5_000) { subscribed.await() }
            val outgoing = NostrEvents.create(secret, RELYRA_KIND, emptyList(), "outgoing", 1_700_000_001)
            assertTrue(withTimeout(5_000) { pool.publish(outgoing) })
            assertTrue(NostrEvents.verify(withTimeout(5_000) { received.await() }))
            assertTrue(pool.connected.value.values.any { it })
        } finally {
            pool.configure(null, emptySet())
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun pendingPublishFailsWhenRelayDisconnects() = runBlocking {
        val server = MockWebServer()
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val subscribed = CompletableDeferred<Unit>()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONArray(text)
                if (message.getString(0) == "REQ") subscribed.complete(Unit)
                if (message.getString(0) == "EVENT") webSocket.close(1001, "test disconnect")
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        val pool = NostrPool(OkHttpClient(), scope, { 1_700_000_000L }) { }
        try {
            pool.configure(secret, setOf(server.url("/").toString().replaceFirst("http", "ws")))
            withTimeout(5_000) { subscribed.await() }
            val event = NostrEvents.create(secret, RELYRA_KIND, emptyList(), "disconnect", 1_700_000_001)
            assertFalse(withTimeout(5_000) { pool.publish(event) })
        } finally {
            pool.configure(null, emptySet())
            scope.cancel()
            server.shutdown()
        }
    }
}
