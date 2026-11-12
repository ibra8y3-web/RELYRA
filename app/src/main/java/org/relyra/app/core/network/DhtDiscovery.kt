package org.relyra.app.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.relyra.app.core.crypto.IdentityCrypto
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

data class PeerPresence(
    val publicId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val capabilities: List<String>,
)

enum class DhtDiscoveryStatus { Disabled, NoGateway, Publishing, Published, Unavailable }

object PeerPresenceCodec {
    const val VERSION = 1
    const val TTL_SECONDS = 180L
    const val MAX_RECORD_CHARS = 8_192
    private const val DOMAIN = "RELYRA-DHT-PRESENCE-V1"
    private val allowedCapabilities = setOf("nostr-signal-v1", "webrtc-data-v1")

    fun encode(
        publicId: String,
        issuedAt: Long = System.currentTimeMillis() / 1000,
        sign: (ByteArray) -> ByteArray?,
    ): String? {
        val normalizedId = publicId.trim().lowercase()
        val publicKey = IdentityCrypto.parsePublicId(normalizedId) ?: return null
        val publicKeyText = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey)
        val expiresAt = issuedAt + TTL_SECONDS
        val capabilities = allowedCapabilities.sorted()
        val payload = signingBytes(normalizedId, publicKeyText, issuedAt, expiresAt, capabilities)
        val signature = sign(payload)?.takeIf { it.size == 64 } ?: return null
        val json = JSONObject()
            .put("version", VERSION)
            .put("publicId", normalizedId)
            .put("publicKey", publicKeyText)
            .put("issuedAt", issuedAt)
            .put("expiresAt", expiresAt)
            .put("capabilities", JSONArray(capabilities))
            .put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
            .toString()
        return json.takeIf { it.length <= MAX_RECORD_CHARS }
    }

    fun decode(
        jsonText: String,
        expectedPublicId: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): PeerPresence? {
        if (jsonText.isBlank() || jsonText.length > MAX_RECORD_CHARS) return null
        return try {
            val json = JSONObject(jsonText)
            if (json.getInt("version") != VERSION) return null
            val publicId = json.getString("publicId").lowercase()
            if (publicId != expectedPublicId.trim().lowercase()) return null
            val publicKey = IdentityCrypto.parsePublicId(publicId) ?: return null
            val publicKeyText = json.getString("publicKey")
            val decodedKey = Base64.getUrlDecoder().decode(publicKeyText)
            if (!MessageDigest.isEqual(publicKey, decodedKey)) return null
            val issuedAt = json.getLong("issuedAt")
            val expiresAt = json.getLong("expiresAt")
            if (issuedAt <= 0 || issuedAt > nowSeconds + 60 ||
                expiresAt <= nowSeconds || expiresAt <= issuedAt ||
                expiresAt - issuedAt > TTL_SECONDS
            ) return null
            val array = json.getJSONArray("capabilities")
            if (array.length() !in 1..allowedCapabilities.size) return null
            val capabilities = (0 until array.length()).map { array.getString(it) }
            if (capabilities.toSet().size != capabilities.size ||
                capabilities.any { it !in allowedCapabilities }
            ) return null
            val canonicalCapabilities = capabilities.sorted()
            val signature = Base64.getUrlDecoder().decode(json.getString("signature"))
            val payload = signingBytes(publicId, publicKeyText, issuedAt, expiresAt, canonicalCapabilities)
            if (signature.size != 64 || !IdentityCrypto.verify(publicKey, signature, payload)) return null
            PeerPresence(publicId, issuedAt, expiresAt, canonicalCapabilities)
        } catch (_: Exception) {
            null
        }
    }

    private fun signingBytes(
        publicId: String,
        publicKey: String,
        issuedAt: Long,
        expiresAt: Long,
        capabilities: List<String>,
    ): ByteArray = listOf(
        DOMAIN,
        publicId,
        publicKey,
        issuedAt.toString(),
        expiresAt.toString(),
        capabilities.sorted().joinToString(","),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)
}

class DhtDiscoveryClient(
    baseClient: OkHttpClient = Http.client,
) {
    private val http = baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun publish(baseUrl: String, signedRecordJson: String): Boolean = withContext(Dispatchers.IO) {
        val body = signedRecordJson.toRequestBody(jsonType)
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/v1/records").post(body).build()
        http.newCall(request).execute().use { it.code == 202 }
    }

    suspend fun lookup(baseUrl: String, publicId: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/records/${publicId.trim().lowercase()}")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> response.body?.string()?.takeIf { it.length <= PeerPresenceCodec.MAX_RECORD_CHARS }
                else -> throw java.io.IOException("discovery gateway HTTP ${response.code}")
            }
        }
    }

    suspend fun turnCredentials(
        baseUrl: String,
        publicId: String,
        sign: (ByteArray) -> ByteArray?,
    ): List<IceServerConfig>? = withContext(Dispatchers.IO) {
        val nonceBytes = ByteArray(24).also(SecureRandom()::nextBytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        val timestamp = System.currentTimeMillis() / 1000
        val transcript = listOf(
            "RELYRA-TURN-CREDENTIALS-V1",
            publicId.trim().lowercase(),
            timestamp.toString(),
            nonce,
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
        val signature = sign(transcript)?.takeIf { it.size == 64 } ?: return@withContext null
        val body = JSONObject()
            .put("publicId", publicId.trim().lowercase())
            .put("timestamp", timestamp)
            .put("nonce", nonce)
            .put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
            .toString()
            .toRequestBody(jsonType)
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/turn-credentials")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body?.string().orEmpty())
            val username = json.getString("username")
            val credential = json.getString("credential")
            val urls = json.getJSONArray("urls")
            val servers = (0 until urls.length()).map { index ->
                IceServerConfig(urls.getString(index), username, credential)
            }
            servers.takeIf { it.isNotEmpty() && it.all(IceServerConfig::isValid) }
        }
    }

    suspend fun test(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/healthz").get().build()
        http.newCall(request).execute().use { it.isSuccessful }
    }
}

class DhtDiscoveryManager(
    private val network: NetworkManager,
    private val ownId: suspend () -> String?,
    private val sign: (ByteArray) -> ByteArray?,
    private val scope: CoroutineScope,
    private val client: DhtDiscoveryClient = DhtDiscoveryClient(),
) {
    private val _status = MutableStateFlow(DhtDiscoveryStatus.Disabled)
    val status: StateFlow<DhtDiscoveryStatus> = _status
    private val _foundPresence = MutableStateFlow<PeerPresence?>(null)
    val foundPresence: StateFlow<PeerPresence?> = _foundPresence

    init {
        scope.launch(Dispatchers.IO) {
            combine(network.discoveryGateways, network.publicDiscoveryEnabled) { gateways, enabled ->
                gateways.filter { it.enabled } to enabled
            }.collectLatest { (gateways, enabled) ->
                when {
                    !enabled -> _status.value = DhtDiscoveryStatus.Disabled
                    gateways.isEmpty() -> _status.value = DhtDiscoveryStatus.NoGateway
                    else -> {
                        while (currentCoroutineContext().isActive) {
                            val id = try {
                                ownId()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                            val signedRecord = try {
                                id?.let { PeerPresenceCodec.encode(it, sign = sign) }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                            if (signedRecord == null) {
                                _status.value = DhtDiscoveryStatus.Unavailable
                            } else {
                                _status.value = DhtDiscoveryStatus.Publishing
                                var published = 0
                                for (gateway in gateways) {
                                    try {
                                        if (withTimeout(10_000) { client.publish(gateway.url, signedRecord) }) published++
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        // Continue with the next configured gateway.
                                    }
                                }
                                _status.value = if (published > 0) DhtDiscoveryStatus.Published else DhtDiscoveryStatus.Unavailable
                            }
                            delay(PeerPresenceCodec.TTL_SECONDS * 500L)
                        }
                    }
                }
            }
        }
    }

    suspend fun lookup(publicId: String): PeerPresence? {
        _foundPresence.value = null
        val normalized = publicId.trim().lowercase()
        if (IdentityCrypto.parsePublicId(normalized) == null) return null
        for (gateway in network.discoveryGateways.value.filter { it.enabled }) {
            try {
                val json = withTimeout(12_000) { client.lookup(gateway.url, normalized) } ?: continue
                val presence = PeerPresenceCodec.decode(json, normalized) ?: continue
                _foundPresence.value = presence
                return presence
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Try the next operator-configured gateway.
            }
        }
        return null
    }

    suspend fun fetchTurnCredentials(): List<IceServerConfig>? {
        val id = try {
            ownId()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        if (IdentityCrypto.parsePublicId(id) == null) return null
        for (gateway in network.discoveryGateways.value.filter { it.enabled }) {
            try {
                val servers = withTimeout(12_000) { client.turnCredentials(gateway.url, id, sign) }
                if (!servers.isNullOrEmpty()) return servers
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Try the next gateway.
            }
        }
        return null
    }

    suspend fun testGateway(url: String): Boolean = try {
        withTimeout(8_000) { client.test(url) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
