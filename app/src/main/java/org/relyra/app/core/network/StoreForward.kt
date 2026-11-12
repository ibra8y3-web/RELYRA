package org.relyra.app.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.messaging.SendOutcome
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

data class StoreForwardEnvelope(
    val messageId: String,
    val senderPublicId: String,
    val recipientPublicId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val payload: ByteArray,
    val digest: String,
)

object StoreForwardEnvelopeCodec {
    const val VERSION = 1
    const val MAX_PAYLOAD_BYTES = 64 * 1024
    const val MAX_TTL_SECONDS = 24 * 60 * 60L
    private const val DOMAIN = "RELYRA-STORE-FORWARD-V1"
    private const val ACCESS_DOMAIN = "RELYRA-COMMUNITY-ACCESS-V1"

    fun encode(
        senderPublicId: String,
        recipientPublicId: String,
        payload: ByteArray,
        issuedAt: Long = System.currentTimeMillis() / 1000,
        expiresAt: Long = issuedAt + MAX_TTL_SECONDS,
        sign: (ByteArray) -> ByteArray?,
    ): String? {
        val sender = senderPublicId.trim().lowercase()
        val recipient = recipientPublicId.trim().lowercase()
        if (IdentityCrypto.parsePublicId(sender) == null ||
            IdentityCrypto.parsePublicId(recipient) == null ||
            sender == recipient || payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES ||
            issuedAt <= 0 || expiresAt <= issuedAt ||
            expiresAt - issuedAt > MAX_TTL_SECONDS
        ) return null
        val digest = sha256Hex(payload)
        val signature = sign(signingBytes(digest, sender, recipient, issuedAt, expiresAt))
            ?.takeIf { it.size == 64 } ?: return null
        return JSONObject()
            .put("version", VERSION)
            .put("messageId", digest)
            .put("senderPublicId", sender)
            .put("recipientPublicId", recipient)
            .put("issuedAt", issuedAt)
            .put("expiresAt", expiresAt)
            .put("payload", Base64.getUrlEncoder().withoutPadding().encodeToString(payload))
            .put("digest", digest)
            .put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
            .toString()
    }

    fun decode(
        jsonText: String,
        expectedRecipient: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): StoreForwardEnvelope? {
        if (jsonText.isBlank() || jsonText.length > 96 * 1024) return null
        return try {
            val json = JSONObject(jsonText)
            if (json.getInt("version") != VERSION) return null
            val id = json.getString("messageId")
            val sender = json.getString("senderPublicId").lowercase()
            val recipient = json.getString("recipientPublicId").lowercase()
            val issued = json.getLong("issuedAt")
            val expires = json.getLong("expiresAt")
            val digest = json.getString("digest").lowercase()
            if (!id.matches(Regex("^[0-9a-f]{64}$")) || id != digest ||
                recipient != expectedRecipient.trim().lowercase() ||
                IdentityCrypto.parsePublicId(sender) == null ||
                IdentityCrypto.parsePublicId(recipient) == null || sender == recipient ||
                issued <= 0 || issued > nowSeconds + 60 || nowSeconds - issued > 5 * 60 ||
                expires <= nowSeconds || expires <= issued || expires - issued > MAX_TTL_SECONDS
            ) return null
            val payload = Base64.getUrlDecoder().decode(json.getString("payload"))
            if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES || sha256Hex(payload) != digest) return null
            val signature = Base64.getUrlDecoder().decode(json.getString("signature"))
            val publicKey = IdentityCrypto.parsePublicId(sender) ?: return null
            if (signature.size != 64 ||
                !IdentityCrypto.verify(publicKey, signature, signingBytes(id, sender, recipient, issued, expires))
            ) return null
            StoreForwardEnvelope(id, sender, recipient, issued, expires, payload, digest)
        } catch (_: Exception) {
            null
        }
    }

    fun accessProof(
        method: String,
        requestPath: String,
        publicId: String,
        timestampMillis: Long,
        sign: (ByteArray) -> ByteArray?,
    ): String? {
        val id = publicId.trim().lowercase()
        if (IdentityCrypto.parsePublicId(id) == null ||
            method.isBlank() || requestPath.isBlank() || timestampMillis <= 0
        ) return null
        val transcript = listOf(
            ACCESS_DOMAIN,
            method.uppercase(),
            requestPath,
            id,
            timestampMillis.toString(),
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
        val signature = sign(transcript)?.takeIf { it.size == 64 } ?: return null
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }

    private fun signingBytes(
        digest: String,
        sender: String,
        recipient: String,
        issuedAt: Long,
        expiresAt: Long,
    ): ByteArray = listOf(
        DOMAIN, digest, sender, recipient, issuedAt.toString(), expiresAt.toString(), digest,
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
}

class StoreForwardClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun enqueue(
        baseUrl: String,
        senderId: String,
        recipientId: String,
        encryptedWirePayload: ByteArray,
        sign: (ByteArray) -> ByteArray?,
    ): Boolean = withContext(Dispatchers.IO) {
        val envelope = StoreForwardEnvelopeCodec.encode(
            senderId, recipientId, encryptedWirePayload, sign = sign,
        ) ?: return@withContext false
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/mailbox")
            .post(envelope.toRequestBody(jsonType))
            .build()
        execute(request) { response ->
            if (!response.isSuccessful) return@execute false
            val body = response.body?.string().orEmpty()
            runCatching { JSONObject(body).optBoolean("accepted", false) }.getOrDefault(false)
        }
    }

    suspend fun poll(
        baseUrl: String,
        ownId: String,
        sign: (ByteArray) -> ByteArray?,
        acceptWire: suspend (ByteArray) -> Boolean,
    ): Int = withContext(Dispatchers.IO) {
        val path = "/v1/mailbox?limit=20"
        val now = System.currentTimeMillis()
        val signature = StoreForwardEnvelopeCodec.accessProof(
            "GET", path, ownId, now, sign,
        ) ?: return@withContext 0
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("X-RELYRA-Public-ID", ownId)
            .header("X-RELYRA-Timestamp", now.toString())
            .header("X-RELYRA-Signature", signature)
            .get()
            .build()
        val items = execute(request) { response ->
            if (!response.isSuccessful) return@execute emptyList()
            val array = JSONObject(response.body?.string().orEmpty()).optJSONArray("messages") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    val encoded = array.optJSONObject(index)?.toString() ?: continue
                    val envelope = StoreForwardEnvelopeCodec.decode(encoded, ownId) ?: continue
                    add(envelope)
                }
            }
        }
        var delivered = 0
        for (envelope in items) {
            val accepted = try {
                acceptWire(envelope.payload)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (accepted && acknowledge(baseUrl, ownId, envelope.messageId, sign)) delivered++
        }
        delivered
    }

    private suspend fun acknowledge(
        baseUrl: String,
        ownId: String,
        messageId: String,
        sign: (ByteArray) -> ByteArray?,
    ): Boolean = withContext(Dispatchers.IO) {
        val path = "/v1/mailbox/$messageId"
        val now = System.currentTimeMillis()
        val signature = StoreForwardEnvelopeCodec.accessProof("DELETE", path, ownId, now, sign)
            ?: return@withContext false
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("X-RELYRA-Public-ID", ownId)
            .header("X-RELYRA-Timestamp", now.toString())
            .header("X-RELYRA-Signature", signature)
            .delete(ByteArray(0).toRequestBody(jsonType))
            .build()
        execute(request) { it.isSuccessful }
    }

    private inline fun <T> execute(request: Request, read: (okhttp3.Response) -> T): T {
        return http.newCall(request).execute().use(read)
    }
}

class StoreForwardConnector(
    private val network: NetworkManager,
    private val ownId: suspend () -> String?,
    private val sign: (ByteArray) -> ByteArray?,
    private val onWire: suspend (ByteArray) -> Boolean,
    scope: CoroutineScope,
    private val client: StoreForwardClient = StoreForwardClient(),
) : Connector {
    override val name: String = "Store-and-Forward"

    init {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!network.storeForwardEnabled.value) continue
                val identity = ownId() ?: continue
                network.storeForwardGateways.value.filter { it.enabled }.forEach { gateway ->
                    try {
                        client.poll(gateway.url, identity, sign, onWire)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: IOException) {
                        // Retry on the next bounded poll interval.
                    } catch (_: Exception) {
                        // A failing gateway must not stop polling other configured gateways.
                    }
                }
            }
        }
    }

    override fun isReady(): Boolean =
        network.storeForwardEnabled.value && network.storeForwardGateways.value.any { it.enabled }

    override suspend fun send(toPeer: String, payload: ByteArray): SendOutcome {
        if (!isReady()) return SendOutcome.Unavailable
        val sender = ownId() ?: return SendOutcome.Rejected("local RELYRA identity is unavailable")
        for (gateway in network.storeForwardGateways.value.filter { it.enabled }) {
            try {
                if (client.enqueue(gateway.url, sender, toPeer, payload, sign)) return SendOutcome.Relayed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Try the next operator-managed store-and-forward gateway.
            }
        }
        return SendOutcome.Unavailable
    }

    private companion object {
        const val POLL_INTERVAL_MS = 30_000L
    }
}
