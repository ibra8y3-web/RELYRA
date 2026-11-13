package org.relyra.app.core.integrations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class LocalApiState(
    val running: Boolean = false,
    val port: Int? = null,
    val token: String? = null,
    val exposesIdentity: Boolean = false,
    val error: String? = null,
)

/**
 * A deliberately small read-only API. It binds only to IPv4 loopback, accepts GET requests only,
 * requires a fresh in-memory bearer token, rejects browser Origin headers, and never exposes message
 * bodies, private keys, or database files. The listener dies with the app process.
 */
class LocalApiServer(
    private val version: String,
    private val identityProvider: suspend () -> Pair<String, String>?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val recentRequests = ArrayDeque<Long>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var listener: Job? = null
    @Volatile private var bearer: String? = null
    @Volatile private var identityAllowed = false

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(LocalApiState())
    val state: kotlinx.coroutines.flow.StateFlow<LocalApiState> = _state

    @Synchronized
    fun start(exposeIdentity: Boolean): Boolean {
        if (running.get()) stop()
        return try {
            val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            socket.soTimeout = 1_000
            val tokenBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
            tokenBytes.fill(0)
            server = socket
            bearer = token
            identityAllowed = exposeIdentity
            running.set(true)
            _state.value = LocalApiState(true, socket.localPort, token, exposeIdentity, null)
            listener = scope.launch {
                while (isActive && running.get()) {
                    try {
                        val client = socket.accept()
                        launch { runCatching { client.use { handle(it, socket.localPort) } } }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Periodic cancellation and close check.
                    } catch (t: Throwable) {
                        if (running.get()) _state.value = _state.value.copy(error = t.message ?: "listener failed")
                        break
                    }
                }
            }
            true
        } catch (t: Throwable) {
            stop()
            _state.value = LocalApiState(error = t.message ?: "could not bind loopback listener")
            false
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        bearer = null
        identityAllowed = false
        listener?.cancel()
        listener = null
        runCatching { server?.close() }
        server = null
        _state.value = LocalApiState()
    }

    private suspend fun handle(socket: Socket, port: Int) {
        socket.soTimeout = 4_000
        if (!socket.inetAddress.isLoopbackAddress) {
            respond(socket.getOutputStream(), 403, errorJson("loopback only"))
            return
        }
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
        val requestLine = readBoundedLine(reader, 2_048)
        if (requestLine == null) return
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[0] != "GET" || parts[2] != "HTTP/1.1") {
            respond(socket.getOutputStream(), 405, errorJson("GET only"))
            return
        }
        if (parts[1].contains('?') || parts[1].contains('#') || parts[1].contains("..")) {
            respond(socket.getOutputStream(), 400, errorJson("invalid path"))
            return
        }
        val headers = linkedMapOf<String, String>()
        var total = requestLine.length
        var headerCount = 0
        while (true) {
            val line = readBoundedLine(reader, 2_048) ?: return
            total += line.length
            if (total > 8_192 || ++headerCount > 32) {
                respond(socket.getOutputStream(), 431, errorJson("headers too large"))
                return
            }
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split <= 0) {
                respond(socket.getOutputStream(), 400, errorJson("invalid header"))
                return
            }
            headers[line.substring(0, split).trim().lowercase(Locale.ROOT)] = line.substring(split + 1).trim()
        }
        val host = headers["host"].orEmpty().lowercase(Locale.ROOT)
        if (host != "127.0.0.1:$port" && host != "localhost:$port") {
            respond(socket.getOutputStream(), 400, errorJson("invalid host"))
            return
        }
        val origin = headers["origin"]
        if (origin != null && origin != "http://127.0.0.1:$port" && origin != "http://localhost:$port") {
            respond(socket.getOutputStream(), 403, errorJson("browser origin is not allowed"))
            return
        }
        val supplied = headers["authorization"]?.takeIf { it.startsWith("Bearer ", true) }?.substring(7)
        val expected = bearer
        if (expected == null || supplied == null ||
            !MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), supplied.toByteArray(StandardCharsets.UTF_8))
        ) {
            respond(socket.getOutputStream(), 401, errorJson("bearer token required"))
            return
        }
        if (!allowRequest()) {
            respond(socket.getOutputStream(), 429, errorJson("rate limit exceeded"))
            return
        }
        val payload = when (parts[1]) {
            "/v1/health" -> JSONObject()
                .put("service", "RELYRA Local API")
                .put("status", "ok")
                .put("version", version)
                .put("timestamp", System.currentTimeMillis())
            "/v1/identity" -> {
                if (!identityAllowed) {
                    respond(socket.getOutputStream(), 403, errorJson("identity.read scope is disabled"))
                    return
                }
                val identity = withContext(Dispatchers.IO) { identityProvider() }
                    ?: run {
                        respond(socket.getOutputStream(), 404, errorJson("RELYRA identity is not initialized"))
                        return
                    }
                JSONObject().put("displayName", identity.first).put("publicId", identity.second)
            }
            else -> {
                respond(socket.getOutputStream(), 404, errorJson("endpoint not found"))
                return
            }
        }
        respond(socket.getOutputStream(), 200, payload.toString())
    }

    @Synchronized
    private fun allowRequest(): Boolean {
        val now = System.currentTimeMillis()
        while (recentRequests.isNotEmpty() && now - recentRequests.first() > 60_000L) {
            recentRequests.removeFirst()
        }
        if (recentRequests.size >= 60) return false
        recentRequests.addLast(now)
        return true
    }

    private fun readBoundedLine(reader: BufferedReader, max: Int): String? {
        val out = StringBuilder()
        while (out.length <= max) {
            val value = reader.read()
            if (value < 0) return if (out.isEmpty()) null else out.toString()
            if (value == '\n'.code) return out.toString().trimEnd('\r')
            out.append(value.toChar())
        }
        throw java.io.IOException("HTTP line too long")
    }

    private fun errorJson(message: String) = JSONObject().put("error", message).toString()

    private fun respond(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            else -> "Error"
        }
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }
}
