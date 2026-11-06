package org.relyra.app.core.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import java.security.MessageDigest

class KeyMaterial(val seed: ByteArray, val publicKey: ByteArray)

/** Ed25519 identity primitives (Google Tink). No home-made cryptography. */
object IdentityCrypto {
    const val ID_PREFIX = "rly1"

    fun generate(): KeyMaterial {
        val kp = Ed25519Sign.KeyPair.newKeyPair()
        return KeyMaterial(kp.privateKey, kp.publicKey)
    }

    fun fromSeed(seed: ByteArray): KeyMaterial {
        require(seed.size == 32) { "seed must be 32 bytes" }
        val kp = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed)
        return KeyMaterial(kp.privateKey, kp.publicKey)
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray = Ed25519Sign(seed).sign(message)

    fun verify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean = try {
        Ed25519Verify(publicKey).verify(signature, message)
        true
    } catch (e: GeneralSecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Public ID = "rly1" + base32(publicKey + 2-byte checksum), lowercase. */
    fun publicId(publicKey: ByteArray): String =
        ID_PREFIX + Base32.encode(publicKey + sha256(publicKey).copyOf(2)).lowercase()

    fun parsePublicId(id: String): ByteArray? {
        val s = id.trim().lowercase()
        if (!s.startsWith(ID_PREFIX)) return null
        val raw = Base32.decode(s.removePrefix(ID_PREFIX)) ?: return null
        if (raw.size != 34) return null
        val pk = raw.copyOfRange(0, 32)
        return if (raw.copyOfRange(32, 34).contentEquals(sha256(pk).copyOf(2))) pk else null
    }

    /** 30 hex chars in groups of 5, for out-of-band comparison. */
    fun fingerprint(publicKey: ByteArray): String =
        sha256(publicKey).copyOf(15).joinToString("") { "%02x".format(it) }.chunked(5).joinToString(" ")

    /** Recovery key = base32(seed + 3-byte checksum), grouped by 4. */
    fun recoveryKey(seed: ByteArray): String =
        Base32.encode(seed + sha256(seed).copyOf(3)).chunked(4).joinToString("-")

    fun parseRecoveryKey(text: String): ByteArray? {
        val clean = text.filter { it.isLetterOrDigit() }
        val raw = Base32.decode(clean) ?: return null
        if (raw.size != 35) return null
        val seed = raw.copyOfRange(0, 32)
        return if (raw.copyOfRange(32, 35).contentEquals(sha256(seed).copyOf(3))) seed else null
    }
}
