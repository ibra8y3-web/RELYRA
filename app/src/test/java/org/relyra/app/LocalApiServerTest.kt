package org.relyra.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.integrations.LocalApiServer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class LocalApiServerTest {
    private fun request(port: Int, path: String, token: String? = null): JSONObject {
        Socket().use { socket ->
            socket.soTimeout = 3_000
            socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            val headers = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(headers.toByteArray(StandardCharsets.US_ASCII))
            socket.getOutputStream().flush()
            val response = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readText()
            val status = response.lineSequence().firstOrNull().orEmpty()
            val body = response.substringAfter("\r\n\r\n", "")
            return JSONObject().put("_status", status).put("_body", JSONObject(body))
        }
    }

    @Test
    fun tokenProtectsHealthDiagnosticsIdentityAndSchema() {
        val api = LocalApiServer(
            version = "0.9.0",
            identityProvider = { "Local user" to "public-id" },
            diagnosticsProvider = { JSONObject().put("enabledRelays", 2).put("meshEnabled", false) },
        )
        try {
            assertTrue(api.start(exposeIdentity = false))
            val state = api.state.value
            val port = state.port ?: error("Loopback API did not publish its port")
            val token = state.token ?: error("Loopback API did not issue a bearer token")

            assertTrue(request(port, "/v1/health") .getString("_status").contains("401"))
            val health = request(port, "/v1/health", token)
            assertTrue(health.getString("_status").contains("200"))
            assertEquals("ok", health.getJSONObject("_body").getString("status"))

            val diagnostics = request(port, "/v1/diagnostics", token)
            assertEquals(2, diagnostics.getJSONObject("_body").getInt("enabledRelays"))
            assertFalse(diagnostics.getJSONObject("_body").has("messageBodies"))

            val deniedIdentity = request(port, "/v1/identity", token)
            assertTrue(deniedIdentity.getString("_status").contains("403"))

            val schema = request(port, "/v1/openapi.json", token)
            val openApi = schema.getJSONObject("_body")
            assertEquals("3.1.0", openApi.getString("openapi"))
            assertTrue(openApi.getJSONObject("paths").has("/v1/diagnostics"))
        } finally {
            api.stop()
        }
    }

    @Test
    fun identityIsReturnedOnlyAfterExplicitScopeEnablement() {
        val api = LocalApiServer(
            version = "0.9.0",
            identityProvider = { "Local user" to "public-id" },
        )
        try {
            assertTrue(api.start(exposeIdentity = true))
            val state = api.state.value
            val result = request(state.port ?: error("Missing port"), "/v1/identity",
                state.token ?: error("Missing token"))
            assertTrue(result.getString("_status").contains("200"))
            assertEquals("public-id", result.getJSONObject("_body").getString("publicId"))
        } finally {
            api.stop()
        }
    }
}
