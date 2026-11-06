package org.relyra.app.core.crypto

import com.google.crypto.tink.aead.subtle.AesGcmJce
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Message encryption: ephemeral-static + static-static X25519 (sender-authenticated, like HPKE AuthMode),
 * HKDF-SHA256, AES-256-GCM. Built only from Tink primitives. There is NO ratchet yet: if a recipient's long-term
 * key is ever stolen, messages sent to them could be decrypted. The Double Ratchet upgrade is planned separately.
 *
 * Wire: 0x01 | idLen | senderId | ephemeralPublic(32) | AES-GCM(iv | ciphertext | tag)
 * AAD:  everything before the GCM part, plus the recipient ID.
 */
object PayloadCrypto {
    private const val VERSION = 1
    private val INFO = "relyra-e2ee-v1".toByteArray()

    class Header(val senderId: String, val epk: ByteArray, val length: Int)

    fun deriveSecret(seed: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(seed, "HmacSHA256"))
        return mac.doFinal("relyra-x25519-v1".toByteArray())
    }

    fun publicKey(secret: ByteArray): ByteArray = X25519.publicFromPrivate(secret)

    private fun key(dh1: ByteArray, dh2: ByteArray, epk: ByteArray, senderPub: ByteArray, recipientPub: ByteArray): ByteArray =
        Hkdf.computeHkdf("HmacSha256", dh1 + dh2, ByteArray(0), INFO + epk + senderPub + recipientPub, 32)

    fun seal(senderId: String, senderSecret: ByteArray, recipientId: String, recipientPub: ByteArray, plaintext: ByteArray): ByteArray {
        val esk = X25519.generatePrivateKey()
        val epk = X25519.publicFromPrivate(esk)
        val senderPub = X25519.publicFromPrivate(senderSecret)
        val k = key(X25519.computeSharedSecret(esk, recipientPub), X25519.computeSharedSecret(senderSecret, recipientPub),
            epk, senderPub, recipientPub)
        val id = senderId.toByteArray(Charsets.UTF_8)
        require(id.size in 1..255)
        val header = byteArrayOf(VERSION.toByte(), id.size.toByte()) + id + epk
        val ct = AesGcmJce(k).encrypt(plaintext, header + recipientId.toByteArray(Charsets.UTF_8))
        return header + ct
    }

    fun parseHeader(wire: ByteArray): Header? {
        if (wire.size < 2 + 1 + 32 + 28 || wire[0].toInt() != VERSION) return null
        val idLen = wire[1].toInt() and 0xFF
        val end = 2 + idLen + 32
        if (idLen == 0 || wire.size <= end) return null
        return Header(String(wire, 2, idLen, Charsets.UTF_8), wire.copyOfRange(2 + idLen, end), end)
    }

    /** Returns null if anything fails (wrong key, tampering, wrong sender, wrong recipient). */
    fun open(wire: ByteArray, header: Header, ownId: String, ownSecret: ByteArray, senderPub: ByteArray): ByteArray? = try {
        val ownPub = X25519.publicFromPrivate(ownSecret)
        val k = key(X25519.computeSharedSecret(ownSecret, header.epk), X25519.computeSharedSecret(ownSecret, senderPub),
            header.epk, senderPub, ownPub)
        AesGcmJce(k).decrypt(wire.copyOfRange(header.length, wire.size),
            wire.copyOfRange(0, header.length) + ownId.toByteArray(Charsets.UTF_8))
    } catch (e: GeneralSecurityException) { null } catch (e: IllegalArgumentException) { null }
}
