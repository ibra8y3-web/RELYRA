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
import java.security.MessageDigest
import java.security.SecureRandom
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

data class PrivateCommunityInvitation(
    val inviteId: String,
    val communityId: String,
    val ownerPublicId: String,
    val memberPublicId: String,
    val policyVersion: Long,
    val tokenHash: String,
    val createdAt: Long,
    val expiresAt: Long,
    val signature: String,
)

data class PrivateCommunityJoin(
    val manifest: CommunityManifest,
    val memberPublicId: String,
    val joinedAt: Long,
)

private data class PrivateInvitationAcceptance(
    val inviteId: String,
    val communityId: String,
    val memberPublicId: String,
    val acceptedAt: Long,
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

    /**
     * Creates a single-member invitation. The service stores the token hash only.
     * Share the returned JSON solely through RELYRA end-to-end encrypted chat.
     */
    suspend fun createPrivateInvitation(
        manifest: CommunityManifest,
        memberPublicId: String,
        lifetimeMillis: Long = 7L * 24 * 60 * 60 * 1000,
    ): String = withContext(Dispatchers.IO) {
        check(manifest.visibility == "private") { "Only private communities use invitations" }
        val owner = ownPublicId() ?: error("RELYRA identity is unavailable")
        check(owner.equals(manifest.ownerPublicId, true)) { "Only the owner can invite private members" }
        require(IdentityCrypto.parsePublicId(memberPublicId) != null) { "Invalid member RELYRA public ID" }
        require(lifetimeMillis in 60_000L..14L * 24 * 60 * 60 * 1000)
        val tokenBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val tokenHash = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(tokenBytes))
        tokenBytes.fill(0)
        val now = System.currentTimeMillis()
        val unsigned = PrivateCommunityInvitation(
            UUID.randomUUID().toString(), manifest.communityId, owner, memberPublicId.lowercase(),
            manifest.policyVersion, tokenHash, now, now + lifetimeMillis, "",
        )
        val signed = unsigned.copy(signature = signBase64(canonicalInvitation(unsigned)))
        val response = request(
            "POST",
            "/v1/communities/${manifest.communityId}/invitations",
            JSONObject().apply {
                put("invitation", invitationJson(signed))
                put("token", token)
            },
        )
        val accepted = parseInvitation(response.getJSONObject("invitation"))
        check(verifyInvitation(accepted)) { "The service returned an invalid invitation signature" }
        JSONObject().apply {
            put("format", "RELYRA-PRIVATE-COMMUNITY-INVITE-V1")
            put("invitation", invitationJson(accepted))
            put("token", token)
        }.toString()
    }

    suspend fun acceptPrivateInvitation(sharedCode: String): PrivateCommunityJoin = withContext(Dispatchers.IO) {
        require(sharedCode.length in 1..16_384)
        val code = JSONObject(sharedCode)
        check(code.keys().asSequence().toSet() == setOf("format", "invitation", "token")) {
            "Unsupported private invitation format"
        }
        check(code.getString("format") == "RELYRA-PRIVATE-COMMUNITY-INVITE-V1") {
            "Unsupported private invitation format"
        }
        val invitation = parseInvitation(code.getJSONObject("invitation"))
        val token = code.getString("token")
        check(verifyInvitation(invitation)) { "The invitation owner signature is invalid" }
        check(invitation.expiresAt > System.currentTimeMillis()) { "The invitation has expired" }
        val me = ownPublicId() ?: error("RELYRA identity is unavailable")
        check(me.equals(invitation.memberPublicId, true)) {
            "This invitation belongs to a different RELYRA identity"
        }
        check(tokenDigest(token) == invitation.tokenHash) { "The invitation token does not match" }
        val acceptedAt = System.currentTimeMillis()
        val unsigned = PrivateInvitationAcceptance(
            invitation.inviteId, invitation.communityId, me, acceptedAt, "",
        )
        val acceptance = unsigned.copy(
            signature = signBase64(canonicalAcceptance(unsigned, invitation.tokenHash)),
        )
        val response = request(
            "POST",
            "/v1/communities/${invitation.communityId}/invitations/accept",
            JSONObject().apply {
                put("invitation", invitationJson(invitation))
                put("token", token)
                put("acceptance", acceptanceJson(acceptance))
            },
        )
        val membership = response.getJSONObject("membership")
        check(membership.getString("inviteId").equals(invitation.inviteId, true))
        check(membership.getString("memberPublicId").equals(me, true))
        check(membership.getLong("policyVersion") == invitation.policyVersion)
        val manifest = getPrivateCommunity(invitation.communityId)
        check(manifest.ownerPublicId.equals(invitation.ownerPublicId, true))
        PrivateCommunityJoin(manifest, me, membership.getLong("joinedAt"))
    }

    suspend fun getPrivateCommunity(communityId: String): CommunityManifest = withContext(Dispatchers.IO) {
        requireValidCommunityId(communityId)
        val path = "/v1/communities/${communityId}/private"
        val manifest = parseManifest(request("GET", path, privateAccess = true))
        check(manifest.visibility == "private" && verifyManifest(manifest)) {
            "Private community manifest is invalid"
        }
        manifest
    }

    suspend fun moderationEvents(manifest: CommunityManifest, limit: Int = 100): List<CommunityEvent> =
        withContext(Dispatchers.IO) {
            require(limit in 1..100)
            val privateCommunity = manifest.visibility == "private"
            val suffix = if (privateCommunity) "/private/events?limit=" else "/events?limit="
            val path = "/v1/communities/" + manifest.communityId + suffix + limit
            val response = request("GET", path, privateAccess = privateCommunity)
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
        if (action == "remove_member" || action == "allow_post" || action == "unban") {
            check(actor.equals(manifest.ownerPublicId, true)) { "Only the owner may perform this action" }
        }
        val sequencePath = "/v1/communities/" + manifest.communityId + "/sequence/" + actor
        val sequenceJson = request("GET", sequencePath, privateAccess = manifest.visibility == "private")
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

    suspend fun reports(manifest: CommunityManifest, limit: Int = 50): List<CommunityReport> =
        withContext(Dispatchers.IO) {
            require(limit in 1..100)
            val path = "/v1/communities/" + manifest.communityId + "/reports?limit=" + limit
            // The report inbox always requires a signed RELYRA identity, including for public communities.
            val array = request("GET", path, privateAccess = true).getJSONArray("reports")
            buildList {
                for (index in 0 until array.length()) {
                    val report = parseReport(array.getJSONObject(index))
                    if (verifyReport(report, manifest)) add(report)
                }
            }
        }

    fun verifyReport(report: CommunityReport, manifest: CommunityManifest): Boolean {
        if (!report.communityId.equals(manifest.communityId, true) ||
            !validCommunityId(report.reportId) ||
            !report.category.matches(Regex("[a-z0-9_-]{1,48}")) ||
            report.summary.isBlank() || report.summary.length > 512 ||
            report.summary.contains('\u0000') || report.createdAt <= 0
        ) return false
        val now = System.currentTimeMillis()
        if (report.createdAt > now + 5 * 60 * 1000 || now - report.createdAt > 30L * 24 * 60 * 60 * 1000) {
            return false
        }
        val publicKey = IdentityCrypto.parsePublicId(report.reporterPublicId) ?: return false
        val signature = decodeSignature(report.signature) ?: return false
        return IdentityCrypto.verify(publicKey, signature, canonicalReport(report))
    }

    fun verifyManifest(manifest: CommunityManifest): Boolean {
        if (!validCommunityId(manifest.communityId) || manifest.name.isBlank() ||
            manifest.name.length > 80 || manifest.description.length > 1000 ||
            (manifest.visibility != "public" && manifest.visibility != "private") ||
            manifest.policyVersion < 1 || manifest.createdAt <= 0 || manifest.updatedAt < manifest.createdAt ||
            manifest.moderatorPublicIds.size > 32 || manifest.ownerPublicId.isBlank()
        ) return false
        val now = System.currentTimeMillis()
        if (manifest.createdAt > now + 5 * 60 * 1000 ||
            manifest.updatedAt > now + 5 * 60 * 1000 ||
            now - manifest.createdAt > 3650L * 24 * 60 * 60 * 1000
        ) return false
        if (manifest.moderatorPublicIds.map { it.lowercase() }.distinct().size != manifest.moderatorPublicIds.size ||
            manifest.moderatorPublicIds.any { IdentityCrypto.parsePublicId(it) == null }
        ) return false
        val publicKey = IdentityCrypto.parsePublicId(manifest.ownerPublicId) ?: return false
        val signature = decodeSignature(manifest.signature) ?: return false
        return IdentityCrypto.verify(publicKey, signature, canonicalManifest(manifest))
    }

    fun verifyEvent(event: CommunityEvent, manifest: CommunityManifest): Boolean {
        if (!event.communityId.equals(manifest.communityId, true) ||
            event.policyVersion != manifest.policyVersion || event.sequence < 1 ||
            event.reasonCode.isBlank() || event.createdAt <= 0 ||
            event.action !in setOf("ban", "unban", "mute", "slow_mode", "remove_member", "allow_post")
        ) return false
        val now = System.currentTimeMillis()
        if (event.createdAt > now + 5 * 60 * 1000 ||
            now - event.createdAt > 30L * 24 * 60 * 60 * 1000 ||
            (event.expiresAt != 0L && event.expiresAt <= now) ||
            !event.reasonCode.matches(Regex("[a-z0-9_-]{1,32}")) ||
            event.actorPublicId.equals(event.subjectPublicId, true)
        ) return false
        if (!isModerator(manifest, event.actorPublicId)) return false
        if ((event.action == "remove_member" || event.action == "allow_post" || event.action == "unban") &&
            !event.actorPublicId.equals(manifest.ownerPublicId, true)
        ) return false
        val publicKey = IdentityCrypto.parsePublicId(event.actorPublicId) ?: return false
        val signature = decodeSignature(event.signature) ?: return false
        return IdentityCrypto.verify(publicKey, signature, canonicalEvent(event))
    }

    private fun isModerator(manifest: CommunityManifest, id: String): Boolean =
        id.equals(manifest.ownerPublicId, true) ||
            manifest.moderatorPublicIds.any { it.equals(id, true) }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        privateAccess: Boolean = false,
    ): JSONObject {
        val base = baseUrl()?.trimEnd('/')
            ?: error("Add and enable a RELYRA discovery gateway in Network settings first")
        val normalized = org.relyra.app.core.network.DiscoveryGatewayUrl.normalize(base)
            ?: error("The configured discovery gateway URL is invalid")
        val builder = Request.Builder().url(normalized + path).header("Accept", "application/json")
        if (privateAccess) {
            val member = ownPublicId() ?: error("RELYRA identity is unavailable")
            val timestamp = System.currentTimeMillis()
            val proof = canonicalAccess(method, path, member, timestamp)
            builder.header("X-RELYRA-Public-ID", member)
                .header("X-RELYRA-Timestamp", timestamp.toString())
                .header("X-RELYRA-Signature", signBase64(proof))
        }
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

    private fun canonicalInvitation(i: PrivateCommunityInvitation): ByteArray = listOf(
        "RELYRA-COMMUNITY-INVITATION-V1",
        i.inviteId.lowercase(), i.communityId.lowercase(), i.ownerPublicId.lowercase(),
        i.memberPublicId.lowercase(), i.policyVersion.toString(), i.tokenHash,
        i.createdAt.toString(), i.expiresAt.toString(),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun canonicalAcceptance(a: PrivateInvitationAcceptance, tokenHash: String): ByteArray = listOf(
        "RELYRA-COMMUNITY-ACCEPTANCE-V1",
        a.inviteId.lowercase(), a.communityId.lowercase(), a.memberPublicId.lowercase(),
        tokenHash, a.acceptedAt.toString(),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun canonicalAccess(method: String, path: String, memberId: String, timestamp: Long): ByteArray = listOf(
        "RELYRA-COMMUNITY-ACCESS-V1",
        method.uppercase(), path, memberId.lowercase(), timestamp.toString(),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun tokenDigest(token: String): String {
        val bytes = Base64.getUrlDecoder().decode(token)
        require(bytes.size == 32) { "Invitation token must contain 32 random bytes" }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun verifyInvitation(invitation: PrivateCommunityInvitation): Boolean {
        if (!validCommunityId(invitation.communityId) || !validCommunityId(invitation.inviteId) ||
            invitation.policyVersion <= 0 || invitation.createdAt <= 0 ||
            invitation.expiresAt <= invitation.createdAt ||
            IdentityCrypto.parsePublicId(invitation.ownerPublicId) == null ||
            IdentityCrypto.parsePublicId(invitation.memberPublicId) == null
        ) return false
        val publicKey = IdentityCrypto.parsePublicId(invitation.ownerPublicId) ?: return false
        val signature = decodeSignature(invitation.signature) ?: return false
        return IdentityCrypto.verify(publicKey, signature, canonicalInvitation(invitation))
    }

    private fun invitationJson(i: PrivateCommunityInvitation) = JSONObject().apply {
        put("version", 1); put("inviteId", i.inviteId); put("communityId", i.communityId)
        put("ownerPublicId", i.ownerPublicId); put("memberPublicId", i.memberPublicId)
        put("policyVersion", i.policyVersion); put("tokenHash", i.tokenHash)
        put("createdAt", i.createdAt); put("expiresAt", i.expiresAt); put("signature", i.signature)
    }

    private fun acceptanceJson(a: PrivateInvitationAcceptance) = JSONObject().apply {
        put("version", 1); put("inviteId", a.inviteId); put("communityId", a.communityId)
        put("memberPublicId", a.memberPublicId); put("acceptedAt", a.acceptedAt); put("signature", a.signature)
    }

    private fun parseInvitation(j: JSONObject) = PrivateCommunityInvitation(
        j.getString("inviteId"), j.getString("communityId"), j.getString("ownerPublicId"),
        j.getString("memberPublicId"), j.getLong("policyVersion"), j.getString("tokenHash"),
        j.getLong("createdAt"), j.getLong("expiresAt"), j.getString("signature"),
    )

    private fun canonicalReport(r: CommunityReport): ByteArray = listOf(
        "RELYRA-COMMUNITY-REPORT-V1", r.reportId.lowercase(), r.communityId.lowercase(),
        r.reporterPublicId.lowercase(), r.category, r.summary, r.createdAt.toString(),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    fun serializeManifest(manifest: CommunityManifest): String = manifestJson(manifest).toString()

    fun deserializeAndVerifyManifest(raw: String): CommunityManifest {
        val manifest = parseManifest(JSONObject(raw))
        require(verifyManifest(manifest)) { "Saved community owner signature is invalid" }
        return manifest
    }

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

    private fun parseReport(j: JSONObject) = CommunityReport(
        j.getString("reportId"), j.getString("communityId"), j.getString("reporterPublicId"),
        j.getString("category"), j.getString("summary"), j.getLong("createdAt"), j.getString("signature"),
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
