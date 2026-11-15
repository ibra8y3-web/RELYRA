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
import java.io.BufferedInputStream
import java.io.InputStream
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
    val expiresAtMillis: Long? = null,
    val error: String? = null,
    val allowsMessaging: Boolean = false,
)

/** Outcome of an API send request, mapped to an HTTP status by the provider. */
class ApiSendResult(val status: Int, val message: String, val messageId: String? = null)

/**
 * A deliberately small loopback API. It binds only to IPv4 loopback, requires a fresh in-memory bearer
 * token, rejects browser Origin headers, and never exposes message bodies, private keys, or database
 * files. Read endpoints are GET only. Sending messages (POST, JSON only, 8 KiB limit, 20 sends per
 * minute) and listing contacts or groups need the separate messages.send scope, which is off until the
 * user turns it on in the app. The listener dies with the app process.
 */
class LocalApiServer(
    private val version: String,
    private val identityProvider: suspend () -> Pair<String, String>?,
    private val diagnosticsProvider: suspend () -> JSONObject = { JSONObject() },
    private val contactsProvider: suspend () -> List<Pair<String, String>> = { emptyList() },
    private val groupsProvider: suspend () -> List<Pair<String, String>> = { emptyList() },
    private val sendDirect: suspend (String, String) -> ApiSendResult = { _, _ -> ApiSendResult(503, "sending is not available") },
    private val sendGroup: suspend (String, String) -> ApiSendResult = { _, _ -> ApiSendResult(503, "sending is not available") },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val recentRequests = ArrayDeque<Long>()
    private val recentSends = ArrayDeque<Long>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var listener: Job? = null
    @Volatile private var bearer: String? = null
    @Volatile private var identityAllowed = false
    @Volatile private var messagingAllowed = false
    @Volatile private var tokenExpiresAt = 0L

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(LocalApiState())
    val state: kotlinx.coroutines.flow.StateFlow<LocalApiState> = _state

    @Synchronized
    fun start(exposeIdentity: Boolean, allowMessaging: Boolean = false): Boolean {
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
            messagingAllowed = allowMessaging
            tokenExpiresAt = System.currentTimeMillis() + TOKEN_LIFETIME_MS
            running.set(true)
            _state.value = LocalApiState(true, socket.localPort, token, exposeIdentity, tokenExpiresAt, null, allowMessaging)
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
        messagingAllowed = false
        tokenExpiresAt = 0L
        listener?.cancel()
        listener = null
        runCatching { server?.close() }
        server = null
        _state.value = LocalApiState()
    }

    private suspend fun handle(socket: Socket, port: Int) {
        socket.soTimeout = 4_000
        val out = socket.getOutputStream()
        if (!socket.inetAddress.isLoopbackAddress) {
            respond(out, 403, errorJson("loopback only"))
            return
        }
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readBoundedLine(input, 2_048)
        if (requestLine == null) return
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0)
        if (parts.size != 3 || (method != "GET" && method != "POST") || parts[2] != "HTTP/1.1") {
            respond(out, 405, errorJson("GET and POST only"))
            return
        }
        val path = parts[1]
        if (path.contains('?') || path.contains('#') || path.contains("..")) {
            respond(out, 400, errorJson("invalid path"))
            return
        }
        val headers = linkedMapOf<String, String>()
        var total = requestLine.length
        var headerCount = 0
        while (true) {
            val line = readBoundedLine(input, 2_048) ?: return
            total += line.length
            if (total > 8_192 || ++headerCount > 32) {
                respond(out, 431, errorJson("headers too large"))
                return
            }
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split <= 0) {
                respond(out, 400, errorJson("invalid header"))
                return
            }
            headers[line.substring(0, split).trim().lowercase(Locale.ROOT)] = line.substring(split + 1).trim()
        }
        if (headers["transfer-encoding"] != null) {
            respond(out, 400, errorJson("chunked request bodies are not supported"))
            return
        }
        if (method == "GET" && headers["content-length"]?.let { it.toLongOrNull() != 0L } == true) {
            respond(out, 400, errorJson("request bodies are not supported on GET"))
            return
        }
        val host = headers["host"].orEmpty().lowercase(Locale.ROOT)
        if (host != "127.0.0.1:$port" && host != "localhost:$port") {
            respond(out, 400, errorJson("invalid host"))
            return
        }
        val origin = headers["origin"]
        if (origin != null && origin != "http://127.0.0.1:$port" && origin != "http://localhost:$port") {
            respond(out, 403, errorJson("browser origin is not allowed"))
            return
        }
        val supplied = headers["authorization"]?.takeIf { it.startsWith("Bearer ", true) }?.substring(7)
        val expected = bearer
        if (expected == null || System.currentTimeMillis() >= tokenExpiresAt || supplied == null ||
            !MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), supplied.toByteArray(StandardCharsets.UTF_8))
        ) {
            respond(out, 401, errorJson("bearer token required"))
            return
        }
        if (!allowRequest()) {
            respond(out, 429, errorJson("rate limit exceeded"))
            return
        }
        if (method == "POST") {
            handlePost(out, input, path, headers)
            return
        }
        val payload = when (path) {
            "/v1/health" -> JSONObject()
                .put("service", "RELYRA Local API")
                .put("status", "ok")
                .put("version", version)
                .put("timestamp", System.currentTimeMillis())
            "/v1/identity" -> {
                if (!identityAllowed) {
                    respond(out, 403, errorJson("identity.read scope is disabled"))
                    return
                }
                val identity = withContext(Dispatchers.IO) { identityProvider() }
                    ?: run {
                        respond(out, 404, errorJson("RELYRA identity is not initialized"))
                        return
                    }
                JSONObject().put("displayName", identity.first).put("publicId", identity.second)
            }
            "/v1/contacts" -> {
                if (!messagingAllowed) {
                    respond(out, 403, errorJson("messages.send scope is disabled"))
                    return
                }
                val list = withContext(Dispatchers.IO) { contactsProvider() }
                val array = org.json.JSONArray()
                list.take(1_000).forEach { array.put(JSONObject().put("publicId", it.first).put("displayName", it.second)) }
                JSONObject().put("contacts", array)
            }
            "/v1/groups" -> {
                if (!messagingAllowed) {
                    respond(out, 403, errorJson("messages.send scope is disabled"))
                    return
                }
                val list = withContext(Dispatchers.IO) { groupsProvider() }
                val array = org.json.JSONArray()
                list.take(1_000).forEach { array.put(JSONObject().put("groupId", it.first).put("name", it.second)) }
                JSONObject().put("groups", array)
            }
            "/v1/diagnostics" -> withContext(Dispatchers.IO) { diagnosticsProvider() }
            "/v1/openapi.json" -> apiSchema()
            else -> {
                respond(out, 404, errorJson("endpoint not found"))
                return
            }
        }
        respond(out, 200, payload.toString())
    }

    /** POST endpoints: opt-in messages.send scope, JSON only, small bodies, separate send rate limit. */
    private suspend fun handlePost(out: OutputStream, input: InputStream, path: String, headers: Map<String, String>) {
        if (path != "/v1/messages" && path != "/v1/groups/messages") {
            respond(out, 404, errorJson("endpoint not found"))
            return
        }
        if (!messagingAllowed) {
            respond(out, 403, errorJson("messages.send scope is disabled"))
            return
        }
        if (!headers["content-type"].orEmpty().lowercase(Locale.ROOT).startsWith("application/json")) {
            respond(out, 415, errorJson("Content-Type must be application/json"))
            return
        }
        val length = headers["content-length"]?.toLongOrNull()
        if (length == null || length <= 0L) {
            respond(out, 400, errorJson("Content-Length is required"))
            return
        }
        if (length > MAX_BODY_BYTES) {
            respond(out, 413, errorJson("request body is too large"))
            return
        }
        val body = ByteArray(length.toInt())
        var filled = 0
        while (filled < body.size) {
            val read = input.read(body, filled, body.size - filled)
            if (read < 0) {
                respond(out, 400, errorJson("request body ended early"))
                return
            }
            filled += read
        }
        val json = try {
            JSONObject(String(body, StandardCharsets.UTF_8))
        } catch (_: Throwable) {
            respond(out, 400, errorJson("invalid JSON"))
            return
        }
        val target = json.optString(if (path == "/v1/messages") "to" else "groupId").trim()
        val text = json.optString("text")
        if (target.isEmpty() || target.length > 128 || text.isBlank()) {
            respond(out, 422, errorJson("target and non-empty text are required"))
            return
        }
        if (text.length > MAX_TEXT_CHARS) {
            respond(out, 413, errorJson("text is too long"))
            return
        }
        if (!allowSend()) {
            respond(out, 429, errorJson("send rate limit exceeded"))
            return
        }
        val result = withContext(Dispatchers.IO) {
            if (path == "/v1/messages") sendDirect(target, text) else sendGroup(target, text)
        }
        val payload = JSONObject().put("status", if (result.status in 200..299) "queued" else "rejected").put("detail", result.message)
        result.messageId?.let { payload.put("messageId", it) }
        respond(out, result.status, payload.toString())
    }

    @Synchronized
    private fun allowSend(): Boolean {
        val now = System.currentTimeMillis()
        while (recentSends.isNotEmpty() && now - recentSends.first() > 60_000L) {
            recentSends.removeFirst()
        }
        if (recentSends.size >= 20) return false
        recentSends.addLast(now)
        return true
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

    private fun readBoundedLine(input: InputStream, max: Int): String? {
        val out = StringBuilder()
        while (out.length <= max) {
            val value = input.read()
            if (value < 0) return if (out.isEmpty()) null else out.toString()
            if (value == '\n'.code) return out.toString().trimEnd('\r')
            out.append(value.toChar())
        }
        throw java.io.IOException("HTTP line too long")
    }

    private fun errorJson(message: String) = JSONObject().put("error", message).toString()

    /** Static read-only contract; never includes the active token or account/device secrets. */
    private fun apiSchema(): JSONObject {
        val bearer = JSONObject()
            .put("type", "http")
            .put("scheme", "bearer")
            .put("bearerFormat", "RELYRA local short-lived token")
        fun response(description: String) = JSONObject()
            .put("200", JSONObject().put("description", description)
                .put("content", JSONObject().put("application/json",
                    JSONObject().put("schema", JSONObject().put("type", "object")))))
            .put("401", JSONObject().put("description", "Missing, invalid or expired bearer token"))
            .put("404", JSONObject().put("description", "Endpoint or RELYRA identity not found"))
            .put("429", JSONObject().put("description", "Local request rate limit exceeded"))
        fun operation(summary: String, description: String) = JSONObject()
            .put("summary", summary)
            .put("security", org.json.JSONArray().put(JSONObject().put("LocalBearer", org.json.JSONArray())))
            .put("x-relyra-loopback-only", true)
            .put("responses", response(description))
        val health = JSONObject().put("get", operation("Read local API health", "Local API process status"))
        val identity = JSONObject().put("get", operation("Read public identity when identity.read is enabled", "Public display name and RELYRA public ID")
            .put("x-relyra-scope", "identity.read"))
        val diagnostics = JSONObject().put("get", operation("Read redacted operational counters", "Counts and boolean status only"))
        val contacts = JSONObject().put("get", operation("List contact ids and display names when messages.send is enabled", "Contact public ids and names")
            .put("x-relyra-scope", "messages.send"))
        val groupList = JSONObject().put("get", operation("List group ids and names when messages.send is enabled", "Group ids and names")
            .put("x-relyra-scope", "messages.send"))
        fun sendOperation(summary: String, targetField: String): JSONObject = JSONObject()
            .put("summary", summary)
            .put("security", org.json.JSONArray().put(JSONObject().put("LocalBearer", org.json.JSONArray())))
            .put("x-relyra-loopback-only", true)
            .put("x-relyra-scope", "messages.send")
            .put("requestBody", JSONObject().put("required", true).put("content", JSONObject().put("application/json",
                JSONObject().put("schema", JSONObject().put("type", "object")
                    .put("required", org.json.JSONArray().put(targetField).put("text"))
                    .put("properties", JSONObject()
                        .put(targetField, JSONObject().put("type", "string"))
                        .put("text", JSONObject().put("type", "string").put("maxLength", MAX_TEXT_CHARS)))))))
            .put("responses", JSONObject()
                .put("202", JSONObject().put("description", "Message was queued for encrypted delivery"))
                .put("401", JSONObject().put("description", "Missing, invalid or expired bearer token"))
                .put("403", JSONObject().put("description", "messages.send scope disabled, or the contact is blocked"))
                .put("404", JSONObject().put("description", "Unknown contact or group"))
                .put("422", JSONObject().put("description", "Empty or too long message"))
                .put("429", JSONObject().put("description", "Rate limit exceeded (20 sends per minute)")))
        val sendMessage = JSONObject().put("post", sendOperation("Send a text message to a contact", "to"))
        val sendGroupMessage = JSONObject().put("post", sendOperation("Send a text message to a group", "groupId"))
        return JSONObject()
            .put("openapi", "3.1.0")
            .put("info", JSONObject()
                .put("title", "RELYRA Local Companion API")
                .put("version", "1.0.0")
                .put("description", "Loopback-only API. A fresh bearer token expires after 15 minutes. Read endpoints use GET. Sending messages needs the messages.send scope, which is off until the user enables it. Requests must use the loopback Host and must not include browser Origin headers."))
            .put("components", JSONObject().put("securitySchemes", JSONObject().put("LocalBearer", bearer)))
            .put("paths", JSONObject()
                .put("/v1/health", health)
                .put("/v1/identity", identity)
                .put("/v1/diagnostics", diagnostics)
                .put("/v1/contacts", contacts)
                .put("/v1/groups", groupList)
                .put("/v1/messages", sendMessage)
                .put("/v1/groups/messages", sendGroupMessage))
    }

    companion object {
        const val TOKEN_LIFETIME_MS = 15L * 60L * 1000L
        const val MAX_BODY_BYTES = 8_192L
        const val MAX_TEXT_CHARS = 6_000
    }

    private fun respond(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            422 -> "Unprocessable Content"
            429 -> "Too Many Requests"
            503 -> "Service Unavailable"
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
