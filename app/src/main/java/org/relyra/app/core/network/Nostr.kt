package org.relyra.app.core.network

import fr.acinq.secp256k1.Secp256k1
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hex {
    fun encode(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun decode(s: String): ByteArray? {
        if (s.length % 2 != 0 || !s.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** secp256k1 / BIP-340 Schnorr, as Nostr requires. The key is derived from the identity seed, never stored. */
object NostrCrypto {
    private val secp: Secp256k1 get() = Secp256k1.get()

    fun deriveSecret(seed: ByteArray): ByteArray {
        var counter = 0
        while (true) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(seed, "HmacSHA256"))
            val candidate = mac.doFinal("relyra-nostr-v1/$counter".toByteArray())
            if (secp.secKeyVerify(candidate)) return candidate
            counter++
        }
    }

    /** A throw-away key, e.g. to sign a file-server upload without revealing the identity key. */
    fun randomSecret(): ByteArray {
        val rnd = SecureRandom()
        while (true) {
            val candidate = ByteArray(32).also { rnd.nextBytes(it) }
            if (secp.secKeyVerify(candidate)) return candidate
        }
    }

    /** 32-byte x-only public key. */
    fun publicKeyX(secret: ByteArray): ByteArray = secp.pubkeyCreate(secret).copyOfRange(1, 33)
    fun sign(secret: ByteArray, hash32: ByteArray): ByteArray = secp.signSchnorr(hash32, secret, null)
    fun verify(signature: ByteArray, hash32: ByteArray, pubkeyX: ByteArray): Boolean =
        try { secp.verifySchnorr(signature, hash32, pubkeyX) } catch (e: Exception) { false }
}

class NostrEvent(
    val id: String, val pubkey: String, val createdAt: Long, val kind: Int,
    val tags: List<List<String>>, val content: String, val sig: String,
)

/** NIP-01 events. Serialization is hand-written because org.json escapes "/", which would change the event ID. */
object NostrEvents {
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c == '\b' -> sb.append("\\b")
            c == '\u000C' -> sb.append("\\f")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private fun tagsJson(tags: List<List<String>>) =
        tags.joinToString(",", "[", "]") { t -> t.joinToString(",", "[", "]") { "\"${escape(it)}\"" } }

    fun serializeForId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String) =
        "[0,\"$pubkey\",$createdAt,$kind,${tagsJson(tags)},\"${escape(content)}\"]"

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    fun create(secret: ByteArray, kind: Int, tags: List<List<String>>, content: String, createdAt: Long): NostrEvent {
        val pub = Hex.encode(NostrCrypto.publicKeyX(secret))
        val hash = sha256(serializeForId(pub, createdAt, kind, tags, content))
        return NostrEvent(Hex.encode(hash), pub, createdAt, kind, tags, content, Hex.encode(NostrCrypto.sign(secret, hash)))
    }

    fun verify(e: NostrEvent): Boolean {
        val hash = sha256(serializeForId(e.pubkey, e.createdAt, e.kind, e.tags, e.content))
        if (Hex.encode(hash) != e.id) return false
        val sig = Hex.decode(e.sig) ?: return false
        val pub = Hex.decode(e.pubkey) ?: return false
        return sig.size == 64 && pub.size == 32 && NostrCrypto.verify(sig, hash, pub)
    }

    fun toJson(e: NostrEvent) =
        "{\"id\":\"${e.id}\",\"pubkey\":\"${e.pubkey}\",\"created_at\":${e.createdAt},\"kind\":${e.kind}," +
            "\"tags\":${tagsJson(e.tags)},\"content\":\"${escape(e.content)}\",\"sig\":\"${e.sig}\"}"

    fun fromJson(o: JSONObject): NostrEvent? = try {
        val tags = o.getJSONArray("tags").let { arr ->
            (0 until arr.length()).map { i -> arr.getJSONArray(i).let { t -> (0 until t.length()).map { t.getString(it) } } }
        }
        val e = NostrEvent(o.getString("id"), o.getString("pubkey"), o.getLong("created_at"), o.getInt("kind"),
            tags, o.getString("content"), o.getString("sig"))
        if (e.id.length == 64 && e.pubkey.length == 64 && e.sig.length == 128) e else null
    } catch (ex: JSONException) { null }
}

sealed interface RelayMessage {
    class Event(val subId: String, val event: NostrEvent) : RelayMessage
    class Ok(val eventId: String, val accepted: Boolean, val message: String) : RelayMessage
    class Eose(val subId: String) : RelayMessage
    class Notice(val message: String) : RelayMessage
    class Closed(val subId: String, val message: String) : RelayMessage
}

object RelayProtocol {
    fun parse(text: String): RelayMessage? = try {
        val a = JSONArray(text)
        when (a.getString(0)) {
            "EVENT" -> NostrEvents.fromJson(a.getJSONObject(2))?.let { RelayMessage.Event(a.getString(1), it) }
            "OK" -> RelayMessage.Ok(a.getString(1), a.getBoolean(2), a.optString(3, ""))
            "EOSE" -> RelayMessage.Eose(a.getString(1))
            "NOTICE" -> RelayMessage.Notice(a.optString(1, ""))
            "CLOSED" -> RelayMessage.Closed(a.getString(1), a.optString(2, ""))
            else -> null
        }
    } catch (e: JSONException) { null }

    fun req(subId: String, filterJson: String) = "[\"REQ\",\"${NostrEvents.escape(subId)}\",$filterJson]"
    fun close(subId: String) = "[\"CLOSE\",\"${NostrEvents.escape(subId)}\"]"
    fun event(e: NostrEvent) = "[\"EVENT\",${NostrEvents.toJson(e)}]"
}
