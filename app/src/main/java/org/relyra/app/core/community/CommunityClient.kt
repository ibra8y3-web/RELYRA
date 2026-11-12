package org.relyra.app.core.community

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.relyra.app.core.crypto.IdentityCrypto
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CommunityManifest(
    val communityId: String,
    val ownerPublicId: String,
    val name: String,
    val description: String,
    val visibility: String,
    val moderatorPublicIds: List<String>,
    val policyVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val signature: String,
)

data class CommunityEvent(
    val eventId: String,
    val communityId: String,
    val actorPublicId: String,
    val sequence: Long,
    val policyVersion: Long,
    val action: String,
    val subjectPublicId: String,
    val reasonCode: String,
    val createdAt: Long,
    val expiresAt: Long,
    val signature: String,
)

data class CommunityReport(
    val reportId: String,
    val communityId: String,
    val reporterPublicId: String,
    val category: String,
    val summary: String,
    val createdAt: Long,
    val signature: String,
)

class CommunityClient(
    private val baseUrl: () -> String?,
    private val ownPublicId: suspend () -> String?,
    private val sign: suspend (ByteArray) -> ByteArray,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun discover(limit: Int = 30): List<CommunityManifest> = withContext(Dispatchers.IO) {
        require(limit in 1..50)
        val response = request("GET", "/v1/communities?limit=$limit")
        val array = response.getJSONArray("communities")
        buildList {
            for (index in 0 until array.length()) {
                val manifest = parseManifest(array.getJSONObject(index))
                if (verifyManifest(manifest)) add(manifest)
            }
        }
    }

    suspend fun getPublicCommunity(communityId: String): CommunityManifest = withContext(Dispatchers.IO) {
        requireValidCommunityId(communityId)
        val manifest = parseManifest(request("GET", "/v1/communities/" + communityId))
        check(verifyManifest(manifest)) { "Community owner signature is invalid" }
        manifest
    }

    suspend fun createCommunity(name: String, description: String, visibility: String): CommunityManifest =
        withContext(Dispatchers.IO) {
            require(visibility == "public" || visibility == "private")
            val owner = ownPublicId() ?: error("RELYRA identity is unavailable")
            val now = System.currentTimeMillis()
            val unsigned = CommunityManifest(
                UUID.randomUUID().toString(), owner, name.trim(), description.trim(), visibility,
                emptyList(), 1, now, now, "",
            )
            val signed = unsigned.copy(signature = signBase64(canonicalManifest(unsigned)))
            val result = parseManifest(request("POST", "/v1/communities", manifestJson(signed)))
            check(verifyManifest(result)) { "Server returned an invalid community manifest" }
            result
        }

    suspend fun updateModerators(manifest: CommunityManifest, moderators: List<String>): CommunityManifest =
        withContext(Dispatchers.IO) {
            val owner = ownPublicId() ?: error("RELYRA identity is unavailable")
            require(owner.equals(manifest.ownerPublicId, ignoreCase = true)) { "Only the owner can manage moderators" }
            val now = maxOf(System.currentTimeMillis(), manifest.updatedAt + 1)
            val unsigned = manifest.copy(
                moderatorPublicIds = moderators.map { it.trim().lowercase() }.distinct().sorted(),
                policyVersion = manifest.policyVersion + 1,
                updatedAt = now,
                signature = "",
            )
            val signed = unsigned.copy(signature = signBase64(canonicalManifest(unsigned)))
            val result = parseManifest(request("POST", "/v1/communities", manifestJson(signed)))
            check(verifyManifest(result)) { "Server returned an invalid community manifest" }
            result
        }

    suspend fun moderationEvents(manifest: CommunityManifest, limit: Int = 100): List<CommunityEvent> =
        withContext(Dispatchers.IO) {
            require(limit in 1..100)
            val path = "/v1/communities/" + manifest.communityId + "/events?limit=" + limit
            val response = request("GET", path)
            val array = response.getJSONArray("events")
            buildList {
                for (index in 0 until array.length()) {
                    val event = parseEvent(array.getJSONObject(index))
                    if (verifyEvent(event, manifest)) add(event)
                }
            }
        }

    suspend fun moderate(
        manifest: CommunityManifest,
        action: String,
        subjectPublicId: String,
        reasonCode: String,
        expiresAt: Long = 0,
    ): CommunityEvent = withContext(Dispatchers.IO) {
        val actor = ownPublicId() ?: error("RELYRA identity is unavailable")
        check(isModerator(manifest, actor)) { "This identity is not a community moderator" }
        if (action == "remove_member" || action == "allow_post") {
            check(actor.equals(manifest.ownerPublicId, true)) { "Only the owner may perform this action" }
        }
        val sequencePath = "/v1/communities/" + manifest.communityId + "/sequence/" + actor
        val sequenceJson = request("GET", sequencePath)
        val sequence = sequenceJson.getLong("lastSequence") + 1
        val now = System.currentTimeMillis()
        val unsigned = CommunityEvent(
            UUID.randomUUID().toString(), manifest.communityId, actor, sequence,
            manifest.policyVersion, action, subjectPublicId.trim().lowercase(),
            reasonCode.trim().lowercase(), now, expiresAt, "",
        )
        val signed = unsigned.copy(signature = signBase64(canonicalEvent(unsigned)))
        val path = "/v1/communities/" + manifest.communityId + "/events"
        val result = parseEvent(request("POST", path, eventJson(signed)).getJSONObject("event"))
        check(verifyEvent(result, manifest)) { "Server returned an invalid moderation event" }
        result
    }

    suspend fun submitReport(
        communityId: String,
        category: String,
        redactedSummary: String,
    ): String = withContext(Dispatchers.IO) {
        val reporter = ownPublicId() ?: error("RELYRA identity is unavailable")
        val unsigned = CommunityReport(
            UUID.randomUUID().toString(), communityId, reporter,
            category.trim().lowercase(), redactedSummary.trim(), System.currentTimeMillis(), "",
        )
        val signed = unsigned.copy(signature = signBase64(canonicalReport(unsigned)))
        val path = "/v1/communities/" + communityId + "/reports"
        request("POST", path, reportJson(signed)).getString("reportId")
    }

    fun verifyManifest(manifest: CommunityManifest): Boolean {
        if (!validCommunityId(manifest.communityId) || manifest.name.isBlank() ||
            manifest.name.length > 80 || manifest.description.length > 1000 ||
            (manifest.visibility != "public" && manifest.visibility != "private") ||
            manifest.policyVersion < 1 || manifest.createdAt <= 0 || manifest.updatedAt < manifest.createdAt ||
            manifest.moderatorPublicIds.size > 32 || manifest.ownerPublicId.isBlank()
        ) return false
        val publicKey = IdentityCrypto.parsePublicId(manifest.ownerPublicId) ?: return false
        val signature = decodeSignature(manifest.signature) ?: return false
        return IdentityCrypto.verify(publicKey, signature, canonicalManifest(manifest))
    }

    fun verifyEvent(event: CommunityEvent, manifest: CommunityManifest): Boolean {
        if (!event.communityId.equals(manifest.communityId, true) ||
            event.policyVersion != manifest.policyVersion || event.sequence < 1 ||
            event.reasonCode.isBlank() || event.createdAt <= 0
        ) return false
        if (!isModerator(manifest, event.actorPublicId)) return false
        if ((event.action == "remove_member" || event.action == "allow_post") &&
            !event.actorPublicId.equals(manifest.ownerPublicId, true)
        ) return false
        val publicKey = IdentityCrypto.parsePublicId(event.actorPublicId) ?: return false
        val signature = decodeSignature(event.signature) ?: return false
        return IdentityCrypto.verify(publicKey, signature, canonicalEvent(event))
    }

    private fun isModerator(manifest: CommunityManifest, id: String): Boolean =
        id.equals(manifest.ownerPublicId, true) ||
            manifest.moderatorPublicIds.any { it.equals(id, true) }

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val base = baseUrl()?.trimEnd('/')
            ?: error("Add and enable a RELYRA discovery gateway in Network settings first")
        val normalized = org.relyra.app.core.network.DiscoveryGatewayUrl.normalize(base)
            ?: error("The configured discovery gateway URL is invalid")
        val builder = Request.Builder().url(normalized + path).header("Accept", "application/json")
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
            else -> error("Unsupported community API method")
        }
        client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = runCatching { JSONObject(raw).optString("error") }.getOrNull()
                throw IOException(reason?.takeIf { it.isNotBlank() } ?: "Community service returned HTTP " + response.code)
            }
            return JSONObject(raw)
        }
    }

    private suspend fun signBase64(payload: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload))

    private fun canonicalManifest(m: CommunityManifest): ByteArray = listOf(
        "RELYRA-COMMUNITY-MANIFEST-V1", m.communityId.lowercase(), m.ownerPublicId.lowercase(),
        m.name, m.description, m.visibility,
        m.moderatorPublicIds.map { it.lowercase() }.sorted().joinToString(","),
        m.policyVersion.toString(), m.createdAt.toString(), m.updatedAt.toString(),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun canonicalEvent(e: CommunityEvent): ByteArray = listOf(
        "RELYRA-COMMUNITY-MODERATION-V1", e.eventId.lowercase(), e.communityId.lowercase(),
        e.actorPublicId.lowercase(), e.sequence.toString(), e.policyVersion.toString(), e.action,
        e.subjectPublicId.lowercase(), e.reasonCode, e.createdAt.toString(), e.expiresAt.toString(),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun canonicalReport(r: CommunityReport): ByteArray = listOf(
        "RELYRA-COMMUNITY-REPORT-V1", r.reportId.lowercase(), r.communityId.lowercase(),
        r.reporterPublicId.lowercase(), r.category, r.summary, r.createdAt.toString(),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun manifestJson(m: CommunityManifest) = JSONObject().apply {
        put("version", 1); put("communityId", m.communityId); put("ownerPublicId", m.ownerPublicId)
        put("name", m.name); put("description", m.description); put("visibility", m.visibility)
        put("moderatorPublicIds", JSONArray(m.moderatorPublicIds)); put("policyVersion", m.policyVersion)
        put("createdAt", m.createdAt); put("updatedAt", m.updatedAt); put("signature", m.signature)
    }

    private fun eventJson(e: CommunityEvent) = JSONObject().apply {
        put("version", 1); put("eventId", e.eventId); put("communityId", e.communityId)
        put("actorPublicId", e.actorPublicId); put("sequence", e.sequence); put("policyVersion", e.policyVersion)
        put("action", e.action); put("subjectPublicId", e.subjectPublicId); put("reasonCode", e.reasonCode)
        put("createdAt", e.createdAt); put("expiresAt", e.expiresAt); put("signature", e.signature)
    }

    private fun reportJson(r: CommunityReport) = JSONObject().apply {
        put("version", 1); put("reportId", r.reportId); put("communityId", r.communityId)
        put("reporterPublicId", r.reporterPublicId); put("category", r.category)
        put("summary", r.summary); put("createdAt", r.createdAt); put("signature", r.signature)
    }

    private fun parseManifest(j: JSONObject) = CommunityManifest(
        j.getString("communityId"), j.getString("ownerPublicId"), j.getString("name"),
        j.optString("description"), j.getString("visibility"),
        j.optJSONArray("moderatorPublicIds").toStringList(), j.getLong("policyVersion"),
        j.getLong("createdAt"), j.getLong("updatedAt"), j.getString("signature"),
    )

    private fun parseEvent(j: JSONObject) = CommunityEvent(
        j.getString("eventId"), j.getString("communityId"), j.getString("actorPublicId"),
        j.getLong("sequence"), j.getLong("policyVersion"), j.getString("action"),
        j.getString("subjectPublicId"), j.getString("reasonCode"), j.getLong("createdAt"),
        j.optLong("expiresAt"), j.getString("signature"),
    )

    private fun JSONArray?.toStringList(): List<String> =
        this?.let { array -> List(array.length()) { array.getString(it) } } ?: emptyList()

    private fun decodeSignature(value: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(value)
    } catch (_: IllegalArgumentException) { null }

    private fun validCommunityId(value: String): Boolean =
        runCatching { UUID.fromString(value).toString().equals(value, true) }.getOrDefault(false)

    private fun requireValidCommunityId(value: String) {
        require(validCommunityId(value)) { "Invalid community ID" }
    }
}
