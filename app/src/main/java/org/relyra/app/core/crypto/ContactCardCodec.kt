package org.relyra.app.core.crypto

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

class ContactCard(
    val name: String,
    val publicKey: ByteArray,
    val encKey: ByteArray? = null,
    val nostrKey: ByteArray? = null,
    val preKeyBundle: X3dh.PreKeyBundle? = null,
)

sealed interface CardDecode {
    class Ok(val card: ContactCard) : CardDecode
    data object Malformed : CardDecode
    data object BadSignature : CardDecode
}

/** Signed contact cards: v1 identity only, v2 adds transport keys, v3 adds an authenticated X3DH prekey bundle. */
object ContactCardCodec {
    private const val PREFIX = "relyra://contact?"
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    fun sanitizeName(raw: String): String = raw.filterNot { it.isISOControl() }.trim().take(64)
    private fun b64(bytes: ByteArray) = enc.encodeToString(bytes)
    private fun messageV1(name: String, publicKey: ByteArray): ByteArray =
        "relyra-contact-v1\n$name\n${b64(publicKey)}".toByteArray(Charsets.UTF_8)
    private fun messageV2(name: String, pk: ByteArray, x: ByteArray, m: ByteArray): ByteArray =
        "relyra-contact-v2\n$name\n${b64(pk)}\n${b64(x)}\n${b64(m)}".toByteArray(Charsets.UTF_8)

    private fun messageV3(
        name: String, pk: ByteArray, x: ByteArray, m: ByteArray,
        bundle: X3dh.PreKeyBundle,
    ): ByteArray = listOf(
        "relyra-contact-v3", name, b64(pk), b64(x), b64(m), bundle.identityId,
        bundle.signedPreKeyId.toString(), b64(bundle.signedPreKey),
        bundle.signedPreKeyExpiresAt.toString(), b64(bundle.signedPreKeySignature),
        bundle.oneTimePreKeyId?.toString().orEmpty(), bundle.oneTimePreKey?.let(::b64).orEmpty(),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    fun encode(name: String, seed: ByteArray, publicKey: ByteArray, encKey: ByteArray, nostrKey: ByteArray): String {
        val clean = sanitizeName(name)
        val sig = IdentityCrypto.sign(seed, messageV2(clean, publicKey, encKey, nostrKey))
        return PREFIX + "v=2&n=" + URLEncoder.encode(clean, "UTF-8") +
            "&k=" + b64(publicKey) + "&x=" + b64(encKey) + "&m=" + b64(nostrKey) + "&s=" + b64(sig)
    }

    fun encodeV3(
        name: String, seed: ByteArray, publicKey: ByteArray, encKey: ByteArray,
        nostrKey: ByteArray, bundle: X3dh.PreKeyBundle,
    ): String {
        require(bundle.identityId == IdentityCrypto.publicId(publicKey))
        require(bundle.identityDhKey.contentEquals(encKey))
        val clean = sanitizeName(name)
        val sig = IdentityCrypto.sign(seed, messageV3(clean, publicKey, encKey, nostrKey, bundle))
        val params = linkedMapOf(
            "v" to "3", "n" to clean, "k" to b64(publicKey), "x" to b64(encKey), "m" to b64(nostrKey),
            "pi" to bundle.signedPreKeyId.toString(), "p" to b64(bundle.signedPreKey),
            "pe" to bundle.signedPreKeyExpiresAt.toString(), "ps" to b64(bundle.signedPreKeySignature),
            "oi" to (bundle.oneTimePreKeyId?.toString() ?: ""), "o" to (bundle.oneTimePreKey?.let(::b64) ?: ""),
            "s" to b64(sig),
        )
        return PREFIX + params.entries.joinToString("&") { (k, v) ->
            k + "=" + if (k == "n") URLEncoder.encode(v, "UTF-8") else v
        }
    }

    fun decode(uri: String): CardDecode {
        val text = uri.trim()
        if (!text.startsWith(PREFIX)) return CardDecode.Malformed
        return try {
            val params = text.removePrefix(PREFIX).split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i < 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val version = params["v"]
            if (version !in setOf("1", "2", "3")) return CardDecode.Malformed
            val name = params["n"] ?: return CardDecode.Malformed
            if (name.isBlank() || name != sanitizeName(name)) return CardDecode.Malformed
            val key = dec.decode(params["k"] ?: return CardDecode.Malformed)
            val sig = dec.decode(params["s"] ?: return CardDecode.Malformed)
            if (key.size != 32 || sig.size != 64) return CardDecode.Malformed
            if (version == "1") {
                return if (IdentityCrypto.verify(key, sig, messageV1(name, key))) CardDecode.Ok(ContactCard(name, key))
                else CardDecode.BadSignature
            }
            val x = dec.decode(params["x"] ?: return CardDecode.Malformed)
            val m = dec.decode(params["m"] ?: return CardDecode.Malformed)
            if (x.size != 32 || m.size != 32) return CardDecode.Malformed
            if (version == "2") {
                return if (IdentityCrypto.verify(key, sig, messageV2(name, key, x, m))) CardDecode.Ok(ContactCard(name, key, x, m))
                else CardDecode.BadSignature
            }
            val signedId = params["pi"]?.toLongOrNull() ?: return CardDecode.Malformed
            val signed = dec.decode(params["p"] ?: return CardDecode.Malformed)
            val expiry = params["pe"]?.toLongOrNull() ?: return CardDecode.Malformed
            val signedSig = dec.decode(params["ps"] ?: return CardDecode.Malformed)
            val oneTimeIdText = params["oi"].orEmpty()
            val oneTimeId = if (oneTimeIdText.isEmpty()) null else oneTimeIdText.toLongOrNull() ?: return CardDecode.Malformed
            val oneTime = params["o"]?.takeIf { it.isNotEmpty() }?.let(dec::decode)
            if (signedId < 0 || signed.size != 32 || expiry <= 0 || signedSig.size != 64 ||
                ((oneTimeId == null) != (oneTime == null)) || (oneTime != null && oneTime.size != 32)) return CardDecode.Malformed
            val bundle = X3dh.PreKeyBundle(
                IdentityCrypto.publicId(key), key, x, signedId, signed, expiry, signedSig, oneTimeId, oneTime,
            )
            if (!IdentityCrypto.verify(key, sig, messageV3(name, key, x, m, bundle))) CardDecode.BadSignature
            else CardDecode.Ok(ContactCard(name, key, x, m, bundle))
        } catch (_: IllegalArgumentException) {
            CardDecode.Malformed
        }
    }
}
