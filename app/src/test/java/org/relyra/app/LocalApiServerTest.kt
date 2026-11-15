package org.relyra.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.integrations.ApiSendResult
import org.relyra.app.core.integrations.LocalApiServer
import java.net.Socket

class LocalApiServerTest {
    private var sentTo: String? = null
    private var sentText: String? = null
    private var sentGroup: String? = null
    private val server = LocalApiServer(
        version = "test",
        identityProvider = { null },
        contactsProvider = { listOf("contact-1" to "Alice") },
        groupsProvider = { listOf("group-1" to "Team") },
        sendDirect = { to, text ->
            sentTo = to
            sentText = text
            ApiSendResult(202, "queued", "message-1")
        },
        sendGroup = { groupId, text ->
            sentGroup = groupId
            sentText = text
            ApiSendResult(202, "queued")
        },
    )

    @After
    fun tearDown() {
        server.stop()
    }

    private fun raw(port: Int, bytes: ByteArray): Pair<Int, String> {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply { write(bytes); flush() }
            val text = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val status = text.substringBefore("\r\n").split(" ")[1].toInt()
            return status to text.substringAfter("\r\n\r\n")
        }
    }

    private fun get(port: Int, token: String?, path: String): Pair<Int, String> {
        val auth = if (token != null) "Authorization: Bearer $token\r\n" else ""
        val head = "GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$auth\r\n"
        return raw(port, head.toByteArray(Charsets.US_ASCII))
    }

    private fun post(
        port: Int,
        token: String?,
        path: String,
        body: String,
        contentType: String = "application/json",
    ): Pair<Int, String> {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val auth = if (token != null) "Authorization: Bearer $token\r\n" else ""
        val head = "POST $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$auth" +
            "Content-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n\r\n"
        return raw(port, head.toByteArray(Charsets.US_ASCII) + bytes)
    }

    private fun startServer(allowMessaging: Boolean): Pair<Int, String> {
        assertTrue(server.start(exposeIdentity = false, allowMessaging = allowMessaging))
        return server.state.value.port!! to server.state.value.token!!
    }

    @Test
    fun postIsRefusedUntilTheMessagingScopeIsEnabled() {
        val (port, token) = startServer(allowMessaging = false)
        assertEquals(403, post(port, token, "/v1/messages", """{"to":"contact-1","text":"hi"}""").first)
        assertEquals(403, get(port, token, "/v1/contacts").first)
        assertEquals(null, sentTo)
    }

    @Test
    fun sendsAMessageAndKeepsUtf8Text() {
        val (port, token) = startServer(allowMessaging = true)
        val (status, body) = post(port, token, "/v1/messages", """{"to":"contact-1","text":"مرحبا"}""")
        assertEquals(202, status)
        assertTrue(body.contains("message-1"))
        assertEquals("contact-1", sentTo)
        assertEquals("مرحبا", sentText)
    }

    @Test
    fun sendsAGroupMessage() {
        val (port, token) = startServer(allowMessaging = true)
        assertEquals(202, post(port, token, "/v1/groups/messages", """{"groupId":"group-1","text":"hello"}""").first)
        assertEquals("group-1", sentGroup)
    }

    @Test
    fun listsContactsAndGroupsOnlyWhenEnabled() {
        val (port, token) = startServer(allowMessaging = true)
        assertTrue(get(port, token, "/v1/contacts").second.contains("Alice"))
        assertTrue(get(port, token, "/v1/groups").second.contains("Team"))
    }

    @Test
    fun rejectsBadRequests() {
        val (port, token) = startServer(allowMessaging = true)
        assertEquals(401, post(port, null, "/v1/messages", """{"to":"a","text":"b"}""").first)
        assertEquals(415, post(port, token, "/v1/messages", """{"to":"a","text":"b"}""", "text/plain").first)
        assertEquals(400, post(port, token, "/v1/messages", "not json").first)
        assertEquals(422, post(port, token, "/v1/messages", """{"to":"a","text":"  "}""").first)
        assertEquals(404, post(port, token, "/v1/other", """{"to":"a","text":"b"}""").first)
        val oversized = "POST /v1/messages HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\n" +
            "Content-Type: application/json\r\nContent-Length: 9000\r\n\r\n"
        assertEquals(413, raw(port, oversized.toByteArray(Charsets.US_ASCII)).first)
        assertEquals(null, sentTo)
    }

    @Test
    fun limitsSendsToTwentyPerMinute() {
        val (port, token) = startServer(allowMessaging = true)
        repeat(20) { assertEquals(202, post(port, token, "/v1/messages", """{"to":"a","text":"b"}""").first) }
        assertEquals(429, post(port, token, "/v1/messages", """{"to":"a","text":"b"}""").first)
    }

    @Test
    fun getStillWorksWithoutTheMessagingScope() {
        val (port, token) = startServer(allowMessaging = false)
        assertEquals(200, get(port, token, "/v1/health").first)
        assertEquals(200, get(port, token, "/v1/openapi.json").first)
    }
}
