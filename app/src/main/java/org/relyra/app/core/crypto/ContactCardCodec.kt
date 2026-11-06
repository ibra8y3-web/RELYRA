package org.relyra.app.core.crypto

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

class ContactCard(val name: String, val publicKey: ByteArray, val encKey: ByteArray? = null, val nostrKey: ByteArray? = null)

sealed interface CardDecode {
    class Ok(val card: ContactCard) : CardDecode
    data object Malformed : CardDecode
    data object BadSignature : CardDecode
}

/** Signed card: relyra://contact?v=2&n=<name>&k=<ed25519>&x=<x25519>&m=<nostr>&s=<signature> (v=1 had no x/m). */
object ContactCardCodec {
    private const val PREFIX = "relyra://contact?"
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    fun sanitizeName(raw: String): String = raw.filterNot { it.isISOControl() }.trim().take(64)

    private fun messageV1(name: String, publicKey: ByteArray): ByteArray =
        "relyra-contact-v1\n$name\n${enc.encodeToString(publicKey)}".toByteArray(Charsets.UTF_8)

    private fun messageV2(name: String, pk: ByteArray, x: ByteArray, m: ByteArray): ByteArray =
        ("relyra-contact-v2\n$name\n${enc.encodeToString(pk)}\n${enc.encodeToString(x)}\n${enc.encodeToString(m)}")
            .toByteArray(Charsets.UTF_8)

    fun encode(name: String, seed: ByteArray, publicKey: ByteArray, encKey: ByteArray, nostrKey: ByteArray): String {
        val clean = sanitizeName(name)
        val sig = IdentityCrypto.sign(seed, messageV2(clean, publicKey, encKey, nostrKey))
        return PREFIX + "v=2" +
            "&n=" + URLEncoder.encode(clean, "UTF-8") +
            "&k=" + enc.encodeToString(publicKey) +
            "&x=" + enc.encodeToString(encKey) +
            "&m=" + enc.encodeToString(nostrKey) +
            "&s=" + enc.encodeToString(sig)
    }

    fun decode(uri: String): CardDecode {
        val text = uri.trim()
        if (!text.startsWith(PREFIX)) return CardDecode.Malformed
        return try {
            val params = text.removePrefix(PREFIX).split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i < 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val v = params["v"]
            if (v != "1" && v != "2") return CardDecode.Malformed
            val name = params["n"] ?: return CardDecode.Malformed
            if (name.isBlank() || name != sanitizeName(name)) return CardDecode.Malformed
            val key = dec.decode(params["k"] ?: return CardDecode.Malformed)
            val sig = dec.decode(params["s"] ?: return CardDecode.Malformed)
            if (key.size != 32 || sig.size != 64) return CardDecode.Malformed
            if (v == "1") {
                return if (IdentityCrypto.verify(key, sig, messageV1(name, key))) CardDecode.Ok(ContactCard(name, key))
                else CardDecode.BadSignature
            }
            val x = dec.decode(params["x"] ?: return CardDecode.Malformed)
            val m = dec.decode(params["m"] ?: return CardDecode.Malformed)
            if (x.size != 32 || m.size != 32) return CardDecode.Malformed
            if (IdentityCrypto.verify(key, sig, messageV2(name, key, x, m))) CardDecode.Ok(ContactCard(name, key, x, m))
            else CardDecode.BadSignature
        } catch (e: IllegalArgumentException) {
            CardDecode.Malformed
        }
    }
}
